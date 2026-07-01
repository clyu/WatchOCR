package com.watchocr.app.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "monitored_files")
data class MonitoredFile(
    @PrimaryKey val documentUri: String,
    val lastModified: Long
)
