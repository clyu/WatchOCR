package com.watchocr.app.network

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
 * Mirrors the request/response contract of gemini_ocr_trans.sh: a single
 * generateContent call with inline image data and a structured JSON response schema.
 */
object GeminiClient {

    private const val PROMPT =
        "Extract text from the image, translate it to Traditional Chinese, and explain any idioms or slang."

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
                        Exception("API request failed with HTTP ${response.code}: $bodyString")
                    )
                }
                parseResponse(bodyString)
            }
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
            ?: return Result.failure(Exception("No candidates in API response: $body"))
        if (candidates.length() == 0) {
            return Result.failure(Exception("Empty candidates in API response: $body"))
        }
        val parts = candidates.getJSONObject(0)
            .optJSONObject("content")
            ?.optJSONArray("parts")
            ?: return Result.failure(Exception("No content parts in API response: $body"))

        var rawText: String? = null
        for (i in 0 until parts.length()) {
            val part = parts.getJSONObject(i)
            if (part.has("text") && !part.isNull("text") && !part.optBoolean("thought", false)) {
                rawText = part.getString("text")
                break
            }
        }

        if (rawText.isNullOrEmpty()) {
            return Result.failure(
                Exception("Failed to parse valid text from API response. Check if the model blocked the request. Full response: $body")
            )
        }

        // Strip Markdown code block markers (e.g. ```json) some models mistakenly append.
        val cleanedJson = rawText.lineSequence()
            .filterNot { it.trim().startsWith("```") }
            .joinToString("\n")

        val resultJson = JSONObject(cleanedJson)
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
}
