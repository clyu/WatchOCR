package com.watchocr.app.data

import android.content.Context
import android.provider.MediaStore
import java.io.File

/**
 * An image folder (MediaStore bucket) available on the device. [path] is the
 * folder's absolute path — what the directory monitor actually watches — and is
 * never null, because a bucket no path could be derived for is dropped rather
 * than offered; see [MediaStoreImages.queryBuckets].
 */
data class ImageBucket(val id: Long, val name: String, val path: String, val imageCount: Int)

/** Read-only queries over the device's MediaStore images collection. */
object MediaStoreImages {

    /** A bucket while [queryBuckets] is still walking rows into it. */
    private class Accumulator(val name: String) {
        /** From the first row carrying a usable one; null until then. */
        var path: String? = null
        var imageCount: Int = 0
    }

    /**
     * All buckets (folders) that currently contain images, ordered by most
     * recently used first (buckets with the newest images come first).
     *
     * A bucket's path is taken from the DATA column of any one of its images —
     * BUCKET_ID is the hash of the lowercased parent directory, so every image in
     * a bucket shares one parent. Read during this pass rather than by a second
     * query keyed on whichever bucket the user picked: that took two passes over
     * MediaStore where one does, and left the picker offering folders that could
     * only fail once chosen. The monitor watches a path, so a bucket no path
     * could be derived for is dropped here instead of listed.
     */
    @Suppress("DEPRECATION") // DATA is deprecated but is the only bucket->path mapping
    fun queryBuckets(context: Context): List<ImageBucket> {
        val buckets = LinkedHashMap<Long, Accumulator>()
        context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            arrayOf(
                MediaStore.Images.Media.BUCKET_ID,
                MediaStore.Images.Media.BUCKET_DISPLAY_NAME,
                MediaStore.Images.Media.DATA
            ),
            null,
            null,
            "${MediaStore.Images.Media.DATE_ADDED} DESC"
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_ID)
            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
            val dataCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATA)
            while (cursor.moveToNext()) {
                val bucket = buckets.getOrPut(cursor.getLong(idCol)) {
                    Accumulator(cursor.getString(nameCol) ?: "(unnamed)")
                }
                // Every row counts towards the bucket, but only the first usable
                // path is kept: a row whose DATA is blank (or names a file with no
                // parent) still belongs here, it just cannot say where it lives,
                // so the next row gets to answer instead.
                bucket.imageCount++
                if (bucket.path == null) {
                    val data = cursor.getString(dataCol)
                    if (!data.isNullOrBlank()) bucket.path = File(data).parent
                }
            }
        }
        return buckets.mapNotNull { (id, bucket) ->
            bucket.path?.let { ImageBucket(id, bucket.name, it, bucket.imageCount) }
        }
    }
}
