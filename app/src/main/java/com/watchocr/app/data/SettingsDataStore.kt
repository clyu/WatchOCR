package com.watchocr.app.data

import android.content.Context
import android.util.Log
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.watchocr.app.LOG_TAG
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

private val Context.dataStore by preferencesDataStore(
    name = "watchocr_settings",
    // A preferences file that no longer deserializes (a write torn by power
    // loss, say) otherwise throws out of every read: MainActivity's collect
    // then crashes the app on each launch, and nothing short of clearing app
    // data recovers. Starting over loses the stored settings, but they can be
    // re-entered in a way the app itself cannot — the same trade
    // AnalysisListConverter makes for a corrupt analysis column.
    //
    // A corruption handler rather than `.catch { emit(emptyPreferences()) }`
    // on [SettingsDataStore.settingsFlow]: the handler replaces the file, so
    // the next edit lands, where a catch would mask every read while leaving
    // every write to fail against the same corrupt bytes — including the key
    // the user re-enters to recover. Deliberately nothing for the other
    // IOExceptions a read can throw: presenting a transient read failure as
    // empty settings would hand the settings screen blank fields that its
    // debounced writer then persists over the stored values.
    corruptionHandler = ReplaceFileCorruptionHandler { e ->
        Log.w(LOG_TAG, "settings file unreadable, starting over from defaults", e)
        emptyPreferences()
    }
)

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

    suspend fun setWatchedBucket(bucketName: String, dirPath: String) = persist {
        it[Keys.BUCKET_NAME] = bucketName
        it[Keys.WATCHED_DIR_PATH] = dirPath
    }

    suspend fun setApiKey(key: String) = persist { it[Keys.API_KEY] = key }

    suspend fun setModel(model: String) = persist { it[Keys.MODEL] = model }

    suspend fun setRetentionDays(days: Int) = persist { it[Keys.RETENTION_DAYS] = days }

    /**
     * The one way anything in here writes, so that "once called, the value
     * lands" holds for every setting without each caller arranging it.
     *
     * NonCancellable because the callers are UI event handlers running on the
     * composition's scope, which a rotation cancels: the settings field would
     * then re-seed from a DataStore that never got the last edit, silently
     * reverting what the user just typed. The writes are single-key and
     * DataStore serializes them anyway, so nothing is held up for long.
     */
    private suspend fun persist(transform: suspend (MutablePreferences) -> Unit) {
        withContext(NonCancellable) { context.dataStore.edit(transform) }
    }
}
