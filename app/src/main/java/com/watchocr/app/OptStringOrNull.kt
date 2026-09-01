package com.watchocr.app

import org.json.JSONObject

/**
 * Value of [name] as a string, or null when the key is missing, explicitly
 * null, or empty. Not `optString`: that funnels through `JSONObject.NULL`'s
 * `toString()`, so an explicit `"key": null` comes back as the four-character
 * string "null" rather than the fallback — which then reads as real content
 * everywhere downstream.
 *
 * Top-level rather than owned by either package that reads JSON, for the same
 * reason as [LOG_TAG] and [runQuietly]: the callers are spread across packages
 * — [com.watchocr.app.data.AnalysisItem] parses the Room analysis column with
 * it and [com.watchocr.app.network.GeminiClient] the API response — and neither
 * of those is where the other should be importing a general JSON accessor from.
 */
internal fun JSONObject.optStringOrNull(name: String): String? =
    if (isNull(name)) null else optString(name).takeIf { it.isNotEmpty() }
