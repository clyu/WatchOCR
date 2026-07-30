package com.watchocr.app.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "watchocr_settings")

data class AppSettings(
    /** Display name of the watched folder, for the settings UI and notification. */
    val bucketName: String? = null,
    /** Absolute path of the directory to watch, or null if no folder is selected. */
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
    val canMonitor: Boolean get() = watchedDirPath != null && apiKey.isNotBlank()

    companion object {
        /**
         * Model used when none is configured. Public so the settings UI can
         * show it in place of a blank field, which [settingsFlow] resolves to
         * this same value — otherwise the field would contradict the model
         * requests actually use.
         */
        const val DEFAULT_MODEL = "gemini-3.5-flash-lite"
    }
}

class SettingsDataStore(private val context: Context) {

    private object Keys {
        val BUCKET_NAME = stringPreferencesKey("bucket_name")
        val WATCHED_DIR_PATH = stringPreferencesKey("watched_dir_path")
        val API_KEY = stringPreferencesKey("api_key")
        val MODEL = stringPreferencesKey("model")
        val RETENTION_DAYS = intPreferencesKey("retention_days")
    }

    val settingsFlow: Flow<AppSettings> = context.dataStore.data.map { prefs ->
        AppSettings(
            bucketName = prefs[Keys.BUCKET_NAME],
            watchedDirPath = prefs[Keys.WATCHED_DIR_PATH],
            apiKey = prefs[Keys.API_KEY] ?: "",
            // Blank counts as unset: the settings field writes every keystroke,
            // so clearing it stores "", which would produce a broken request URL.
            model = prefs[Keys.MODEL]?.takeUnless { it.isBlank() } ?: AppSettings.DEFAULT_MODEL,
            retentionDays = prefs[Keys.RETENTION_DAYS] ?: 0
        )
    }

    suspend fun setWatchedBucket(bucketName: String, dirPath: String) {
        context.dataStore.edit {
            it[Keys.BUCKET_NAME] = bucketName
            it[Keys.WATCHED_DIR_PATH] = dirPath
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
