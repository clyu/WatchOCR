package com.watchocr.app.network

import com.watchocr.app.data.AnalysisItem
import com.watchocr.app.data.optStringOrNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class GeminiOcrResult(
    val ocr: String,
    val translation: String,
    val analysis: List<AnalysisItem>
)

/**
 * A non-2xx API response, with the HTTP status [code] so callers can tell
 * permanent failures (4xx: invalid key, unprocessable image) from transient
 * ones worth retrying (429, 5xx).
 */
class ApiHttpException(val code: Int, message: String) : Exception(message)

/**
 * Client for the Gemini API: a single generateContent call with inline image
 * data and a structured JSON response schema.
 */
object GeminiClient {

    private const val PROMPT =
        "Extract text from the image, translate it to Traditional Chinese, and explain any idioms or slang. " +
            "If an idiom or slang expression contains kanji, also provide its reading as furigana (振り仮名)."

    /** Error details shown to the user (snackbar/notification) are capped at this length. */
    const val MAX_ERROR_DETAIL_CHARS = 200

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .build()

    /**
     * Throws on failure — [ApiHttpException] for a non-2xx response,
     * [java.io.IOException] for network errors, plain [Exception] for an
     * unusable response body; OcrProcessor (the only caller) wraps errors
     * into its `Result`.
     */
    suspend fun ocrAndTranslate(
        apiKey: String,
        model: String,
        base64Data: String,
        mimeType: String
    ): GeminiOcrResult = withContext(Dispatchers.IO) {
        val payload = buildRequestPayload(base64Data, mimeType)
        val request = Request.Builder()
            .url("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent")
            .addHeader("x-goog-api-key", apiKey)
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()

        client.newCall(request).execute().use { response ->
            val bodyString = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw ApiHttpException(
                    response.code,
                    "API request failed with HTTP ${response.code}: ${extractApiError(bodyString)}"
                )
            }
            parseResponse(bodyString)
        }
    }

    /** Response schema for the structured JSON output; see [GeminiOcrResult]. */
    private val RESPONSE_SCHEMA = """
        {
          "type": "object",
          "properties": {
            "ocr": {
              "type": "string",
              "description": "Extracted text from the image."
            },
            "translation": {
              "type": "string",
              "description": "Extracted text translated into Traditional Chinese."
            },
            "analysis": {
              "type": "array",
              "description": "Array of idioms or slang found in the extracted text, each with an explanation in Traditional Chinese.",
              "items": {
                "type": "object",
                "properties": {
                  "expression": {
                    "type": "string",
                    "description": "The idiom or slang expression as it appears in the extracted text."
                  },
                  "furigana": {
                    "type": "string",
                    "description": "Reading of the expression as furigana (振り仮名). Only provide this when the expression contains kanji."
                  },
                  "explanation": {
                    "type": "string",
                    "description": "Explanation of the expression in Traditional Chinese."
                  }
                },
                "required": ["expression", "explanation"]
              }
            }
          },
          "required": ["ocr", "translation", "analysis"]
        }
    """.trimIndent()

    private fun buildRequestPayload(base64Data: String, mimeType: String): JSONObject {
        val parts = JSONArray()
            .put(JSONObject().put("text", PROMPT))
            .put(
                JSONObject().apply {
                    put("inlineData", JSONObject().apply {
                        put("mimeType", mimeType)
                        put("data", base64Data)
                    })
                    put("mediaResolution", JSONObject().put("level", "MEDIA_RESOLUTION_LOW"))
                }
            )

        return JSONObject().apply {
            put("contents", JSONArray().put(JSONObject().put("parts", parts)))
            put("generationConfig", JSONObject().apply {
                put("responseMimeType", "application/json")
                // Parsed per request: JSONObject is mutable, so a shared
                // instance embedded in payloads would be easy to corrupt.
                put("responseSchema", JSONObject(RESPONSE_SCHEMA))
            })
        }
    }

    private fun parseResponse(body: String): GeminiOcrResult {
        val root = JSONObject(body)
        val candidates = root.optJSONArray("candidates")
        if (candidates == null || candidates.length() == 0) {
            val blockReason = root.optJSONObject("promptFeedback")?.optStringOrNull("blockReason").orEmpty()
            throw Exception(
                if (blockReason.isNotEmpty()) "Request was blocked by the API (reason: $blockReason)."
                else "API response contained no candidates."
            )
        }
        val candidate = candidates.getJSONObject(0)
        val finishReason = candidate.optStringOrNull("finishReason").orEmpty()
        val parts = candidate.optJSONObject("content")?.optJSONArray("parts")
            ?: throw Exception(noTextMessage(finishReason))

        val rawText = (0 until parts.length()).asSequence()
            .map { parts.getJSONObject(it) }
            .firstOrNull { it.has("text") && !it.isNull("text") && !it.optBoolean("thought", false) }
            ?.getString("text")

        if (rawText.isNullOrEmpty()) {
            throw Exception(noTextMessage(finishReason))
        }

        // The request forces structured output (responseMimeType + responseSchema),
        // so the text part is plain JSON — no Markdown fences to strip.
        val resultJson = try {
            JSONObject(rawText)
        } catch (e: Exception) {
            throw Exception(
                if (finishReason == "MAX_TOKENS") "Model response was truncated (MAX_TOKENS)."
                else "Model returned malformed JSON: ${rawText.take(MAX_ERROR_DETAIL_CHARS)}"
            )
        }
        val analysis = resultJson.optJSONArray("analysis")
            ?.let { array ->
                (0 until array.length()).mapNotNull { index ->
                    array.optJSONObject(index)?.let(AnalysisItem::fromJson)
                }
            }
            .orEmpty()

        return GeminiOcrResult(
            ocr = resultJson.optStringOrNull("ocr").orEmpty(),
            translation = resultJson.optStringOrNull("translation").orEmpty(),
            analysis = analysis
        )
    }

    private fun noTextMessage(finishReason: String): String =
        if (finishReason.isNotEmpty() && finishReason != "STOP") {
            "API returned no text (finishReason: $finishReason)."
        } else {
            "API returned no text."
        }

    /** Pulls the human-readable `error.message` out of an API error body, if present. */
    private fun extractApiError(body: String): String {
        val message = try {
            JSONObject(body).optJSONObject("error")?.optStringOrNull("message")
        } catch (e: Exception) {
            null
        }
        return message?.takeIf { it.isNotBlank() } ?: body.take(MAX_ERROR_DETAIL_CHARS)
    }
}
