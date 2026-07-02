package com.watchocr.app.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import com.watchocr.app.data.AppDatabase
import com.watchocr.app.data.OcrRecord
import com.watchocr.app.network.GeminiClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID

object OcrProcessor {

    /** Images above these limits are downscaled/re-encoded before upload. */
    private const val MAX_DIMENSION = 1536
    private const val MAX_UPLOAD_BYTES = 4 * 1024 * 1024
    private const val JPEG_QUALITY = 85

    /**
     * Reads the image at [uri], downscales it if oversized, runs it through
     * Gemini for OCR + translation, copies the (possibly downscaled) image
     * into app-private storage, and persists an [OcrRecord].
     */
    suspend fun processImage(
        context: Context,
        uri: Uri,
        apiKey: String,
        model: String
    ): Result<OcrRecord> = withContext(Dispatchers.IO) {
        try {
            val rawBytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: return@withContext Result.failure(Exception("Unable to open image: $uri"))
            val rawMime = context.contentResolver.getType(uri) ?: guessMimeType(uri)

            val (bytes, mimeType) = prepareForUpload(rawBytes, rawMime)
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

    /**
     * Keeps small images untouched, but decodes oversized ones with a power-of-two
     * sample size (bounding peak memory) and re-encodes them as JPEG. The request
     * uses MEDIA_RESOLUTION_LOW, so the extra resolution would be discarded
     * server-side anyway; this just avoids OOM on huge photos and stays under the
     * API's inline-data size limit.
     */
    private fun prepareForUpload(bytes: ByteArray, mimeType: String): Pair<ByteArray, String> {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val maxDimension = maxOf(bounds.outWidth, bounds.outHeight)
        if (maxDimension <= 0) return bytes to mimeType // not decodable; send as-is
        if (maxDimension <= MAX_DIMENSION && bytes.size <= MAX_UPLOAD_BYTES) return bytes to mimeType

        var sampleSize = 1
        while (maxDimension / sampleSize > MAX_DIMENSION) sampleSize *= 2

        val options = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
            ?: return bytes to mimeType
        val output = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, output)
        bitmap.recycle()
        return output.toByteArray() to "image/jpeg"
    }

    private fun guessMimeType(uri: Uri): String {
        val name = uri.lastPathSegment.orEmpty().lowercase()
        return if (name.endsWith(".png")) "image/png" else "image/jpeg"
    }
}
