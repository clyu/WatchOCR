package com.watchocr.app.service

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
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
import com.watchocr.app.LOG_TAG
import com.watchocr.app.MainActivity
import com.watchocr.app.data.HistoryCleanup
import com.watchocr.app.data.SettingsDataStore
import com.watchocr.app.network.ApiHttpException
import com.watchocr.app.ocr.OcrProcessor
import com.watchocr.app.ocr.UnreadableImageException
import com.watchocr.app.ui.describeForUser
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.SendChannel
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
 * DELETE_SELF/MOVE_SELF report the watched directory itself disappearing;
 * without them the inotify watch would die silently (even a recreated folder
 * is a new inode the dead watch does not cover) while the "Watching…"
 * notification kept claiming otherwise.
 */
class DirectoryMonitorService : Service() {

    /** What [fileObserver] reports: a newly written image, or the loss of the
     * watched directory itself (deleted, renamed or unmounted). */
    private sealed interface WatchEvent {
        data class NewFile(val file: File) : WatchEvent
        data object WatchedDirGone : WatchEvent
    }

    // The handler is built inline rather than held in a property: a property
    // declared after this one would still be null when this initializer runs.
    private val serviceScope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, e -> onCoroutineFailure(e) }
    )
    private val settingsDataStore by lazy { SettingsDataStore(applicationContext) }
    private var monitorJob: Job? = null
    private var cleanupJob: Job? = null

    private val notificationManager by lazy { getSystemService(NotificationManager::class.java) }

    /**
     * Sent when either notification is tapped. Without it `setAutoCancel` is a
     * no-op — it only fires on tap — and the alerts' "…in Settings"
     * instructions are a dead end. Reopening MainActivity also re-runs its
     * start-service effect, which is exactly the recovery the alerts ask for.
     *
     * Built once: [updateNotification] runs per processed file, and
     * PendingIntent.getActivity round-trips to the system server.
     */
    private val contentIntent: PendingIntent by lazy {
        PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).setFlags(
                // SINGLE_TOP is load-bearing: MainActivity's launchMode is
                // `standard`, so CLEAR_TOP alone would destroy and recreate an
                // already-open app, resetting the selected tab and scroll
                // position instead of just bringing it forward.
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
            ),
            PendingIntent.FLAG_IMMUTABLE
        )
    }

    /** Serializes [reconcileMonitor] so overlapping start() calls cannot race. */
    private val reconcileLock = Mutex()

    /**
     * Directory the running monitor loop watches; null when no loop is running.
     *
     * Volatile because the loop clears it on its way out while [reconcileMonitor]
     * may be reading it concurrently — that read is the whole point of clearing
     * it, and in the case it guards against there is no join between the two to
     * publish the write.
     */
    @Volatile
    private var watchingDirPath: String? = null

    /**
     * Strong reference: a GC'd FileObserver silently stops watching.
     *
     * Volatile because [monitorLoop] starts and stops it on [serviceScope] while
     * [onDestroy] stops it on the main thread, with no join or lock between the
     * two to publish either write. Without it the loop's stop could read
     * [onDestroy]'s null, skip the stop, and leave a live inotify watch feeding a
     * channel nobody reads — until the GC got around to finalizing it.
     */
    @Volatile
    private var fileObserver: FileObserver? = null

    /**
     * Id of the most recently delivered start command, for the one stop that
     * cannot name its own ([monitorLoop]'s, which outlives the start that
     * launched it). Written on the main thread, read from [serviceScope].
     */
    @Volatile
    private var latestStartId: Int = 0

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
        latestStartId = startId
        // start() is called liberally (every app open, configuration change,
        // watched-bucket change); reconcileMonitor restarts the loop only when
        // the watched directory actually changed, so a redundant start cannot
        // cancel (and lose) a file mid-processing.
        //
        // startId travels with the reconcile so its decision to stop can be
        // vetoed: every start command produces exactly one reconcile, so a
        // newer one always re-reads the settings this one is stopping over.
        serviceScope.launch { reconcileMonitor(startId) }
        if (cleanupJob?.isActive != true) {
            cleanupJob = serviceScope.launch { cleanupLoop() }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // Deliberate: swiping the app away from recents is the user's way of
    // stopping monitoring; opening the app again resumes it. Unconditional
    // stopSelf() on purpose — unlike the settings-driven stops below, this is
    // a direct user instruction that no pending start command should override.
    override fun onTaskRemoved(rootIntent: Intent?) {
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        // Before the cancel, not after: cancelling only schedules [monitorLoop]'s
        // finally to run, so waiting on it to stop the watch would leave one live
        // for as long as the loop takes to resume.
        stopObserver()
        serviceScope.cancel()
        super.onDestroy()
    }

    /**
     * Reads the configured directory and (re)starts [monitorLoop] for it,
     * leaving an already-running loop untouched when the directory is
     * unchanged. Joining the old loop before starting the new one keeps its
     * cleanup from stopping the new loop's observer.
     *
     * [startId] is the start command this reconcile answers; both stops below
     * are scoped to it, so settings read here can never stop a service that a
     * later start command has already been asked to reconsider.
     */
    private suspend fun reconcileMonitor(startId: Int): Unit = reconcileLock.withLock {
        val settings = settingsDataStore.settingsFlow.first()

        if (!settings.canMonitor) {
            stopSelf(startId)
            return
        }
        // canMonitor already established that the path is set, so the null branch
        // is only here to narrow the type. isDirectory is the real check: the
        // folder can be deleted, renamed or unmounted between two reconciles.
        val dirPath = settings.watchedDirPath
        if (dirPath == null || !File(dirPath).isDirectory) {
            stopWithAlert("Watched folder unavailable — re-select it in Settings.", startId)
            return
        }
        // Monitoring is viable again, so an alert left behind by an earlier
        // reconcile ("folder unavailable") or by monitorLoop ("API key is not
        // set", "folder is no longer available") no longer describes reality. Above the early return on purpose:
        // a stop that was vetoed by a newer start command leaves the alert up
        // with the loop still running, and that case must clear it too.
        // Deliberately not in onDestroy — stopWithAlert posts and then stops,
        // so cancelling there would erase the alert it just put up.
        notificationManager.cancel(ALERT_NOTIFICATION_ID)

        if (monitorJob?.isActive == true && dirPath == watchingDirPath) return

        monitorJob?.cancelAndJoin()
        watchingDirPath = dirPath
        monitorJob = serviceScope.launch { monitorLoop(dirPath, settings.bucketName) }
    }

    private suspend fun monitorLoop(dirPath: String, bucketName: String?) {
        val idleText = "Watching ${bucketName ?: dirPath} for new images…"

        // Last processing failure, shown in the notification in place of
        // [idleText] until a file succeeds. Local rather than a field: it names
        // a file in *this* folder, so a loop started for a different one must
        // not open with it — and scoping it to the loop is what guarantees
        // that, instead of reconcileMonitor having to remember to clear it.
        var lastErrorText: String? = null

        // Some camera apps close a file, then reopen it to write EXIF and
        // close again — two CLOSE_WRITE events for one image. Entries age out by
        // timestamp on every event, not by insertion order, so nothing here
        // needs an ordered map.
        val recentlyDone = HashMap<String, Long>()

        // Owned by this loop, created fresh for each one: events a previous
        // folder's observer queued die with its channel, so a folder switch
        // never needs to drain them to keep the old folder's files out of the
        // new loop. UNLIMITED so bursts are not dropped.
        val watchEvents = Channel<WatchEvent>(Channel.UNLIMITED)

        // Inside the try so the finally owns the observer from the moment it
        // exists: an exception on the way into the loop must not leave a live
        // observer feeding a channel nobody reads.
        try {
            startObserver(dirPath, watchEvents)
            updateNotification(idleText)
            for (event in watchEvents) {
                val file = when (event) {
                    WatchEvent.WatchedDirGone -> {
                        Log.w(LOG_TAG, "watched directory gone, stopping monitor")
                        // latestStartId for the same reason as the API-key stop
                        // below; reconcile clears this alert once the folder is
                        // viable again (e.g. re-selected, or recreated by the
                        // camera app and the user reopened WatchOCR).
                        stopWithAlert(
                            "Watched folder is no longer available — monitoring stopped. Re-select it in Settings.",
                            latestStartId
                        )
                        return
                    }
                    is WatchEvent.NewFile -> event.file
                }
                val now = SystemClock.elapsedRealtime()
                recentlyDone.entries.removeAll { now - it.value > DEDUP_WINDOW_MS }
                if (recentlyDone.containsKey(file.path)) {
                    Log.d(LOG_TAG, "duplicate event for ${file.name}, skipping")
                    continue
                }
                if (!file.isFile) continue // renamed/deleted since the event
                if (file.length() == 0L) {
                    // Creation handshake of a two-pass writer; the write that
                    // fills the file triggers its own CLOSE_WRITE.
                    Log.d(LOG_TAG, "${file.name} is empty, awaiting next write")
                    continue
                }

                val current = settingsDataStore.settingsFlow.first()
                if (current.apiKey.isBlank()) {
                    // Key cleared after startup: every upload would fail, so
                    // stop instead of burning retries; MainActivity restarts
                    // the service once a key is set again.
                    Log.w(LOG_TAG, "API key cleared, stopping monitor")
                    // latestStartId, not the one that launched this loop: that
                    // one is long superseded (every app open starts the service
                    // again), so stopping against it would never take effect.
                    stopWithAlert(
                        "Gemini API key is not set — monitoring stopped. Set it in Settings to resume.",
                        latestStartId
                    )
                    return
                }
                Log.i(LOG_TAG, "processing ${file.name}")
                updateNotification("Processing ${file.name}…")

                val failure = OcrProcessor.withActiveJob {
                    processWithRetry(file, current.apiKey, current.model)
                }.exceptionOrNull()
                if (failure == null) {
                    Log.i(LOG_TAG, "processed ${file.name}")
                    lastErrorText = null
                } else {
                    Log.w(LOG_TAG, "failed ${file.name}: ${failure.message}")
                    lastErrorText = "Failed to process ${file.name}: ${failure.describeForUser()}"
                }
                // Settings the API will not accept are not this file's problem:
                // they fail every image the same way, so carrying on would spend
                // one pointless request per screenshot for as long as the folder
                // keeps filling — while the notification still reads "Watching…"
                // and nothing tells the user why History stays empty. Stop the
                // way the blank-key branch above does, and with latestStartId for
                // the same reason it uses one.
                //
                // Reopening WatchOCR without fixing them does restart the loop —
                // canMonitor only asks whether a key is set, not whether the key
                // or the model works — and it stops again on the next image. That
                // is the same shape as the folder-gone alert, and it costs one
                // request per app open rather than one per image.
                val settingsAlert = settingsAlertFor(failure)
                if (settingsAlert != null) {
                    Log.w(LOG_TAG, "unusable API settings, stopping monitor")
                    stopWithAlert(settingsAlert, latestStartId)
                    return
                }
                // Hold back only the outcomes that settle these bytes for good,
                // so a duplicate event for the same path is dropped rather than
                // re-run; [isSettled] spells out which those are.
                if (isSettled(failure)) {
                    recentlyDone[file.path] = SystemClock.elapsedRealtime()
                }
                updateNotification(lastErrorText ?: idleText)
            }
        } finally {
            // Makes this loop's exit visible to reconcileMonitor, whose
            // "already watching that folder" check would otherwise be satisfied
            // by a loop on its way out: a coroutine running this block after a
            // plain `return` (the stopWithAlert paths above) is still Completing
            // rather than completed, so its Job reports isActive == true. A
            // start command delivered in that window would cancel the alert,
            // see the stale path, return early, and leave nothing watching
            // behind a notification still claiming otherwise. Cleared here
            // rather than at each `return` so cancellation is covered too.
            watchingDirPath = null
            stopObserver()
        }
    }

    @Suppress("DEPRECATION") // String ctor: the File overload is API 29+, minSdk is 26
    private fun startObserver(dirPath: String, events: SendChannel<WatchEvent>) {
        // No previous observer to stop: reconcileMonitor joins the old
        // monitorLoop before launching a new one, and that loop's finally
        // always stops and clears the observer it started.
        fileObserver = object : FileObserver(
            dirPath,
            CLOSE_WRITE or MOVED_TO or DELETE_SELF or MOVE_SELF
        ) {
            // Called on FileObserver's own thread: filter cheaply, hand off.
            override fun onEvent(event: Int, path: String?) {
                // Checked by bit rather than left to mask filtering: the self
                // events carry a null path, and the kernel also delivers
                // unrequested null-path events (IN_IGNORED after the watch
                // dies) that must not be mistaken for them. IN_UNMOUNT is one
                // of those unrequested events, and the storage going away
                // kills the watch just like a delete, so it is treated the
                // same.
                if (event and (DELETE_SELF or MOVE_SELF or IN_UNMOUNT) != 0) {
                    events.trySend(WatchEvent.WatchedDirGone)
                    return
                }
                if (path == null || path.startsWith(".")) return // .pending-*, .trashed-*
                if (OcrProcessor.mimeForFileName(path) == null) return
                events.trySend(WatchEvent.NewFile(File(dirPath, path)))
            }
        }.also { it.startWatching() }
    }

    /**
     * Counterpart to [startObserver], called from [monitorLoop]'s finally (on
     * [serviceScope]) and from [onDestroy] (on the main thread) — whichever gets
     * there first, with the other then finding null and doing nothing.
     *
     * The read and the write are not one atomic step, and do not need to be: the
     * worst a lost race costs is a second stopWatching() on an observer that has
     * already been stopped, which FileObserver guards against internally. What
     * the two threads do need is to see each other's writes at all, which is what
     * [fileObserver]'s @Volatile is for.
     */
    private fun stopObserver() {
        fileObserver?.stopWatching()
        fileObserver = null
    }

    // Wrapped in OcrProcessor.withActiveJob by the caller so the progress
    // indicator stays on across the backoff delays between attempts, which
    // processImage's own counting does not cover.
    private suspend fun processWithRetry(file: File, apiKey: String, model: String): Result<Unit> {
        val uri = Uri.fromFile(file)
        var attempt = 1
        while (true) {
            val result = OcrProcessor.processImage(applicationContext, uri, apiKey, model)
            if (result.isSuccess || attempt >= MAX_ATTEMPTS || !isRetryable(result.exceptionOrNull())) {
                return result
            }
            Log.w(LOG_TAG, "retrying ${file.name} (attempt ${attempt + 1}): ${result.exceptionOrNull()?.message}")
            delay(RETRY_DELAY_MS)
            attempt++
        }
    }

    /**
     * Whether another attempt at the same file could plausibly do better. 4xx
     * responses are permanent (invalid API key: 400/403, unprocessable image:
     * 400, unknown model: 404) — retrying them is pointless — except 408
     * (request timeout) and 429 (rate limited), which are transient like the
     * 5xx range. A file that gave up no bytes is permanent too — already gone
     * (FileNotFoundException), or read as nothing ([UnreadableImageException]):
     * reading the same path again immediately reads the same nothing.
     *
     * Both are named rather than left to the `else` branch, so that giving
     * either one an IOException supertype later cannot quietly move it into the
     * retryable arm above.
     *
     * Answers that question only, not "is this file finished with" — see
     * [isSettled], which the two disagree on.
     *
     * [e] is nullable because both callers take theirs from
     * `Result.exceptionOrNull()`; a success is not retryable.
     */
    private fun isRetryable(e: Throwable?): Boolean = when (e) {
        is FileNotFoundException, is UnreadableImageException -> false
        is IOException -> true
        is ApiHttpException -> e.code == 408 || e.code == 429 || e.code in 500..599
        else -> false
    }

    /**
     * The alert to stop monitoring over when [failure] (null for a success) is
     * one the user fixes in Settings and every later image would hit
     * identically; null when it is not one of those.
     *
     * A function returning the text rather than a predicate the caller then
     * writes a message for: the two cases differ in nothing but what they tell
     * the user, and splitting them across a predicate and a `when` at the call
     * site would put the only thing that differs somewhere other than the thing
     * that decides. Adding a third case is then one branch here.
     *
     * Deliberately narrow. A plain 400 stays out of it: the API answers an image
     * it cannot process with the same code as a request it cannot parse, so
     * stopping over one would let a single bad screenshot take monitoring down.
     */
    private fun settingsAlertFor(failure: Throwable?): String? {
        if (failure !is ApiHttpException) return null
        return when {
            failure.isCredentialFailure ->
                "Gemini rejected the API key (${failure.describeForUser()}) — " +
                    "monitoring stopped. Check it in Settings."
            failure.isModelUnavailable ->
                "Gemini rejected the model name (${failure.describeForUser()}) — " +
                    "monitoring stopped. Check it in Settings."
            else -> null
        }
    }

    /**
     * Whether [failure] (null for a success) settles this path's bytes for good,
     * so a duplicate event for it inside [DEDUP_WINDOW_MS] announces nothing new
     * — the case the dedup window exists for, some camera apps closing a file
     * and reopening it to write EXIF.
     *
     * A success settles it, and so do the permanent failures [isRetryable] rules
     * out and [monitorLoop] does not stop the whole loop over: an image the API
     * cannot read, a response that came back unusable. A duplicate event carries
     * the same bytes, so re-running one of those buys a second identical
     * rejection at the price of another full retry cycle, and the 429 case makes
     * that actively counterproductive. Transient failures settle nothing — there
     * a duplicate event is a free extra attempt once the network recovers.
     *
     * The two failures that read no bytes at all — FileNotFoundException and
     * [UnreadableImageException] — settle nothing either, and are the reason
     * this is not simply `!isRetryable`: there is no verdict about any bytes to
     * reuse. They are permanent only in that re-reading a path that is right now
     * missing or empty is pointless; a path that fills — a `.trashed-`/
     * `.pending-` rename round-trip, a writer that unlinked and recreated the
     * file, the second pass of one that creates it empty — fills with different
     * bytes, and the event announcing them has to get through.
     *
     * [monitorLoop]'s length check drops most of a two-pass writer's creation
     * events before they ever reach here, but it cannot drop all of them: the
     * check and the read that follows it are separate steps, so a file truncated
     * and rewritten in between passes the check and then reads as nothing. That
     * is the case [UnreadableImageException] is here to keep unsettled — held
     * back, the event carrying the real bytes would be dropped as a duplicate
     * and the image never processed.
     */
    private fun isSettled(failure: Throwable?): Boolean =
        !isRetryable(failure) &&
            failure !is FileNotFoundException &&
            failure !is UnreadableImageException

    /**
     * Long-running service: enforces the history retention setting periodically,
     * so it applies even when the app UI is never opened.
     */
    private suspend fun cleanupLoop() {
        while (currentCoroutineContext().isActive) {
            // Quietly: a failed sweep must not reach [onCoroutineFailure] and
            // escalate into stopping monitoring. The next hourly pass retries.
            //
            // The settings read is deliberately left outside that protection,
            // matching every other DataStore read in this file (reconcileMonitor's
            // and monitorLoop's are both uncaught): settings this service cannot
            // read are fatal to monitoring itself, not just to housekeeping.
            HistoryCleanup.deleteOlderThanQuietly(
                applicationContext,
                settingsDataStore.settingsFlow.first().retentionDays
            )
            delay(CLEANUP_INTERVAL_MS)
        }
    }

    private fun createNotificationChannels() {
        notificationManager.createNotificationChannel(
            android.app.NotificationChannel(
                MONITOR_CHANNEL_ID,
                "Directory Monitor",
                NotificationManager.IMPORTANCE_LOW
            )
        )
        // Alerts ask the user to act (monitoring stopped, folder gone), so they
        // must be audible/heads-up — unlike the silent ongoing status channel.
        notificationManager.createNotificationChannel(
            android.app.NotificationChannel(
                ALERT_CHANNEL_ID,
                "Monitoring Alerts",
                NotificationManager.IMPORTANCE_DEFAULT
            )
        )
    }

    /**
     * [alert] selects the whole "the user has to act" presentation at once —
     * audible channel, dismissible rather than ongoing, cleared on tap. The
     * three always moved together, so they are one parameter.
     */
    private fun buildNotification(text: String, alert: Boolean = false): Notification {
        return NotificationCompat.Builder(this, if (alert) ALERT_CHANNEL_ID else MONITOR_CHANNEL_ID)
            .setContentTitle("WatchOCR")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentIntent(contentIntent)
            .setOngoing(!alert)
            .setAutoCancel(alert)
            // Re-posting an unchanged alert (every app open while the folder is
            // still missing) must not make a sound again; one posted after the
            // user dismissed the previous alert still does.
            .setOnlyAlertOnce(alert)
            .build()
    }

    private fun updateNotification(text: String) {
        notificationManager.notify(NOTIFICATION_ID, buildNotification(text))
    }

    /**
     * Stops the service, leaving [text] behind as a dismissible notification
     * that outlives it. Not updateNotification: stopSelf() takes the
     * foreground notification down with the service, so a message the user
     * has to act on must go out as a standalone one.
     *
     * [startId] scopes the stop to the start command that prompted it: if a
     * newer one has since been delivered, the system ignores this and lets that
     * one's [reconcileMonitor] decide. The alert stands either way — it is
     * dismissible, and the condition it reports was real when it was posted;
     * a reconcile that finds monitoring viable again cancels it.
     */
    private fun stopWithAlert(text: String, startId: Int) {
        notificationManager.notify(ALERT_NOTIFICATION_ID, buildNotification(text, alert = true))
        stopSelf(startId)
    }

    /**
     * Last line of defence for [serviceScope]'s children. SupervisorJob keeps
     * one child's failure from cancelling its siblings but does not swallow it:
     * with no handler installed it reaches the thread's default handler and
     * takes the process down. [reconcileMonitor] and [monitorLoop] both go
     * through DataStore, MediaStore and the notification manager outside any
     * try/catch, none of which are guaranteed not to throw.
     *
     * Stopping is the honest response rather than logging and carrying on:
     * whatever failed, nothing is watching the folder any more, and the
     * "Watching…" notification would keep claiming otherwise. Cancellation is
     * never delivered to a handler, so an ordinary shutdown or a folder switch
     * cannot trip this.
     */
    private fun onCoroutineFailure(e: Throwable) {
        Log.e(LOG_TAG, "monitor coroutine failed", e)
        // latestStartId for the same reason monitorLoop's stop uses it: the
        // coroutine that failed may long outlive the start that launched it.
        stopWithAlert(
            "Monitoring stopped unexpectedly (${e.describeForUser()}). Reopen WatchOCR to resume.",
            latestStartId
        )
    }

    companion object {
        /**
         * inotify's IN_UNMOUNT bit. The kernel delivers it to every watcher
         * (no need to request it in the mask) and [FileObserver] passes it on
         * to onEvent, but unlike DELETE_SELF/MOVE_SELF it has no FileObserver
         * constant.
         */
        private const val IN_UNMOUNT = 0x2000

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

        /**
         * Counterpart to [start] for when monitoring is no longer possible
         * (see [com.watchocr.app.data.AppSettings.canMonitor]). Without it the
         * service would keep its "Watching…" notification up until the next
         * image arrived and [monitorLoop] noticed the missing key. A no-op when
         * the service is not running.
         */
        fun stop(context: Context) {
            context.stopService(Intent(context, DirectoryMonitorService::class.java))
        }
    }
}
