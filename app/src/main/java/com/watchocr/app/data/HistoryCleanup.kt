package com.watchocr.app.data

import android.content.Context
import com.watchocr.app.runQuietly
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

/** Deletes OCR history records together with their stored image copies. */
object HistoryCleanup {

    /**
     * How long a stored image is left alone before [clearAll]'s orphan sweep
     * will consider it.
     *
     * An image being written right now belongs to an OcrProcessor run whose row
     * does not exist yet, so deleting it would produce exactly the broken
     * thumbnail [deleteBefore] orders its two deletes to avoid — and the sweep
     * cannot tell one from an orphan by looking at the directory. Age tells them
     * apart instead: an in-flight image is seconds old, while an orphan is at
     * least as old as the interrupted sweep that stranded it, which puts it far
     * outside this window.
     */
    private val ORPHAN_GRACE_MILLIS = TimeUnit.HOURS.toMillis(1)

    /**
     * Deletes records older than [retentionDays] days. A retention of 0 (or
     * less) means "keep forever" and is a no-op.
     */
    private suspend fun deleteOlderThan(context: Context, retentionDays: Int) {
        if (retentionDays <= 0) return
        deleteBefore(context, System.currentTimeMillis() - TimeUnit.DAYS.toMillis(retentionDays.toLong()))
    }

    /**
     * [deleteOlderThan], with a failed sweep (database locked, unreadable file)
     * reduced to a log line. Enforcing retention is background housekeeping that
     * both callers repeat — the service hourly, the UI on every settings change
     * — so a failure has a next attempt already scheduled and must not escalate:
     * out of the service it would stop monitoring, out of the composition it
     * would take the app down.
     *
     * The only way in: [deleteOlderThan] is private precisely so that argument
     * cannot be sidestepped by a future caller reaching for the louder-looking
     * name.
     *
     * Cancellation [runQuietly] rethrows rather than logs, which is what this
     * caller needs: it is not a failed sweep but the caller's scope (a stopped
     * service, a leaving composition) shutting down. It still reaches here after
     * [deleteBefore]'s NonCancellable section — but only from either side of it,
     * never from within, so a cancelled sweep is always one that either did
     * nothing or ran to the end.
     */
    suspend fun deleteOlderThanQuietly(context: Context, retentionDays: Int) {
        runQuietly("history cleanup failed") { deleteOlderThan(context, retentionDays) }
    }

    /**
     * Deletes all history records and their images, then reclaims any stored
     * image no row points at any more.
     *
     * This is the one moment when sweeping the image directory is safe to
     * reason about: every row is going, so nothing found there can still be
     * referenced by one that stays. It is also the only thing that ever
     * reclaims the orphans [deleteBefore] accepts leaving behind — a file whose
     * row was deleted before the process was killed, or one delete() refused —
     * which otherwise sit in app storage for the life of the install.
     */
    suspend fun clearAll(context: Context) {
        deleteBefore(context, Long.MAX_VALUE)
        deleteOrphanImages(context)
    }

    // Main-safe: the image files are deleted off the main thread (Room's
    // suspend DAO methods already are), so callers may invoke this from UI code.
    private suspend fun deleteBefore(context: Context, cutoffMillis: Long) = withContext(Dispatchers.IO) {
        val dao = AppDatabase.getInstance(context).ocrRecordDao()
        val expired = dao.getOlderThan(cutoffMillis)
        if (expired.isEmpty()) return@withContext
        // Rows first, files second, deliberately: the two deletes are not one
        // transaction, so whichever runs first decides what a failure (database
        // locked, process killed) leaves behind. Interrupted this way around it
        // is an image file no row points at — wasted bytes nobody sees.
        // The other way around it is a row pointing at a file that is already
        // gone, which shows up as a permanently broken thumbnail in History and
        // which nothing ever repairs.
        //
        // Chunked to stay under SQLite's bound-variable limit.
        //
        // NonCancellable draws the line here rather than at the call sites: the
        // query above is safe to abandon (nothing has been deleted yet), but
        // once the first chunk is gone the sweep is half-applied, and every
        // caller runs on a scope that gets cancelled routinely — the service's
        // at onDestroy, the UI's on a rotation or a tab switch. Leaving each
        // caller to remember that is how "Clear History" ends up clearing part
        // of the history. Bounded work, so nothing waits on it for long.
        withContext(NonCancellable) {
            expired.map { it.id }.chunked(500).forEach { dao.deleteByIds(it) }
            expired.forEach { File(it.imagePath).delete() }
        }
    }

    /**
     * Deletes stored images older than [ORPHAN_GRACE_MILLIS], called only from
     * [clearAll], where the rows that could have named them are already gone.
     *
     * Not NonCancellable, unlike [deleteBefore]: there is no row/file pair to
     * keep in step here, so an interrupted sweep leaves nothing half-applied —
     * just a few orphans for the next [clearAll] to find. Worth staying
     * cancellable for, too, since what it walks is a whole directory rather than
     * one bounded batch of expired rows.
     */
    private suspend fun deleteOrphanImages(context: Context) = withContext(Dispatchers.IO) {
        val cutoffMillis = System.currentTimeMillis() - ORPHAN_GRACE_MILLIS
        val files = OcrImages.dir(context).listFiles() ?: return@withContext
        files.forEach { if (it.lastModified() < cutoffMillis) it.delete() }
    }
}
