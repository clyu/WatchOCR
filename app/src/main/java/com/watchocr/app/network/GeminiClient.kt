package com.watchocr.app.network

import com.watchocr.app.data.AnalysisItem
import kotlinx.coroutines.CancellationException
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
 * Mirrors the request/response contract of gemini_ocr_trans.sh: a single
 * generateContent call with inline image data and a structured JSON response schema.
 */
object GeminiClient {

    private const val PROMPT =
        "Extract text from the image, translate it to Traditional Chinese, and explain any idioms or slang. " +
            "If an idiom or slang expression contains kanji, also provide its reading as furigana (振り仮名)."

    /** Error details shown to the user (snackbar/notification) are capped at this length. */
    private const val MAX_ERROR_DETAIL_CHARS = 200

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .build()

    suspend fun ocrAndTranslate(
        apiKey: String,
        model: String,
        base64Data: String,
        mimeType: String
    ): Result<GeminiOcrResult> = withContext(Dispatchers.IO) {
        try {
            val payload = buildRequestPayload(base64Data, mimeType)
            val request = Request.Builder()
                .url("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent")
                .addHeader("x-goog-api-key", apiKey)
                .post(payload.toString().toRequestBody("application/json".toMediaType()))
                .build()

            client.newCall(request).execute().use { response ->
                val bodyString = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    return@withContext Result.failure(
                        ApiHttpException(
                            response.code,
                            "API request failed with HTTP ${response.code}: ${extractApiError(bodyString)}"
                        )
                    )
                }
                parseResponse(bodyString)
            }
        } catch (e: CancellationException) {
            throw e // cancellation must propagate, not surface as a failed OCR
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun buildRequestPayload(base64Data: String, mimeType: String): JSONObject {
        val schema = JSONObject().apply {
            put("type", "object")
            put("properties", JSONObject().apply {
                put("ocr", JSONObject().apply {
                    put("type", "string")
                    put("description", "Extracted text from the image.")
                })
                put("translation", JSONObject().apply {
                    put("type", "string")
                    put("description", "Extracted text translated into Traditional Chinese.")
                })
                put("analysis", JSONObject().apply {
                    put("type", "array")
                    put("items", JSONObject().apply {
                        put("type", "object")
                        put("properties", JSONObject().apply {
                            put("expression", JSONObject().apply {
                                put("type", "string")
                                put("description", "The idiom or slang expression as it appears in the extracted text.")
                            })
                            put("furigana", JSONObject().apply {
                                put("type", "string")
                                put(
                                    "description",
                                    "Reading of the expression as furigana (振り仮名). Only provide this when the expression contains kanji."
                                )
                            })
                            put("explanation", JSONObject().apply {
                                put("type", "string")
                                put("description", "Explanation of the expression in Traditional Chinese.")
                            })
                        })
                        put("required", JSONArray().put("expression").put("explanation"))
                    })
                    put(
                        "description",
                        "Array of idioms or slang found in the extracted text, each with an explanation in Traditional Chinese."
                    )
                })
            })
            put("required", JSONArray().put("ocr").put("translation").put("analysis"))
        }

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
                put("responseSchema", schema)
            })
        }
    }

    private fun parseResponse(body: String): Result<GeminiOcrResult> {
        val root = JSONObject(body)
        val candidates = root.optJSONArray("candidates")
        if (candidates == null || candidates.length() == 0) {
            val blockReason = root.optJSONObject("promptFeedback")?.optString("blockReason").orEmpty()
            return Result.failure(
                Exception(
                    if (blockReason.isNotEmpty()) "Request was blocked by the API (reason: $blockReason)."
                    else "API response contained no candidates."
                )
            )
        }
        val candidate = candidates.getJSONObject(0)
        val finishReason = candidate.optString("finishReason")
        val parts = candidate.optJSONObject("content")?.optJSONArray("parts")
            ?: return Result.failure(Exception(noTextMessage(finishReason)))

        val rawText = (0 until parts.length()).asSequence()
            .map { parts.getJSONObject(it) }
            .firstOrNull { it.has("text") && !it.isNull("text") && !it.optBoolean("thought", false) }
            ?.getString("text")

        if (rawText.isNullOrEmpty()) {
            return Result.failure(Exception(noTextMessage(finishReason)))
        }

        // Strip Markdown code block markers (e.g. ```json) some models mistakenly append.
        val cleanedJson = rawText.lineSequence()
            .filterNot { it.trim().startsWith("```") }
            .joinToString("\n")

        val resultJson = try {
            JSONObject(cleanedJson)
        } catch (e: Exception) {
            return Result.failure(
                Exception(
                    if (finishReason == "MAX_TOKENS") "Model response was truncated (MAX_TOKENS)."
                    else "Model returned malformed JSON: ${cleanedJson.take(MAX_ERROR_DETAIL_CHARS)}"
                )
            )
        }
        val analysis = resultJson.optJSONArray("analysis")
            ?.let { array ->
                (0 until array.length()).mapNotNull { index ->
                    val item = array.optJSONObject(index) ?: return@mapNotNull null
                    AnalysisItem(
                        expression = item.optString("expression"),
                        furigana = item.optString("furigana").takeIf { it.isNotEmpty() },
                        explanation = item.optString("explanation")
                    )
                }
            }
            .orEmpty()

        return Result.success(
            GeminiOcrResult(
                ocr = resultJson.optString("ocr"),
                translation = resultJson.optString("translation"),
                analysis = analysis
            )
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
            JSONObject(body).optJSONObject("error")?.optString("message")
        } catch (e: Exception) {
            null
        }
        return message.takeUnless { it.isNullOrBlank() } ?: body.take(MAX_ERROR_DETAIL_CHARS)
    }
}
