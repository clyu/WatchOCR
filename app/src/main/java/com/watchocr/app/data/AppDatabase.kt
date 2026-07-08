package com.watchocr.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [OcrRecord::class], version = 5, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun ocrRecordDao(): OcrRecordDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        // v1–v4 differed only in the monitored_files bookkeeping table, dropped
        // in v5 when new-image detection moved to FileObserver; ocr_records never
        // changed. So every legacy version upgrades the same way: drop that table.
        private val LEGACY_MIGRATIONS = (1..4).map { fromVersion ->
            object : Migration(fromVersion, 5) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL("DROP TABLE IF EXISTS monitored_files")
                }
            }
        }

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "watchocr.db"
                ).addMigrations(*LEGACY_MIGRATIONS.toTypedArray())
                    .build().also { INSTANCE = it }
            }
        }
    }
}
