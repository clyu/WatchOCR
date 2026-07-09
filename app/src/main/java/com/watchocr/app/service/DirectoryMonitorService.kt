package com.watchocr.app.service

import android.app.Notification
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.FileObserver
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.watchocr.app.NotificationChannels
import com.watchocr.app.data.HistoryCleanup
import com.watchocr.app.data.MediaStoreImages
import com.watchocr.app.data.OcrRecord
import com.watchocr.app.data.SettingsDataStore
import com.watchocr.app.network.ApiHttpException
import com.watchocr.app.ocr.OcrProcessor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.IOException

/**
 * Foreground service that watches the user-selected folder for newly written
 * images via [FileObserver] (inotify) and runs each one through
 * [OcrProcessor]. CLOSE_WRITE fires only after a writer closes the file and
 * MOVED_TO only after a rename of a fully written file (the MediaStore
 * IS_PENDING pattern publishes `.pending-*` files this way), so a reported
 * file is complete — no size polling or processed-file bookkeeping is needed.
 */
class DirectoryMonitorService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val settingsDataStore by lazy { SettingsDataStore(applicationContext) }
    private var monitorJob: Job? = null
    private var cleanupJob: Job? = null

    /** Serializes [reconcileMonitor] so overlapping start() calls cannot race. */
    private val reconcileLock = Mutex()

    /** Directory the running monitor loop watches; null when no loop is running. */
    private var watchingDirPath: String? = null

    /** Strong reference: a GC'd FileObserver silently stops watching. */
    private var fileObserver: FileObserver? = null

    /** Files reported by [fileObserver]; UNLIMITED so bursts are not dropped. */
    private val newFiles = Channel<File>(Channel.UNLIMITED)

    /** Last processing error, kept visible in the idle notification until a file succeeds. */
    private var lastErrorText: String? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        val notification = buildNotification("Watching for new images…")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // start() is called liberally (every app open, configuration change,
        // watched-bucket change); reconcileMonitor restarts the loop only when
        // the watched directory actually changed, so a redundant start cannot
        // cancel (and lose) a file mid-processing.
        serviceScope.launch { reconcileMonitor() }
        if (cleanupJob?.isActive != true) {
            cleanupJob = serviceScope.launch { cleanupLoop() }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onTaskRemoved(rootIntent: Intent?) {
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        fileObserver?.stopWatching()
        fileObserver = null
        monitorJob?.cancel()
        serviceScope.cancel()
        super.onDestroy()
    }

    /**
     * Resolves the configured directory and (re)starts [monitorLoop] for it,
     * leaving an already-running loop untouched when the directory is
     * unchanged. Joining the old loop before starting the new one keeps its
     * cleanup from stopping the new loop's observer.
     */
    private suspend fun reconcileMonitor(): Unit = reconcileLock.withLock {
        val settings = settingsDataStore.settingsFlow.first()
        val bucketId = settings.bucketId

        if (bucketId == null || settings.apiKey.isBlank()) {
            stopSelf()
            return
        }

        // Installs upgraded from the MediaStore-based version have a bucketId
        // but no persisted path — resolve it once and persist.
        val dirPath = settings.watchedDirPath
            ?: MediaStoreImages.queryBucketPath(applicationContext, bucketId)
                ?.also { settingsDataStore.setWatchedDirPath(it) }
        if (dirPath == null || !File(dirPath).isDirectory) {
            // Not updateNotification: stopSelf() removes the foreground
            // notification, so the message must go out as a standalone one.
            postAlertNotification("Watched folder unavailable — re-select it in Settings.")
            stopSelf()
            return
        }

        if (monitorJob?.isActive == true && dirPath == watchingDirPath) return

        monitorJob?.cancelAndJoin()
        // The old loop's observer is stopped by now and the new one not yet
        // started, so everything still queued is from the previous folder —
        // drop it rather than process it under the new folder.
        while (newFiles.tryReceive().isSuccess) { /* discard */ }
        watchingDirPath = dirPath
        monitorJob = serviceScope.launch { monitorLoop(dirPath, settings.bucketName) }
    }

    private suspend fun monitorLoop(dirPath: String, bucketName: String?) {
        startObserver(dirPath)
        val idleText = "Watching ${bucketName ?: dirPath} for new images…"
        updateNotification(lastErrorText ?: idleText)

        // Some camera apps close a file, then reopen it to write EXIF and
        // close again — two CLOSE_WRITE events for one image.
        val recentlyDone = LinkedHashMap<String, Long>()

        try {
            for (file in newFiles) {
                val now = SystemClock.elapsedRealtime()
                recentlyDone.entries.removeAll { now - it.value > DEDUP_WINDOW_MS }
                if (recentlyDone.containsKey(file.path)) {
                    Log.d(TAG, "duplicate event for ${file.name}, skipping")
                    continue
                }
                if (!file.isFile) continue // renamed/deleted since the event
                if (file.length() == 0L) {
                    // Creation handshake of a two-pass writer; the write that
                    // fills the file triggers its own CLOSE_WRITE.
                    Log.d(TAG, "${file.name} is empty, awaiting next write")
                    continue
                }

                val current = settingsDataStore.settingsFlow.first()
                if (current.apiKey.isBlank()) {
                    // Key cleared after startup: every upload would fail, so
                    // stop instead of burning retries; MainActivity restarts
                    // the service once a key is set again.
                    Log.w(TAG, "API key cleared, stopping monitor")
                    postAlertNotification("Gemini API key is not set — monitoring stopped. Set it in Settings to resume.")
                    stopSelf()
                    return
                }
                Log.i(TAG, "processing ${file.name}")
                updateNotification("Processing ${file.name}…")

                processWithRetry(file, current.apiKey, current.model).onSuccess {
                    Log.i(TAG, "processed ${file.name}")
                    lastErrorText = null
                    // Dedup successes only: writers that create the file empty
                    // and fill it in a second pass (two CLOSE_WRITEs) must stay
                    // eligible for the event that carries the real content.
                    recentlyDone[file.path] = SystemClock.elapsedRealtime()
                }.onFailure {
                    Log.w(TAG, "failed ${file.name}: ${it.message}")
                    lastErrorText = "Failed to process ${file.name}: ${it.message}"
                }
                updateNotification(lastErrorText ?: idleText)
            }
        } finally {
            fileObserver?.stopWatching()
            fileObserver = null
        }
    }

    @Suppress("DEPRECATION") // String ctor: the File overload is API 29+, minSdk is 26
    private fun startObserver(dirPath: String) {
        fileObserver?.stopWatching()
        fileObserver = object : FileObserver(dirPath, CLOSE_WRITE or MOVED_TO) {
            // Called on FileObserver's own thread: filter cheaply, hand off.
            override fun onEvent(event: Int, path: String?) {
                if (path == null || path.startsWith(".")) return // .pending-*, .trashed-*
                if (path.substringAfterLast('.', "").lowercase() !in OcrProcessor.MIME_BY_EXTENSION.keys) return
                newFiles.trySend(File(dirPath, path))
            }
        }.also { it.startWatching() }
    }

    private suspend fun processWithRetry(file: File, apiKey: String, model: String): Result<OcrRecord> {
        val uri = Uri.fromFile(file)
        var result = OcrProcessor.processImage(applicationContext, uri, apiKey, model)
        var attempt = 1
        while (result.isFailure && attempt < MAX_ATTEMPTS && isRetryable(result.exceptionOrNull())) {
            Log.w(TAG, "retrying ${file.name} (attempt ${attempt + 1}): ${result.exceptionOrNull()?.message}")
            delay(RETRY_DELAY_MS)
            result = OcrProcessor.processImage(applicationContext, uri, apiKey, model)
            attempt++
        }
        return result
    }

    /**
     * 4xx responses other than 429 are permanent (invalid API key: 400/403,
     * unprocessable image: 400) — retrying them is pointless.
     */
    private fun isRetryable(e: Throwable?): Boolean =
        e is IOException || (e is ApiHttpException && (e.code == 429 || e.code in 500..599))

    /**
     * Long-running service: enforces the history retention setting periodically,
     * so it applies even when the app UI is never opened.
     */
    private suspend fun cleanupLoop() {
        while (currentCoroutineContext().isActive) {
            HistoryCleanup.deleteOlderThan(applicationContext, settingsDataStore.settingsFlow.first().retentionDays)
            delay(CLEANUP_INTERVAL_MS)
        }
    }

    private fun createNotificationChannel() {
        val channel = android.app.NotificationChannel(
            NotificationChannels.MONITOR_CHANNEL_ID,
            "Directory Monitor",
            NotificationManager.IMPORTANCE_LOW
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(text: String, ongoing: Boolean = true): Notification {
        return NotificationCompat.Builder(this, NotificationChannels.MONITOR_CHANNEL_ID)
            .setContentTitle("WatchOCR")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(ongoing)
            .setAutoCancel(!ongoing)
            .build()
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(text))
    }

    /** A dismissible notification that outlives the service (and its foreground notification). */
    private fun postAlertNotification(text: String) {
        getSystemService(NotificationManager::class.java)
            .notify(ALERT_NOTIFICATION_ID, buildNotification(text, ongoing = false))
    }

    companion object {
        private const val TAG = "WatchOCR"

        private const val NOTIFICATION_ID = 1001

        /** For [postAlertNotification]; distinct from the foreground notification's ID. */
        private const val ALERT_NOTIFICATION_ID = 1002

        /** Attempts per file for transient (network/429/5xx) failures. */
        private const val MAX_ATTEMPTS = 3

        /** Delay between attempts on a transient failure. */
        private const val RETRY_DELAY_MS = 15_000L

        /** Duplicate events for the same path within this window are dropped. */
        private const val DEDUP_WINDOW_MS = 10_000L

        private const val CLEANUP_INTERVAL_MS = 60 * 60 * 1000L

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, DirectoryMonitorService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, DirectoryMonitorService::class.java))
        }
    }
}
