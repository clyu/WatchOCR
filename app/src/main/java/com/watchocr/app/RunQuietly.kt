package com.watchocr.app

import android.util.Log
import kotlinx.coroutines.CancellationException

/**
 * Runs [block] with a failure reduced to a log line under [logMessage], and
 * reports which happened so a caller that has somewhere to say so can.
 *
 * Top-level rather than a copy per caller, for the same reason as [LOG_TAG]:
 * the callers are spread across packages, and every copy of this shape has to
 * get the same two things right — that cancellation is rethrown rather than
 * logged, and that nothing else escapes at all.
 *
 * Cancellation is not a failure. It is the caller's scope shutting down — a
 * stopped service, a leaving composition, a debounce superseded by a newer
 * keystroke — and logging it as a failure would both misreport it and leave the
 * coroutine running on inside a scope that is already cancelled.
 *
 * Why a failure must not escape is each caller's own business, and the answers
 * differ enough to be worth reading there rather than being summarised here:
 * see [com.watchocr.app.data.HistoryCleanup.deleteOlderThanQuietly] for the
 * housekeeping sweep that already has a next attempt scheduled, and the
 * settings screen for the two scopes that would each take the app down.
 */
internal suspend fun <T> runQuietly(logMessage: String, block: suspend () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(LOG_TAG, logMessage, e)
        Result.failure(e)
    }
