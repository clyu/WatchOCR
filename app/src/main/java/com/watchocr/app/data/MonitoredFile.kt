package com.watchocr.app.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "monitored_files")
data class MonitoredFile(
    @PrimaryKey val documentUri: String,
    /** False until the file has been successfully OCR'd. */
    @ColumnInfo(defaultValue = "0") val processed: Boolean = false,
    /** Number of failed OCR attempts so far. */
    @ColumnInfo(defaultValue = "0") val failedAttempts: Int = 0
)
