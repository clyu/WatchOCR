package com.watchocr.app.data

import android.content.Context
import java.io.File
import java.util.concurrent.TimeUnit

/** Deletes OCR history records together with their stored image copies. */
object HistoryCleanup {

    /**
     * Deletes records older than [retentionDays] days. A retention of 0 (or
     * less) means "keep forever" and is a no-op.
     */
    suspend fun deleteOlderThan(context: Context, retentionDays: Int) {
        if (retentionDays <= 0) return
        deleteBefore(context, System.currentTimeMillis() - TimeUnit.DAYS.toMillis(retentionDays.toLong()))
    }

    /** Deletes all history records and their images. */
    suspend fun clearAll(context: Context) {
        deleteBefore(context, Long.MAX_VALUE)
    }

    private suspend fun deleteBefore(context: Context, cutoffMillis: Long) {
        val dao = AppDatabase.getInstance(context).ocrRecordDao()
        val expired = dao.getOlderThan(cutoffMillis)
        if (expired.isEmpty()) return
        expired.forEach { File(it.imagePath).delete() }
        // Chunked to stay under SQLite's bound-variable limit.
        expired.map { it.id }.chunked(500).forEach { dao.deleteByIds(it) }
    }
}
