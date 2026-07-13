package com.watchocr.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** Just what cleanup needs from an expired record, skipping the large text columns. */
data class ExpiredRecord(val id: Long, val imagePath: String)

@Dao
interface OcrRecordDao {

    @Insert
    suspend fun insert(record: OcrRecord): Long

    @Query("SELECT * FROM ocr_records ORDER BY timestamp DESC")
    fun getAll(): Flow<List<OcrRecord>>

    @Query("SELECT id, imagePath FROM ocr_records WHERE timestamp < :cutoffMillis")
    suspend fun getOlderThan(cutoffMillis: Long): List<ExpiredRecord>

    @Query("DELETE FROM ocr_records WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>)
}
