package com.watchocr.app.ocr

import android.content.Context
import android.net.Uri
import android.util.Base64
import com.watchocr.app.data.AppDatabase
import com.watchocr.app.data.OcrRecord
import com.watchocr.app.network.GeminiClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

object OcrProcessor {

    /**
     * Reads the image at [uri], runs it through Gemini for OCR + translation,
     * copies the image into app-private storage, and persists an [OcrRecord].
     */
    suspend fun processImage(
        context: Context,
        uri: Uri,
        apiKey: String,
        model: String
    ): Result<OcrRecord> = withContext(Dispatchers.IO) {
        try {
            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: return@withContext Result.failure(Exception("Unable to open image: $uri"))

            val mimeType = context.contentResolver.getType(uri) ?: guessMimeType(uri)
            val base64Data = Base64.encodeToString(bytes, Base64.NO_WRAP)

            val geminiResult = GeminiClient.ocrAndTranslate(apiKey, model, base64Data, mimeType)
                .getOrElse { return@withContext Result.failure(it) }

            val imagesDir = File(context.filesDir, "images").apply { mkdirs() }
            val extension = if (mimeType.contains("png")) "png" else "jpg"
            val imageFile = File(imagesDir, "${UUID.randomUUID()}.$extension")
            imageFile.writeBytes(bytes)

            val record = OcrRecord(
                imagePath = imageFile.absolutePath,
                ocrText = geminiResult.ocr,
                translation = geminiResult.translation,
                analysis = geminiResult.analysis
            )

            val dao = AppDatabase.getInstance(context).ocrRecordDao()
            dao.insert(record)

            Result.success(record)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun guessMimeType(uri: Uri): String {
        val name = uri.lastPathSegment.orEmpty().lowercase()
        return if (name.endsWith(".png")) "image/png" else "image/jpeg"
    }
}
