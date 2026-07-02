package com.watchocr.app.service

import android.app.Notification
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import com.watchocr.app.NotificationChannels
import com.watchocr.app.data.AppDatabase
import com.watchocr.app.data.MonitoredFile
import com.watchocr.app.data.SettingsDataStore
import com.watchocr.app.ocr.OcrProcessor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Foreground service that polls a user-selected SAF directory for newly created
 * image files and runs each new file through [OcrProcessor]. On its first ever
 * poll it records existing files as a baseline without processing them, so only
 * files created afterwards trigger OCR.
 */
class DirectoryMonitorService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var monitorJob: Job? = null

    /** Last processing error, kept visible in the idle notification until a file succeeds. */
    private var lastErrorText: String? = null

    override fun onCreate() {
        super.onCreate()
        val notification = buildNotification("Watching for new images…")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
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
        monitorJob?.cancel()
        serviceScope.cancel()
        super.onDestroy()
    }

    private suspend fun monitorLoop() {
        val settingsDataStore = SettingsDataStore(applicationContext)
        val db = AppDatabase.getInstance(applicationContext)

        while (serviceScope.isActive) {
            val settings = settingsDataStore.settingsFlow.first()
            val dirUriString = settings.directoryUri
            val apiKey = settings.apiKey

            if (dirUriString.isNullOrBlank() || apiKey.isBlank()) {
                stopSelf()
                return
            }

            try {
                val isBaselineRun = !settings.baselineDone
                val directoryListed = pollDirectory(Uri.parse(dirUriString), apiKey, settings.model, db, isBaselineRun)
                if (isBaselineRun && directoryListed) {
                    settingsDataStore.setBaselineDone(true)
                }
            } catch (e: Exception) {
                updateNotification("Monitor error: ${e.message}")
            }

            delay(POLL_INTERVAL_MS)
        }
    }

    /**
     * Scans the directory once. During a baseline run new files are only
     * recorded, not processed. Returns false when the directory could not be
     * listed, so a pending baseline is not marked as done prematurely.
     */
    private suspend fun pollDirectory(
        treeUri: Uri,
        apiKey: String,
        model: String,
        db: AppDatabase,
        isBaselineRun: Boolean
    ): Boolean {
        val treeDoc = DocumentFile.fromTreeUri(applicationContext, treeUri) ?: return false
        val imageFiles = treeDoc.listFiles().filter { it.isFile && isImage(it) }

        val dao = db.monitoredFileDao()
        val knownFiles = dao.getAll().associateBy { it.documentUri }

        for (file in imageFiles) {
            val uriString = file.uri.toString()
            val known = knownFiles[uriString]

            if (isBaselineRun) {
                if (known == null) {
                    dao.insert(MonitoredFile(uriString, file.lastModified(), processed = true))
                }
                continue
            }

            if (known == null) {
                dao.insert(MonitoredFile(uriString, file.lastModified(), processed = false))
            } else if (known.processed || known.failedAttempts > MAX_RETRIES) {
                continue
            }

            updateNotification("Processing ${file.name}…")
            val result = OcrProcessor.processImage(applicationContext, file.uri, apiKey, model)
            result.onSuccess {
                dao.markProcessed(uriString)
                lastErrorText = null
            }.onFailure {
                dao.incrementFailedAttempts(uriString)
                lastErrorText = "Failed to process ${file.name}: ${it.message}"
            }
        }

        if (!isBaselineRun) {
            updateNotification(lastErrorText ?: "Watching for new images…")
        }
        return true
    }

    private fun isImage(doc: DocumentFile): Boolean {
        val mimeType = doc.type
        if (mimeType != null && mimeType.startsWith("image/")) return true
        val name = doc.name?.lowercase().orEmpty()
        return name.endsWith(".jpg") || name.endsWith(".jpeg") || name.endsWith(".png")
    }

    private fun buildNotification(text: String): Notification {
        val channel = android.app.NotificationChannel(
            NotificationChannels.MONITOR_CHANNEL_ID,
            "Directory Monitor",
            NotificationManager.IMPORTANCE_LOW
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)

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
        private const val POLL_INTERVAL_MS = 5000L

        /** A failed file is retried on later polls at most this many times. */
        private const val MAX_RETRIES = 3

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, DirectoryMonitorService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, DirectoryMonitorService::class.java))
        }
    }
}
