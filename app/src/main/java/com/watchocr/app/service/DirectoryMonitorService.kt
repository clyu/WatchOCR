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
                pollDirectory(Uri.parse(dirUriString), apiKey, settings.model, db)
            } catch (e: Exception) {
                updateNotification("Monitor error: ${e.message}")
            }

            delay(POLL_INTERVAL_MS)
        }
    }

    private suspend fun pollDirectory(treeUri: Uri, apiKey: String, model: String, db: AppDatabase) {
        val treeDoc = DocumentFile.fromTreeUri(applicationContext, treeUri) ?: return
        val imageFiles = treeDoc.listFiles().filter { it.isFile && isImage(it) }

        val dao = db.monitoredFileDao()
        val isBaselineRun = dao.count() == 0
        val knownUris = dao.getAllUris().toHashSet()

        for (file in imageFiles) {
            val uriString = file.uri.toString()
            if (uriString in knownUris) continue

            dao.insert(MonitoredFile(uriString, file.lastModified()))
            if (isBaselineRun) continue

            updateNotification("Processing ${file.name}…")
            val result = OcrProcessor.processImage(applicationContext, file.uri, apiKey, model)
            result.onFailure {
                updateNotification("Failed to process ${file.name}: ${it.message}")
            }
        }

        if (!isBaselineRun) {
            updateNotification("Watching for new images…")
        }
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

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, DirectoryMonitorService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, DirectoryMonitorService::class.java))
        }
    }
}
