package com.watchocr.app.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.util.Base64
import com.watchocr.app.data.AppDatabase
import com.watchocr.app.data.OcrImages
import com.watchocr.app.data.OcrRecord
import com.watchocr.app.network.GeminiClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
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
     * MIME type -> the one filename extension the stored copy of such an image
     * is named with. Written in this direction because it is the direction with
     * a single right answer per entry: several spellings may map onto image/jpeg
     * on the way in, but a file being written has to pick exactly one of them.
     */
    private val EXTENSION_BY_MIME: Map<String, String> = mapOf(
        "image/jpeg" to "jpg",
        "image/png" to "png",
        "image/webp" to "webp",
        "image/gif" to "gif",
        "image/bmp" to "bmp",
        "image/heic" to "heic",
        "image/heif" to "heif",
        "image/avif" to "avif"
    )

    /**
     * Lowercase filename extension -> MIME type for the image formats the app
     * accepts. Reached only through [mimeForFileName], which is also the
     * single source of truth for which files the directory monitor picks up.
     *
     * Inverted from [EXTENSION_BY_MIME] rather than written out again, so a
     * format added to one is never missing from the other; the literals below
     * are only the spellings that are alternative names for a format already
     * listed there, not formats of their own. Must stay declared after it —
     * these are initialized in declaration order.
     */
    private val MIME_BY_EXTENSION: Map<String, String> =
        EXTENSION_BY_MIME.entries.associate { (mime, extension) -> extension to mime } +
            mapOf("jpeg" to "image/jpeg")

    /**
     * Image MIME types the Gemini API accepts as inline data
     * (https://ai.google.dev/gemini-api/docs/image-understanding). Accepted
     * formats outside this set (BMP/GIF/AVIF) are re-encoded as JPEG first.
     */
    private val API_SUPPORTED_MIME_TYPES =
        setOf("image/jpeg", "image/png", "image/webp", "image/heic", "image/heif")

    /** Images above these limits are downscaled/re-encoded before upload. */
    private const val MAX_DIMENSION = 1536

    /**
     * Deliberately far below the API's inline-data limit: the request asks for
     * MEDIA_RESOLUTION_LOW (280 tokens), so the server downsamples hard no
     * matter what arrives, and bytes beyond roughly what a [MAX_DIMENSION]
     * JPEG costs buy nothing while being paid for in upload time, memory and
     * battery. Re-encoding a lossless PNG screenshot does cost some sharpness
     * around text, but the server's own downsampling averages those artifacts
     * out well before the model sees them.
     *
     * Tied to the resolution setting, so raising mediaResolution in
     * [com.watchocr.app.network.GeminiClient] means revisiting this.
     */
    private const val MAX_UPLOAD_BYTES = 1024 * 1024

    private const val JPEG_QUALITY = 85

    private val _activeJobs = MutableStateFlow(0)

    /**
     * Non-zero while OCR work is in flight, from either the manual import flow
     * or [com.watchocr.app.service.DirectoryMonitorService]. The UI shows a
     * progress indicator while this is above zero.
     *
     * Only that comparison is meaningful: [processImage] counts itself and the
     * monitor holds a second count open across its retry cycle, so the value is
     * a nesting depth rather than a number of images.
     */
    val activeJobs: StateFlow<Int> = _activeJobs.asStateFlow()

    /**
     * Holds [activeJobs] above zero for the whole time [block] runs. Needed
     * only on top of [processImage]'s own counting, and only by the monitor's
     * retry cycle: without it the count would fall back to zero during the
     * backoff delay between attempts and blink the progress indicator off
     * mid-retry.
     */
    suspend fun <T> withActiveJob(block: suspend () -> T): T {
        _activeJobs.update { it + 1 }
        try {
            return block()
        } finally {
            _activeJobs.update { it - 1 }
        }
    }

    /**
     * Reads the image at [uri], downscales it if oversized, runs it through
     * Gemini for OCR + translation, copies the (possibly downscaled) image
     * into app-private storage, and persists an [OcrRecord].
     *
     * Counts itself in [activeJobs], so no caller has to remember to — the one
     * thing every caller previously had to know about this object. The monitor
     * nests a second, longer-lived count on top via [withActiveJob]; nothing
     * reads the depth, only whether it is above zero.
     */
    suspend fun processImage(
        context: Context,
        uri: Uri,
        apiKey: String,
        model: String
    ): Result<OcrRecord> = withActiveJob {
        withContext(Dispatchers.IO) {
            try {
                val rawBytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: return@withContext Result.failure(Exception("Unable to open image: $uri"))
                if (rawBytes.isEmpty()) {
                    return@withContext Result.failure(Exception("Image is empty: $uri"))
                }
                // getType only resolves content:// providers, so it returns null
                // for the file:// URIs the monitor passes in; there the extension
                // is the real answer, and one [mimeForFileName] already accepted
                // before the file was queued. The literal is a last resort for a
                // name carrying no usable extension at all.
                val rawMime = context.contentResolver.getType(uri)
                    ?: mimeForFileName(uri.lastPathSegment.orEmpty())
                    ?: "image/jpeg"

                val (bytes, mimeType) = prepareForUpload(rawBytes, rawMime)
                val base64Data = Base64.encodeToString(bytes, Base64.NO_WRAP)

                // Throws on failure; the catch below turns that into this Result.
                val geminiResult = GeminiClient.ocrAndTranslate(apiKey, model, base64Data, mimeType)

                val imagesDir = OcrImages.dir(context).apply { mkdirs() }
                val extension = extensionForMime(mimeType)
                val imageFile = File(imagesDir, "${UUID.randomUUID()}.$extension")
                val record = OcrRecord(
                    imagePath = imageFile.absolutePath,
                    ocrText = geminiResult.ocr,
                    translation = geminiResult.translation,
                    analysis = geminiResult.analysis
                )

                // Both steps under one cleanup, because the file only stops being
                // this block's problem once a row points at it: HistoryCleanup
                // deletes images by walking the rows, so anything left here — the
                // half-written file a failed write leaves behind (a full disk), or
                // the complete one a failed insert strands — would sit in app
                // storage forever with nothing to find it by.
                //
                // NonCancellable so a cancellation cannot split the pair the other
                // way: insert() is a suspension point, and a cancelled withContext
                // reports CancellationException even when its block already ran to
                // the end. Without it a folder switch — which cancel-and-joins the
                // monitor mid-file — could commit the row and then have the catch
                // below delete the image it points at, which is exactly the outcome
                // HistoryCleanup calls out as unrecoverable: a permanently broken
                // thumbnail nothing ever repairs. One file write and one insert, so
                // nothing waits on it for long.
                //
                // Throwable, not Exception, so an OutOfMemoryError is covered too;
                // delete() is not a suspension point, so it still runs on that path.
                val id = withContext(NonCancellable) {
                    try {
                        imageFile.writeBytes(bytes)
                        AppDatabase.getInstance(context).ocrRecordDao().insert(record)
                    } catch (e: Throwable) {
                        imageFile.delete()
                        throw e
                    }
                }

                // insert() returns the generated rowid; carrying it back keeps the
                // returned record from advertising the unsaved placeholder id 0.
                Result.success(record.copy(id = id))
            } catch (e: CancellationException) {
                throw e // cancellation must propagate, not surface as a failed OCR
            } catch (e: Exception) {
                Result.failure(e)
            } catch (e: OutOfMemoryError) {
                // Catching an Error is normally wrong, but this one is expected
                // here and recoverable: decoding, re-encoding and base64-ing an
                // image are the allocations this app can realistically fail, and a
                // failed allocation leaves nothing half-written — the bitmap and
                // buffers are simply released. Left uncaught it escapes the Result
                // contract entirely and kills the process, taking the monitor with
                // it; as a failure it is just one skipped image. Not retried:
                // isRetryable() has no case for it, and an immediate second attempt
                // would allocate exactly as much again.
                Result.failure(e)
            }
        }
    }

    /**
     * Keeps small images in API-supported formats untouched, but re-encodes as
     * JPEG anything oversized (decoding with a power-of-two sample size to
     * bound peak memory) or in a format the API rejects (BMP/GIF/AVIF). The
     * request uses MEDIA_RESOLUTION_LOW, so the extra resolution would be
     * discarded server-side anyway; downscaling just avoids OOM on huge photos
     * and keeps the upload to something worth sending (see [MAX_UPLOAD_BYTES]).
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
     * Extension for the stored copy of an image, from the MIME type it was
     * uploaded as. [prepareForUpload] passes small images in API-supported
     * formats through untouched, so the copy is not always JPEG or PNG —
     * naming HEIC or WebP bytes `.jpg` would leave a file whose extension
     * contradicts its content.
     *
     * The fallback covers the MIME types [prepareForUpload] passes through
     * without recognising (an undecodable format the API turned out to accept
     * anyway); everything it re-encodes is image/jpeg, which is in the map.
     */
    private fun extensionForMime(mimeType: String): String =
        EXTENSION_BY_MIME[mimeType] ?: "jpg"

    /**
     * MIME type for [fileName] from its extension, or null when the extension
     * is not one the app accepts. The directory monitor uses the null case to
     * decide which of the files it is notified about are worth queueing.
     */
    fun mimeForFileName(fileName: String): String? =
        MIME_BY_EXTENSION[fileName.substringAfterLast('.', "").lowercase()]
}
