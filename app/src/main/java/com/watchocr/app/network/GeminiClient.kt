package com.watchocr.app.network

import com.watchocr.app.data.AnalysisItem
import com.watchocr.app.data.optStringOrNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resumeWithException

data class GeminiOcrResult(
    val ocr: String,
    val translation: String,
    val analysis: List<AnalysisItem>
)

/**
 * A non-2xx API response, with the HTTP status [code] so callers can tell
 * permanent failures (4xx: invalid key, unprocessable image) from transient
 * ones worth retrying (429, 5xx), and [reason] — the `google.rpc.ErrorInfo`
 * reason the body carries, where it carries one — so they can tell the
 * permanent failures apart from each other.
 */
class ApiHttpException(
    val code: Int,
    val reason: String?,
    /**
     * How long the API asked the caller to wait before trying again, in
     * milliseconds, or null when it did not say — which most responses do not.
     * A quota 429 is where it usually appears.
     *
     * Advisory, and deliberately uncapped: how long a caller is willing to sit
     * on one request is that caller's decision, not the API's. See the
     * monitor's `MAX_RETRY_DELAY_MS` for the one that acts on this.
     */
    val retryAfterMillis: Long?,
    message: String
) : Exception(message) {

    /**
     * Whether the API rejected the caller rather than this particular request,
     * so every later request would be rejected the same way and there is
     * nothing to be gained by sending one.
     *
     * The status code alone cannot answer that. The Gemini API reports a
     * malformed or revoked key as 400 INVALID_ARGUMENT — the same code an image
     * it cannot process comes back as — and only [reason] separates the two.
     * 401 and 403 need no reason, nothing about a single image being able to
     * produce them, and 403 also covers the neighbouring cases that are just as
     * permanent and just as invisible from a background service: the API not
     * enabled for the project, a key restricted to other callers, billing off.
     */
    val isCredentialFailure: Boolean
        // API_KEY_INVALID is the reason the Gemini API pairs with its 400 for a
        // key it cannot parse or no longer recognises.
        get() = code == 401 || code == 403 || reason == "API_KEY_INVALID"

    /**
     * Whether the API has no model to serve the request under the name it was
     * given — either no such model, or one that does not do generateContent.
     * Permanent for every later request in the same way [isCredentialFailure]
     * is, and separate from it only so the two can say different things to the
     * user: one points at the key, the other at the model name.
     *
     * The code alone is enough here, unlike the 400 above. The request URL is a
     * fixed base plus the configured model name, so that name is the only thing
     * about it that can fail to resolve — nothing about the image being sent
     * can produce a 404.
     */
    val isModelUnavailable: Boolean
        get() = code == 404
}

/**
 * Client for the Gemini API: a single generateContent call with inline image
 * data and a structured JSON response schema.
 */
object GeminiClient {

    private const val PROMPT =
        "Extract text from the image, translate it to Traditional Chinese, and explain any idioms or slang. " +
            "If an idiom or slang expression contains kanji, also provide its reading as furigana (振り仮名)."

    /**
     * API-supplied detail embedded in the exceptions thrown below is capped at
     * this length, so an oversized error body cannot flood a log line. What
     * finally reaches the user is capped separately and independently — see
     * [com.watchocr.app.ui.describeForUser].
     */
    private const val MAX_ERROR_DETAIL_CHARS = 200

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

        client.newCall(request).await().use { response ->
            val bodyString = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val error = parseApiError(bodyString)
                throw ApiHttpException(
                    // ErrorInfo is the only google.rpc payload carrying a
                    // `reason`, and RetryInfo the only one carrying a
                    // `retryDelay`, so neither needs matching on `@type`.
                    code = response.code,
                    reason = errorDetail(error, "reason"),
                    retryAfterMillis = retryAfterMillis(error, response),
                    message = "API request failed with HTTP ${response.code}: ${extractApiError(error, bodyString)}"
                )
            }
            parseResponse(bodyString)
        }
    }

    /**
     * Suspends over [Call.enqueue] instead of blocking on [Call.execute]:
     * execute() ignores coroutine cancellation, so a folder switch (whose
     * reconcile cancel-and-joins the monitor loop mid-upload) or a cleared
     * ViewModel would stall until the call's timeout. Cancelling the coroutine
     * cancels the call instead; a response that loses the race and arrives
     * after cancellation is closed rather than leaked, and OkHttp's own
     * "Canceled" IOException is dropped by the already-cancelled continuation.
     */
    private suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
        enqueue(object : Callback {
            override fun onResponse(call: Call, response: Response) {
                continuation.resume(response) { response.close() }
            }

            override fun onFailure(call: Call, e: IOException) {
                continuation.resumeWithException(e)
            }
        })
        continuation.invokeOnCancellation { cancel() }
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
        // optJSONObject rather than getJSONObject, for the reason spelled out
        // over the parts below: a first candidate that is not an object at all
        // would otherwise throw a raw JSONException straight into a snackbar.
        // It also folds "no candidates key", "the array is empty" and "the first
        // entry is unusable" into the one null this branch already answers.
        val candidate = root.optJSONArray("candidates")?.optJSONObject(0)
        if (candidate == null) {
            val blockReason = root.optJSONObject("promptFeedback")?.optStringOrNull("blockReason").orEmpty()
            throw Exception(
                if (blockReason.isNotEmpty()) "Request was blocked by the API (reason: $blockReason)."
                else "API response contained no usable candidate."
            )
        }
        val finishReason = candidate.optStringOrNull("finishReason").orEmpty()
        val parts = candidate.optJSONObject("content")?.optJSONArray("parts")
            ?: throw Exception(noTextMessage(finishReason))

        // The opt* accessors throughout, never the get* pair: a part that is not
        // an object at all, or whose `text` is an object or an array rather than
        // a string, is skipped like any other part without usable text, so the
        // failure surfaces as noTextMessage() below rather than as a raw
        // JSONException whose message ("Value … cannot be converted to …") would
        // end up verbatim in a snackbar.
        //
        // Selected on the text itself rather than on the presence of a `text`
        // key, so that a leading part carrying "text": "" is passed over instead
        // of being taken for the answer and failing the whole response — the part
        // that actually holds it comes after.
        val rawText = (0 until parts.length()).asSequence()
            .mapNotNull { parts.optJSONObject(it) }
            .filterNot { it.optBoolean("thought", false) }
            .mapNotNull { it.optStringOrNull("text") }
            .firstOrNull()
            ?: throw Exception(noTextMessage(finishReason))

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
            ?.let(AnalysisItem::listFromJson)
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

    /**
     * The `error` object of an API error body, or null when the body is not JSON
     * at all or carries no error object.
     *
     * Parsed once and handed to [errorDetail], [retryAfterMillis] and
     * [extractApiError] alike: they read different halves of the same object,
     * and a body large enough to be worth capping is large enough not to want
     * parsed three times.
     */
    private fun parseApiError(body: String): JSONObject? = try {
        JSONObject(body).optJSONObject("error")
    } catch (e: Exception) {
        null // not JSON at all — a proxy's HTML error page, say
    }

    /**
     * [field] from the first entry of an error's `details` array that carries
     * one — the machine-readable half of a failure, where `message` is the half
     * written for a person to read. Null when no entry does, which is the case
     * for most error bodies.
     *
     * The first match rather than a match on `@type`: `details` holds a mix of
     * google.rpc payloads distinguished by that URL, but each field read through
     * here appears in exactly one of them — `reason` only in ErrorInfo,
     * `retryDelay` only in RetryInfo — so matching the URL as well would just be
     * a second spelling of the same fact, and one more thing to keep in step.
     */
    private fun errorDetail(error: JSONObject?, field: String): String? {
        val details = error?.optJSONArray("details") ?: return null
        return (0 until details.length()).asSequence()
            .mapNotNull { details.optJSONObject(it) }
            .mapNotNull { it.optStringOrNull(field) }
            .firstOrNull()
    }

    /**
     * How long the API asked the caller to wait before retrying, in
     * milliseconds, or null when it did not say. See
     * [ApiHttpException.retryAfterMillis], which this fills.
     *
     * Two sources, because the answer arrives either way: `google.rpc.RetryInfo`
     * in the error body, which is what a quota 429 carries, and the standard
     * `Retry-After` header. The body is read first — it is the more specific of
     * the two, and the one the Gemini API actually populates.
     *
     * `retryDelay` is a protobuf Duration in its JSON form: seconds as a decimal
     * with a trailing "s" ("38s", "1.500s"). Of `Retry-After` only the
     * delta-seconds form is read; the HTTP-date form it also permits reads as no
     * hint at all, which the caller answers with its own backoff rather than
     * with a wrong number.
     *
     * A value that is absent, unparseable or not positive is all the same
     * answer — no hint — rather than an instant retry.
     */
    private fun retryAfterMillis(error: JSONObject?, response: Response): Long? {
        val fromBody = errorDetail(error, "retryDelay")
            ?.removeSuffix("s")
            ?.toDoubleOrNull()
            ?.let { (it * 1000).toLong() }
        val fromHeader = response.header("Retry-After")?.trim()?.toLongOrNull()?.times(1000)
        return (fromBody ?: fromHeader)?.takeIf { it > 0 }
    }

    /**
     * Pulls the human-readable `error.message` out of a parsed API error, falling
     * back to the raw body when there is none, and then — for the 5xx responses
     * that carry no body at all — to a fixed phrase, so the caller's
     * "HTTP 500: …" never trails off after the colon.
     *
     * The length cap applies to whichever of the two details is chosen, not
     * just to the raw-body fallback: `error.message` is API-supplied too, and
     * nothing bounds what a gateway or proxy in front of the API may put there.
     */
    private fun extractApiError(error: JSONObject?, body: String): String =
        (error?.optStringOrNull("message")?.takeIf { it.isNotBlank() } ?: body.trim())
            .take(MAX_ERROR_DETAIL_CHARS)
            .ifBlank { "no details in the response body" }
}
