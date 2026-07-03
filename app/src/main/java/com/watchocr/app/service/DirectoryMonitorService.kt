package com.watchocr.app.service

import android.app.Notification
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.provider.MediaStore
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.watchocr.app.NotificationChannels
import com.watchocr.app.data.AppDatabase
import com.watchocr.app.data.HistoryCleanup
import com.watchocr.app.data.MediaStoreImages
import com.watchocr.app.data.MonitoredFile
import com.watchocr.app.data.SettingsDataStore
import com.watchocr.app.ocr.OcrProcessor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Foreground service that watches a user-selected MediaStore bucket (folder)
 * for newly added images and runs each one through [OcrProcessor]. A
 * [ContentObserver] on the images collection triggers a scan as soon as
 * MediaStore changes. Only images added after the folder was selected are
 * processed. Before OCR, each file's real on-disk size is polled until it
 * stabilizes, so partially-written files are not uploaded.
 */
class DirectoryMonitorService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var monitorJob: Job? = null

    /** Last processing error, kept visible in the idle notification until a file succeeds. */
    private var lastErrorText: String? = null

    private var lastCleanupMillis = 0L

    /** Signalled by [contentObserver] whenever the images collection changes. */
    private val changeSignal = Channel<Unit>(Channel.CONFLATED)

    private val contentObserver = object : ContentObserver(null) {
        override fun onChange(selfChange: Boolean) {
            Log.d(TAG, "MediaStore change event")
            changeSignal.trySend(Unit)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        val notification = buildNotification("Watching for new images…")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        contentResolver.registerContentObserver(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            true,
            contentObserver
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (monitorJob?.isActive != true) {
            monitorJob = serviceScope.launch { monitorLoop() }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onTaskRemoved(rootIntent: Intent?) {
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        contentResolver.unregisterContentObserver(contentObserver)
        monitorJob?.cancel()
        serviceScope.cancel()
        super.onDestroy()
    }

    private suspend fun monitorLoop() {
        val settingsDataStore = SettingsDataStore(applicationContext)
        val db = AppDatabase.getInstance(applicationContext)

        while (serviceScope.isActive) {
            val settings = settingsDataStore.settingsFlow.first()
            val bucketId = settings.bucketId

            if (bucketId == null || settings.apiKey.isBlank()) {
                stopSelf()
                return
            }

            var retryPending = false
            try {
                retryPending =
                    scanBucket(bucketId, settings.watchStartMillis, settings.apiKey, settings.model, db)

                // Long-running service: enforce the history retention setting
                // periodically, so it applies even when the app UI is never opened.
                val now = System.currentTimeMillis()
                if (now - lastCleanupMillis >= CLEANUP_INTERVAL_MS) {
                    HistoryCleanup.deleteOlderThan(applicationContext, settings.retentionDays)
                    lastCleanupMillis = now
                }
            } catch (e: Exception) {
                updateNotification("Monitor error: ${e.message}")
                retryPending = true
            }

            // Scan again as soon as MediaStore reports a change. If a file
            // failed with retry attempts left (or the scan itself failed),
            // also retry after a delay even when no change event arrives.
            if (retryPending) {
                val signalled = withTimeoutOrNull(RETRY_DELAY_MS) { changeSignal.receive() }
                Log.d(TAG, if (signalled != null) "woke: MediaStore change" else "woke: retry")
            } else {
                changeSignal.receive()
                Log.d(TAG, "woke: MediaStore change")
            }
        }
    }

    /**
     * Scans [bucketId] and processes every new image. A MediaStore row can
     * appear while the file is still being written (writers that skip
     * IS_PENDING, media-scanner indexing), on any Android version, so
     * [waitForStableSize] polls the file's real size until it settles before
     * OCR runs. Failed files are retried on later scans, up to [MAX_ATTEMPTS]
     * attempts each.
     *
     * @return true when at least one file failed with retry attempts left, so
     *   a timed retry is needed even without another MediaStore change event.
     */
    private suspend fun scanBucket(
        bucketId: Long,
        watchStartMillis: Long,
        apiKey: String,
        model: String,
        db: AppDatabase
    ): Boolean {
        val images = MediaStoreImages.queryBucketImages(applicationContext, bucketId, watchStartMillis)
        val dao = db.monitoredFileDao()
        val knownFiles = dao.getAll().associateBy { it.documentUri }
        var retryPending = false
        Log.d(TAG, "scanning bucket $bucketId: ${images.size} images since watch start, ${knownFiles.size} known")

        for (image in images) {
            val uriString = image.uri.toString()
            val known = knownFiles[uriString]
            if (known != null && (known.processed || known.failedAttempts >= MAX_ATTEMPTS)) continue
            if (known == null) dao.insert(MonitoredFile(uriString))
            val attemptsSoFar = known?.failedAttempts ?: 0

            Log.i(TAG, "processing ${image.displayName}")
            updateNotification("Processing ${image.displayName}…")
            waitForStableSize(image.uri)

            val result = OcrProcessor.processImage(applicationContext, image.uri, apiKey, model)
            result.onSuccess {
                Log.i(TAG, "processed ${image.displayName}")
                dao.markProcessed(uriString)
                lastErrorText = null
            }.onFailure {
                Log.w(TAG, "failed ${image.displayName} (attempt ${attemptsSoFar + 1}): ${it.message}")
                dao.incrementFailedAttempts(uriString)
                if (attemptsSoFar + 1 < MAX_ATTEMPTS) retryPending = true
                lastErrorText = "Failed to process ${image.displayName}: ${it.message}"
            }
        }

        updateNotification(lastErrorText ?: "Watching for new images…")
        return retryPending
    }

    /**
     * Best-effort wait until the file's real on-disk size (AssetFileDescriptor
     * length) is non-zero and unchanged across two consecutive polls. Gives up
     * after [STABILITY_MAX_POLLS] and returns anyway: [OcrProcessor] rejects
     * empty/undecodable content, and failures are retried on the next wake.
     */
    private suspend fun waitForStableSize(uri: Uri) {
        var lastSize = -1L
        repeat(STABILITY_MAX_POLLS) {
            val size = try {
                contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: return
            } catch (e: Exception) {
                return // deleted or unreadable; let processImage surface the real error
            }
            if (size > 0 && size == lastSize) return // >0 also handles UNKNOWN_LENGTH (-1)
            lastSize = size
            delay(STABILITY_POLL_MS)
        }
        Log.w(TAG, "size of $uri never stabilized; processing anyway")
    }

    private fun createNotificationChannel() {
        val channel = android.app.NotificationChannel(
            NotificationChannels.MONITOR_CHANNEL_ID,
            "Directory Monitor",
            NotificationManager.IMPORTANCE_LOW
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification {
        return NotificationCompat.Builder(this, NotificationChannels.MONITOR_CHANNEL_ID)
            .setContentTitle("WatchOCR")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(text))
    }

    companion object {
        private const val TAG = "WatchOCR"

        private const val NOTIFICATION_ID = 1001

        /** A file is OCR'd at most this many times before it is given up on. */
        private const val MAX_ATTEMPTS = 3

        /** Delay before retrying failed files when no MediaStore change arrives. */
        private const val RETRY_DELAY_MS = 60_000L

        /** Interval between file-size polls while waiting for a file to stabilize. */
        private const val STABILITY_POLL_MS = 500L

        /** Maximum size polls per file before processing it anyway. */
        private const val STABILITY_MAX_POLLS = 20

        private const val CLEANUP_INTERVAL_MS = 60 * 60 * 1000L

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, DirectoryMonitorService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, DirectoryMonitorService::class.java))
        }
    }
}
