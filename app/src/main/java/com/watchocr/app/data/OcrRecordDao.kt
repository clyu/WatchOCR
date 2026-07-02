package com.watchocr.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface OcrRecordDao {

    @Insert
    suspend fun insert(record: OcrRecord): Long

    @Query("SELECT * FROM ocr_records ORDER BY timestamp DESC")
    fun getAll(): Flow<List<OcrRecord>>

    @Query("SELECT * FROM ocr_records WHERE timestamp < :cutoffMillis")
    suspend fun getOlderThan(cutoffMillis: Long): List<OcrRecord>

    @Query("DELETE FROM ocr_records WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>)
}
