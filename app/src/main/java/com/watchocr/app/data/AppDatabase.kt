package com.watchocr.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * Version stays at 5 with no migrations registered: v1–v4 differed only in the
 * monitored_files bookkeeping table, dropped in v5 when new-image detection
 * moved to FileObserver, and no install predating v5 exists any more. The
 * number must not be lowered to match — Room reads the version stored in the
 * existing database file and refuses to open it as a downgrade.
 */
@Database(entities = [OcrRecord::class], version = 5, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun ocrRecordDao(): OcrRecordDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "watchocr.db"
                ).build().also { INSTANCE = it }
            }
        }
    }
}
