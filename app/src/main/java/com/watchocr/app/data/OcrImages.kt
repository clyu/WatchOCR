package com.watchocr.app.data

import android.content.Context
import java.io.File

/**
 * The app-private copies of the images that have been through OCR — one file
 * per [OcrRecord], at the absolute path that record's `imagePath` holds.
 *
 * Exists to give that directory a single definition:
 * [com.watchocr.app.ocr.OcrProcessor] writes into it and [HistoryCleanup]
 * sweeps it, and a second spelling of the path in either place would leave the
 * sweep quietly looking somewhere nothing is stored.
 */
object OcrImages {
    /** Created on demand by the writer, so it may not exist yet. */
    fun dir(context: Context): File = File(context.filesDir, "images")
}
