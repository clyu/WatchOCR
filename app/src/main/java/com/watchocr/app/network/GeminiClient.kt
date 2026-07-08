package com.watchocr.app.network

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
    val analysis: List<String>
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
        "Extract text from the image, translate it to Traditional Chinese, and explain any idioms or slang."

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
                    put("items", JSONObject().put("type", "string"))
                    put(
                        "description",
                        "Array of explanations for idioms or slang found in the extracted text in Traditional Chinese."
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

        var rawText: String? = null
        for (i in 0 until parts.length()) {
            val part = parts.getJSONObject(i)
            if (part.has("text") && !part.isNull("text") && !part.optBoolean("thought", false)) {
                rawText = part.getString("text")
                break
            }
        }

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
        val analysisArray = resultJson.optJSONArray("analysis")
        val analysis = mutableListOf<String>()
        if (analysisArray != null) {
            for (i in 0 until analysisArray.length()) {
                analysis.add(analysisArray.getString(i))
            }
        }

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
