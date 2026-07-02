package com.watchocr.app.service

import android.app.Notification
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.database.ContentObserver
import android.os.Build
import android.os.IBinder
import android.provider.MediaStore
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.watchocr.app.NotificationChannels
import com.watchocr.app.data.AppDatabase
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Foreground service that watches a user-selected MediaStore bucket (folder)
 * for newly added images and runs each one through [OcrProcessor]. A
 * [ContentObserver] on the images collection triggers a scan as soon as
 * MediaStore changes; a periodic fallback sweep covers missed notifications.
 * Only images added after the folder was selected are processed.
 */
class DirectoryMonitorService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var monitorJob: Job? = null

    /** Last processing error, kept visible in the idle notification until a file succeeds. */
    private var lastErrorText: String? = null

    /** Signalled by [contentObserver] whenever the images collection changes. */
    private val changeSignal = Channel<Unit>(Channel.CONFLATED)

    private val contentObserver = object : ContentObserver(null) {
        override fun onChange(selfChange: Boolean) {
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

            try {
                scanBucket(bucketId, settings.watchStartMillis, settings.apiKey, settings.model, db)
            } catch (e: Exception) {
                updateNotification("Monitor error: ${e.message}")
            }

            // Scan again as soon as MediaStore reports a change, or after the
            // fallback interval in case a change notification was missed.
            withTimeoutOrNull(FALLBACK_SCAN_INTERVAL_MS) { changeSignal.receive() }
        }
    }

    private suspend fun scanBucket(
        bucketId: Long,
        watchStartMillis: Long,
        apiKey: String,
        model: String,
        db: AppDatabase
    ) {
        val images = MediaStoreImages.queryBucketImages(applicationContext, bucketId, watchStartMillis)
        val dao = db.monitoredFileDao()
        val knownFiles = dao.getAll().associateBy { it.documentUri }

        // Pre-Q MediaStore has no IS_PENDING flag, so a row can appear while the
        // file is still being written; require its size to be stable across two
        // scans before processing. On Q+ pending rows are excluded from queries.
        val requireStableSize = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q

        for (image in images) {
            val uriString = image.uri.toString()
            val known = knownFiles[uriString]

            if (known == null) {
                dao.insert(
                    MonitoredFile(uriString, image.dateAddedMillis, processed = false, sizeBytes = image.sizeBytes)
                )
                if (requireStableSize) continue
            } else {
                if (known.processed || known.failedAttempts > MAX_RETRIES) continue
                if (requireStableSize && image.sizeBytes != known.sizeBytes) {
                    // Still being written; refresh the snapshot and check again next scan.
                    dao.insert(known.copy(sizeBytes = image.sizeBytes))
                    continue
                }
            }

            updateNotification("Processing ${image.displayName}…")
            val result = OcrProcessor.processImage(applicationContext, image.uri, apiKey, model)
            result.onSuccess {
                dao.markProcessed(uriString)
                lastErrorText = null
            }.onFailure {
                dao.incrementFailedAttempts(uriString)
                lastErrorText = "Failed to process ${image.displayName}: ${it.message}"
            }
        }

        updateNotification(lastErrorText ?: "Watching for new images…")
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
        private const val NOTIFICATION_ID = 1001

        /** A failed file is retried on later scans at most this many times. */
        private const val MAX_RETRIES = 3

        // The ContentObserver is the primary trigger on Q+, where this sweep is
        // only a safety net. Pre-Q it also drives the size-stability check, so
        // it has to stay short there.
        private val FALLBACK_SCAN_INTERVAL_MS =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) 60_000L else 5_000L

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, DirectoryMonitorService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, DirectoryMonitorService::class.java))
        }
    }
}
