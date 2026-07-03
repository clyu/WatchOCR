package com.watchocr.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [OcrRecord::class, MonitoredFile::class], version = 4, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun ocrRecordDao(): OcrRecordDao
    abstract fun monitoredFileDao(): MonitoredFileDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        // v2 adds OCR retry tracking to monitored_files. Existing rows were either
        // baselined or already handled under the old semantics, so they migrate as
        // processed with no failed attempts.
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE monitored_files ADD COLUMN processed INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE monitored_files ADD COLUMN failedAttempts INTEGER NOT NULL DEFAULT 0")
            }
        }

        // v3 adds the file size snapshot used to detect files still being written.
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE monitored_files ADD COLUMN sizeBytes INTEGER NOT NULL DEFAULT 0")
            }
        }

        // v4 drops dead columns (lastModified, sizeBytes): stability is now checked
        // inline by polling the real file size, not via cross-scan size snapshots.
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE monitored_files_new (" +
                        "documentUri TEXT NOT NULL PRIMARY KEY, " +
                        "processed INTEGER NOT NULL DEFAULT 0, " +
                        "failedAttempts INTEGER NOT NULL DEFAULT 0)"
                )
                db.execSQL(
                    "INSERT INTO monitored_files_new (documentUri, processed, failedAttempts) " +
                        "SELECT documentUri, processed, failedAttempts FROM monitored_files"
                )
                db.execSQL("DROP TABLE monitored_files")
                db.execSQL("ALTER TABLE monitored_files_new RENAME TO monitored_files")
            }
        }

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "watchocr.db"
                ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4).build().also { INSTANCE = it }
            }
        }
    }
}
