package com.watchocr.app.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "watchocr_settings")

private const val DEFAULT_MODEL = "gemini-3.1-flash-lite"

data class AppSettings(
    /** MediaStore bucket (folder) to watch, or null if none selected. */
    val bucketId: Long? = null,
    /** Display name of the watched bucket, for the settings UI. */
    val bucketName: String? = null,
    /** Absolute filesystem path of the watched bucket's directory, or null if unresolved. */
    val watchedDirPath: String? = null,
    val apiKey: String = "",
    val model: String = DEFAULT_MODEL,
    /** OCR results older than this many days are deleted automatically; 0 = keep forever. */
    val retentionDays: Int = 0
) {
    /**
     * Whether directory monitoring can run: a folder to watch and an API key
     * to process its images with are both configured. The single definition
     * of the service's start/keep-running precondition.
     */
    val canMonitor: Boolean get() = bucketId != null && apiKey.isNotBlank()
}

class SettingsDataStore(private val context: Context) {

    private object Keys {
        val BUCKET_ID = longPreferencesKey("bucket_id")
        val BUCKET_NAME = stringPreferencesKey("bucket_name")
        val WATCHED_DIR_PATH = stringPreferencesKey("watched_dir_path")
        val API_KEY = stringPreferencesKey("api_key")
        val MODEL = stringPreferencesKey("model")
        val RETENTION_DAYS = intPreferencesKey("retention_days")
    }

    val settingsFlow: Flow<AppSettings> = context.dataStore.data.map { prefs ->
        AppSettings(
            bucketId = prefs[Keys.BUCKET_ID],
            bucketName = prefs[Keys.BUCKET_NAME],
            watchedDirPath = prefs[Keys.WATCHED_DIR_PATH],
            apiKey = prefs[Keys.API_KEY] ?: "",
            // Blank counts as unset: the settings field writes every keystroke,
            // so clearing it stores "", which would produce a broken request URL.
            model = prefs[Keys.MODEL]?.takeUnless { it.isBlank() } ?: DEFAULT_MODEL,
            retentionDays = prefs[Keys.RETENTION_DAYS] ?: 0
        )
    }

    suspend fun setWatchedBucket(bucketId: Long, bucketName: String, dirPath: String) {
        context.dataStore.edit {
            it[Keys.BUCKET_ID] = bucketId
            it[Keys.BUCKET_NAME] = bucketName
            it[Keys.WATCHED_DIR_PATH] = dirPath
        }
    }

    /** Backfills the directory path for buckets selected before it was persisted. */
    suspend fun setWatchedDirPath(path: String) {
        context.dataStore.edit { it[Keys.WATCHED_DIR_PATH] = path }
    }

    suspend fun setApiKey(key: String) {
        context.dataStore.edit { it[Keys.API_KEY] = key }
    }

    suspend fun setModel(model: String) {
        context.dataStore.edit { it[Keys.MODEL] = model }
    }

    suspend fun setRetentionDays(days: Int) {
        context.dataStore.edit { it[Keys.RETENTION_DAYS] = days }
    }
}
