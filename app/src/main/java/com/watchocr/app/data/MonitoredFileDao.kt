package com.watchocr.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface MonitoredFileDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(file: MonitoredFile)

    @Query("SELECT documentUri FROM monitored_files")
    suspend fun getAllUris(): List<String>

    @Query("SELECT COUNT(*) FROM monitored_files")
    suspend fun count(): Int

    @Query("DELETE FROM monitored_files")
    suspend fun clear()
}
