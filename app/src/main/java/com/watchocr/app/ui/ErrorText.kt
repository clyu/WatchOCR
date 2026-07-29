package com.watchocr.app.ui

/** Error text shown to the user (snackbar/notification) is capped at this length. */
private const val MAX_ERROR_CHARS = 200

/**
 * Renders a failure as a short line for a snackbar or notification. Exceptions
 * carrying no message (some IO failures) fall back to their class name rather
 * than the string "null", and the result is capped so an oversized API error
 * body cannot flood the UI.
 *
 * Lives here rather than on OcrProcessor: nothing about it is OCR-specific,
 * its only job is producing text a user reads, and both callers (the monitor's
 * notification and the manual import's snackbar) are presentation code. The
 * cap is its own — [com.watchocr.app.network.GeminiClient] keeps a separate
 * one for bounding the detail it embeds in exception messages and log lines.
 */
fun Throwable.describeForUser(): String =
    message.orEmpty().ifBlank { javaClass.simpleName }.take(MAX_ERROR_CHARS)
