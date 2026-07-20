package com.watchocr.app.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.util.Base64
import com.watchocr.app.data.AppDatabase
import com.watchocr.app.data.OcrRecord
import com.watchocr.app.network.GeminiClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID

object OcrProcessor {

    /**
     * Lowercase filename extension -> MIME type for the image formats the app
     * accepts. Reached only through [mimeForFileName], which is also the
     * single source of truth for which files the directory monitor picks up.
     */
    private val MIME_BY_EXTENSION: Map<String, String> = mapOf(
        "jpg" to "image/jpeg",
        "jpeg" to "image/jpeg",
        "png" to "image/png",
        "webp" to "image/webp",
        "gif" to "image/gif",
        "bmp" to "image/bmp",
        "heic" to "image/heic",
        "heif" to "image/heif",
        "avif" to "image/avif"
    )

    /**
     * Image MIME types the Gemini API accepts as inline data
     * (https://ai.google.dev/gemini-api/docs/image-understanding). Accepted
     * formats outside this set (BMP/GIF/AVIF) are re-encoded as JPEG first.
     */
    private val API_SUPPORTED_MIME_TYPES =
        setOf("image/jpeg", "image/png", "image/webp", "image/heic", "image/heif")

    /** Images above these limits are downscaled/re-encoded before upload. */
    private const val MAX_DIMENSION = 1536
    private const val MAX_UPLOAD_BYTES = 4 * 1024 * 1024
    private const val JPEG_QUALITY = 85

    private val _activeJobs = MutableStateFlow(0)

    /**
     * Number of OCR requests currently in flight, from either the manual
     * import flow or [com.watchocr.app.service.DirectoryMonitorService].
     * The UI shows a progress indicator while this is above zero.
     */
    val activeJobs: StateFlow<Int> = _activeJobs.asStateFlow()

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
        _activeJobs.update { it + 1 }
        try {
            val rawBytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: return@withContext Result.failure(Exception("Unable to open image: $uri"))
            if (rawBytes.isEmpty()) {
                return@withContext Result.failure(Exception("Image is empty: $uri"))
            }
            val rawMime = context.contentResolver.getType(uri) ?: guessMimeType(uri)

            val (bytes, mimeType) = prepareForUpload(rawBytes, rawMime)
            val base64Data = Base64.encodeToString(bytes, Base64.NO_WRAP)

            // Throws on failure; the catch below turns that into this Result.
            val geminiResult = GeminiClient.ocrAndTranslate(apiKey, model, base64Data, mimeType)

            val imagesDir = File(context.filesDir, "images").apply { mkdirs() }
            val extension = extensionForMime(mimeType)
            val imageFile = File(imagesDir, "${UUID.randomUUID()}.$extension")
            imageFile.writeBytes(bytes)

            val record = OcrRecord(
                imagePath = imageFile.absolutePath,
                ocrText = geminiResult.ocr,
                translation = geminiResult.translation,
                analysis = geminiResult.analysis
            )

            val id = try {
                AppDatabase.getInstance(context).ocrRecordDao().insert(record)
            } catch (e: Exception) {
                // The record never made it into the database, so nothing would
                // ever clean up the image copy — remove it here.
                imageFile.delete()
                throw e
            }

            // insert() returns the generated rowid; carrying it back keeps the
            // returned record from advertising the unsaved placeholder id 0.
            Result.success(record.copy(id = id))
        } catch (e: CancellationException) {
            throw e // cancellation must propagate, not surface as a failed OCR
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            _activeJobs.update { it - 1 }
        }
    }

    /**
     * Keeps small images in API-supported formats untouched, but re-encodes as
     * JPEG anything oversized (decoding with a power-of-two sample size to
     * bound peak memory) or in a format the API rejects (BMP/GIF/AVIF). The
     * request uses MEDIA_RESOLUTION_LOW, so the extra resolution would be
     * discarded server-side anyway; downscaling just avoids OOM on huge photos
     * and stays under the API's inline-data size limit.
     */
    private fun prepareForUpload(bytes: ByteArray, mimeType: String): Pair<ByteArray, String> {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val maxDimension = maxOf(bounds.outWidth, bounds.outHeight)
        // Not decodable locally (e.g. AVIF before Android 12): converting is
        // impossible, so send the bytes as-is as a last resort and let the API
        // report what it can't handle instead of rejecting the file outright.
        if (maxDimension <= 0) return bytes to mimeType
        if (maxDimension <= MAX_DIMENSION && bytes.size <= MAX_UPLOAD_BYTES &&
            mimeType in API_SUPPORTED_MIME_TYPES
        ) {
            return bytes to mimeType
        }

        var sampleSize = 1
        while (maxDimension / sampleSize > MAX_DIMENSION) sampleSize *= 2

        val options = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
            ?: return bytes to mimeType
        val bitmap = applyJpegExifOrientation(decoded, bytes, mimeType)
        val output = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, output)
        bitmap.recycle()
        return output.toByteArray() to "image/jpeg"
    }

    /**
     * BitmapFactory ignores a JPEG's EXIF orientation (unlike HEIF, where the
     * decoder applies the container's rotation itself), and re-encoding drops
     * the EXIF data entirely — so without this a rotated camera photo would be
     * uploaded and stored lying on its side. JPEG-only on purpose: consulting
     * EXIF for formats the decoder already orients would double-rotate them.
     */
    private fun applyJpegExifOrientation(bitmap: Bitmap, bytes: ByteArray, mimeType: String): Bitmap {
        if (mimeType != "image/jpeg") return bitmap
        val orientation = try {
            ExifInterface(bytes.inputStream())
                .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        } catch (e: Exception) {
            return bitmap // no/corrupt EXIF — treat as upright
        }
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.postRotate(90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.postRotate(270f)
                matrix.postScale(-1f, 1f)
            }
            else -> return bitmap
        }
        val oriented = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        if (oriented != bitmap) bitmap.recycle()
        return oriented
    }

    /**
     * Renders the failure of a [processImage] `Result` as a short line for a
     * snackbar or notification. Exceptions carrying no message (some IO
     * failures) fall back to their class name rather than the string "null",
     * and the result is capped so an oversized API error body cannot flood the
     * UI.
     */
    fun describeFailure(e: Throwable): String =
        e.message.orEmpty().ifBlank { e.javaClass.simpleName }
            .take(GeminiClient.MAX_ERROR_DETAIL_CHARS)

    /**
     * Extension for the stored copy of an image, from the MIME type it was
     * uploaded as. [prepareForUpload] passes small images in API-supported
     * formats through untouched, so the copy is not always JPEG or PNG —
     * naming HEIC or WebP bytes `.jpg` would leave a file whose extension
     * contradicts its content. [MIME_BY_EXTENSION] iterates in declaration
     * order, so image/jpeg resolves to "jpg" rather than "jpeg".
     */
    private fun extensionForMime(mimeType: String): String =
        MIME_BY_EXTENSION.entries.firstOrNull { it.value == mimeType }?.key ?: "jpg"

    /**
     * MIME type for [fileName] from its extension, or null when the extension
     * is not one the app accepts. The directory monitor uses the null case to
     * decide which of the files it is notified about are worth queueing.
     */
    fun mimeForFileName(fileName: String): String? =
        MIME_BY_EXTENSION[fileName.substringAfterLast('.', "").lowercase()]

    private fun guessMimeType(uri: Uri): String =
        mimeForFileName(uri.lastPathSegment.orEmpty()) ?: "image/jpeg"
}
