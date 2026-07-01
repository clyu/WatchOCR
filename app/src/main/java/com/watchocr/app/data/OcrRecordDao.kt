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
}
