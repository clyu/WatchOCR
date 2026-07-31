package com.watchocr.app

/**
 * The single logcat tag the whole app logs under, so `adb logcat -s WatchOCR`
 * shows everything it has to say.
 *
 * Top-level rather than a `private const val TAG` per logging class: the two
 * that log live in different packages, and one shared tag only works as long as
 * every copy of the literal stays identical — which nothing enforces.
 */
internal const val LOG_TAG = "WatchOCR"
