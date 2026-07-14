package com.watchocr.app.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import org.json.JSONArray
import org.json.JSONObject

/**
 * One explained idiom/slang expression. [furigana] is only present when
 * [expression] contains kanji. [expression] is empty for records saved
 * before analysis items were structured; only [explanation] is set then.
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
            expression = json.optString("expression"),
            furigana = json.optString("furigana").takeIf { it.isNotEmpty() },
            explanation = json.optString("explanation")
        )
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

    @TypeConverter
    fun toList(value: String): List<AnalysisItem> {
        if (value.isBlank()) return emptyList()
        val array = JSONArray(value)
        return (0 until array.length()).map { index ->
            when (val entry = array.get(index)) {
                is JSONObject -> AnalysisItem.fromJson(entry)
                // Rows written before analysis items were structured hold plain strings.
                else -> AnalysisItem(expression = "", furigana = null, explanation = entry.toString())
            }
        }
    }
}
