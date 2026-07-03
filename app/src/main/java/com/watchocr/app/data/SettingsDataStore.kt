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
    /** Images added to MediaStore before this time are ignored. */
    val watchStartMillis: Long = 0L,
    val apiKey: String = "",
    val model: String = DEFAULT_MODEL,
    /** OCR results older than this many days are deleted automatically; 0 = keep forever. */
    val retentionDays: Int = 0
)

class SettingsDataStore(private val context: Context) {

    private object Keys {
        val BUCKET_ID = longPreferencesKey("bucket_id")
        val BUCKET_NAME = stringPreferencesKey("bucket_name")
        val WATCH_START_MILLIS = longPreferencesKey("watch_start_millis")
        val API_KEY = stringPreferencesKey("api_key")
        val MODEL = stringPreferencesKey("model")
        val RETENTION_DAYS = intPreferencesKey("retention_days")
    }

    val settingsFlow: Flow<AppSettings> = context.dataStore.data.map { prefs ->
        AppSettings(
            bucketId = prefs[Keys.BUCKET_ID],
            bucketName = prefs[Keys.BUCKET_NAME],
            watchStartMillis = prefs[Keys.WATCH_START_MILLIS] ?: 0L,
            apiKey = prefs[Keys.API_KEY] ?: "",
            model = prefs[Keys.MODEL] ?: DEFAULT_MODEL,
            retentionDays = prefs[Keys.RETENTION_DAYS] ?: 0
        )
    }

    suspend fun setWatchedBucket(bucketId: Long, bucketName: String) {
        context.dataStore.edit {
            it[Keys.BUCKET_ID] = bucketId
            it[Keys.BUCKET_NAME] = bucketName
            // Watching starts now: pre-existing images in the bucket are ignored.
            it[Keys.WATCH_START_MILLIS] = System.currentTimeMillis()
        }
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
