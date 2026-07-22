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
import java.io.FileNotFoundException
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
        createNotificationChannels()
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

    // Deliberate: swiping the app away from recents is the user's way of
    // stopping monitoring; opening the app again resumes it.
    override fun onTaskRemoved(rootIntent: Intent?) {
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        fileObserver?.stopWatching()
        fileObserver = null
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

        if (!settings.canMonitor) {
            stopSelf()
            return
        }
        // canMonitor guarantees a bucketId.
        val bucketId = checkNotNull(settings.bucketId)

        // Installs upgraded from the MediaStore-based version have a bucketId
        // but no persisted path — resolve it once and persist.
        val dirPath = settings.watchedDirPath
            ?: MediaStoreImages.queryBucketPath(applicationContext, bucketId)
                ?.also { settingsDataStore.setWatchedDirPath(it) }
        if (dirPath == null || !File(dirPath).isDirectory) {
            stopWithAlert("Watched folder unavailable — re-select it in Settings.")
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
        val idleText = "Watching ${bucketName ?: dirPath} for new images…"

        // Some camera apps close a file, then reopen it to write EXIF and
        // close again — two CLOSE_WRITE events for one image.
        val recentlyDone = LinkedHashMap<String, Long>()

        // Inside the try so the finally owns the observer from the moment it
        // exists: an exception on the way into the loop must not leave a live
        // observer feeding a channel nobody reads.
        try {
            startObserver(dirPath)
            updateNotification(lastErrorText ?: idleText)
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
                    stopWithAlert("Gemini API key is not set — monitoring stopped. Set it in Settings to resume.")
                    return
                }
                Log.i(TAG, "processing ${file.name}")
                updateNotification("Processing ${file.name}…")

                OcrProcessor.withActiveJob {
                    processWithRetry(file, current.apiKey, current.model)
                }.onSuccess {
                    Log.i(TAG, "processed ${file.name}")
                    lastErrorText = null
                    // Dedup successes only: writers that create the file empty
                    // and fill it in a second pass (two CLOSE_WRITEs) must stay
                    // eligible for the event that carries the real content.
                    recentlyDone[file.path] = SystemClock.elapsedRealtime()
                }.onFailure {
                    Log.w(TAG, "failed ${file.name}: ${it.message}")
                    lastErrorText = "Failed to process ${file.name}: ${OcrProcessor.describeFailure(it)}"
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
        // No previous observer to stop: reconcileMonitor joins the old
        // monitorLoop before launching a new one, and that loop's finally
        // always stops and clears the observer it started.
        fileObserver = object : FileObserver(dirPath, CLOSE_WRITE or MOVED_TO) {
            // Called on FileObserver's own thread: filter cheaply, hand off.
            override fun onEvent(event: Int, path: String?) {
                if (path == null || path.startsWith(".")) return // .pending-*, .trashed-*
                if (OcrProcessor.mimeForFileName(path) == null) return
                newFiles.trySend(File(dirPath, path))
            }
        }.also { it.startWatching() }
    }

    // Wrapped in OcrProcessor.withActiveJob by the caller so the whole retry
    // cycle counts as one in-flight job.
    private suspend fun processWithRetry(file: File, apiKey: String, model: String): Result<OcrRecord> {
        val uri = Uri.fromFile(file)
        var attempt = 1
        while (true) {
            val result = OcrProcessor.processImage(applicationContext, uri, apiKey, model)
            if (result.isSuccess || attempt >= MAX_ATTEMPTS || !isRetryable(result.exceptionOrNull())) {
                return result
            }
            Log.w(TAG, "retrying ${file.name} (attempt ${attempt + 1}): ${result.exceptionOrNull()?.message}")
            delay(RETRY_DELAY_MS)
            attempt++
        }
    }

    /**
     * 4xx responses other than 429 are permanent (invalid API key: 400/403,
     * unprocessable image: 400) — retrying them is pointless. So is a file
     * deleted/renamed after its event (FileNotFoundException): it won't
     * reappear, and if it does it fires a new event.
     */
    private fun isRetryable(e: Throwable?): Boolean = when (e) {
        is FileNotFoundException -> false
        is IOException -> true
        is ApiHttpException -> e.code == 429 || e.code in 500..599
        else -> false
    }

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

    private fun createNotificationChannels() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            android.app.NotificationChannel(
                MONITOR_CHANNEL_ID,
                "Directory Monitor",
                NotificationManager.IMPORTANCE_LOW
            )
        )
        // Alerts ask the user to act (monitoring stopped, folder gone), so they
        // must be audible/heads-up — unlike the silent ongoing status channel.
        manager.createNotificationChannel(
            android.app.NotificationChannel(
                ALERT_CHANNEL_ID,
                "Monitoring Alerts",
                NotificationManager.IMPORTANCE_DEFAULT
            )
        )
    }

    private fun buildNotification(
        text: String,
        ongoing: Boolean = true,
        channelId: String = MONITOR_CHANNEL_ID
    ): Notification {
        return NotificationCompat.Builder(this, channelId)
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

    /**
     * Stops the service, leaving [text] behind as a dismissible notification
     * that outlives it. Not updateNotification: stopSelf() takes the
     * foreground notification down with the service, so a message the user
     * has to act on must go out as a standalone one.
     */
    private fun stopWithAlert(text: String) {
        getSystemService(NotificationManager::class.java)
            .notify(ALERT_NOTIFICATION_ID, buildNotification(text, ongoing = false, channelId = ALERT_CHANNEL_ID))
        stopSelf()
    }

    companion object {
        private const val TAG = "WatchOCR"

        private const val MONITOR_CHANNEL_ID = "directory_monitor"

        /** For [stopWithAlert]; higher importance than the silent monitor channel. */
        private const val ALERT_CHANNEL_ID = "monitor_alerts"

        private const val NOTIFICATION_ID = 1001

        /** For [stopWithAlert]; distinct from the foreground notification's ID. */
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
    }
}
