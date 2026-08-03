package com.watchocr.app.data

import android.util.Log
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import com.watchocr.app.LOG_TAG
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * Value of [name] as a string, or null when the key is missing, explicitly
 * null, or empty. Not `optString`: that funnels through `JSONObject.NULL`'s
 * `toString()`, so an explicit `"key": null` comes back as the four-character
 * string "null" rather than the fallback — which then reads as real content
 * everywhere downstream.
 */
internal fun JSONObject.optStringOrNull(name: String): String? =
    if (isNull(name)) null else optString(name).takeIf { it.isNotEmpty() }

/**
 * One explained idiom/slang expression. [furigana] is only present when
 * [expression] contains kanji.
 */
data class AnalysisItem(
    val expression: String,
    val furigana: String?,
    val explanation: String
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("expression", expression)
        furigana?.let { put("furigana", it) }
        put("explanation", explanation)
    }

    companion object {
        /**
         * Parses the `{expression, furigana?, explanation}` object shape shared
         * by the Gemini response schema and the Room analysis column.
         */
        fun fromJson(json: JSONObject): AnalysisItem = AnalysisItem(
            expression = json.optStringOrNull("expression").orEmpty(),
            furigana = json.optStringOrNull("furigana"),
            explanation = json.optStringOrNull("explanation").orEmpty()
        )

        /**
         * Parses an array of the [fromJson] shape — the form the analysis takes
         * both in the Gemini response and in the Room column it is stored as.
         *
         * optJSONObject, not get: an entry that is not an object at all is
         * dropped rather than throwing. In a model response that leaves one
         * explanation missing instead of failing the whole OCR; in a stored
         * column it keeps a single bad row from crashing the History query it
         * is being read for.
         */
        fun listFromJson(array: JSONArray): List<AnalysisItem> =
            (0 until array.length()).mapNotNull { index ->
                array.optJSONObject(index)?.let(::fromJson)
            }
    }
}

@Entity(tableName = "ocr_records")
@TypeConverters(AnalysisListConverter::class)
data class OcrRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val imagePath: String,
    val ocrText: String,
    val translation: String,
    val analysis: List<AnalysisItem>,
    val timestamp: Long = System.currentTimeMillis()
)

class AnalysisListConverter {
    @TypeConverter
    fun fromList(list: List<AnalysisItem>): String {
        val array = JSONArray()
        list.forEach { array.put(it.toJson()) }
        return array.toString()
    }

    /**
     * A column that is not a JSON array at all reads as no analysis rather than
     * throwing. Room calls this from inside the History query, so an exception
     * here does not cost one record its analysis — it propagates out of the Flow
     * and takes the whole screen down on every read, leaving nothing short of
     * clearing app data to recover from. Degrading to an empty list costs one
     * card its analysis section and leaves its text and translation intact.
     *
     * The counterpart to [AnalysisItem.listFromJson]'s own tolerance, which
     * covers the entries of a well-formed array but not the array itself.
     */
    @TypeConverter
    fun toList(value: String): List<AnalysisItem> {
        if (value.isBlank()) return emptyList()
        return try {
            AnalysisItem.listFromJson(JSONArray(value))
        } catch (e: JSONException) {
            Log.w(LOG_TAG, "unparseable analysis column, dropping", e)
            emptyList()
        }
    }
}
