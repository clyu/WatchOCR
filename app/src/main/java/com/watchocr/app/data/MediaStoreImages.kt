package com.watchocr.app.data

import android.content.Context
import android.provider.MediaStore
import java.io.File

/** An image folder (MediaStore bucket) available on the device. */
data class ImageBucket(val id: Long, val name: String, val imageCount: Int)

/** Read-only queries over the device's MediaStore images collection. */
object MediaStoreImages {

    /**
     * All buckets (folders) that currently contain images, ordered by most
     * recently used first (buckets with the newest images come first).
     */
    fun queryBuckets(context: Context): List<ImageBucket> {
        val projection = arrayOf(
            MediaStore.Images.Media.BUCKET_ID,
            MediaStore.Images.Media.BUCKET_DISPLAY_NAME
        )
        val buckets = LinkedHashMap<Long, ImageBucket>()
        context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projection,
            null,
            null,
            "${MediaStore.Images.Media.DATE_ADDED} DESC"
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_ID)
            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                buckets[id] = buckets[id]?.let { it.copy(imageCount = it.imageCount + 1) }
                    ?: ImageBucket(id, cursor.getString(nameCol) ?: "(unnamed)", 1)
            }
        }
        return buckets.values.toList()
    }

    /**
     * Absolute directory path of [bucketId], taken from the DATA column of any
     * image in the bucket (BUCKET_ID is the hash of the lowercased parent
     * directory, so every image in a bucket shares one parent), or null when no
     * row has a usable path.
     */
    @Suppress("DEPRECATION") // DATA is deprecated but is the only bucket->path mapping
    fun queryBucketPath(context: Context, bucketId: Long): String? {
        context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Images.Media.DATA),
            "${MediaStore.Images.Media.BUCKET_ID} = ?",
            arrayOf(bucketId.toString()),
            null
        )?.use { cursor ->
            val dataCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATA)
            while (cursor.moveToNext()) {
                val data = cursor.getString(dataCol)
                if (!data.isNullOrBlank()) {
                    File(data).parent?.let { return it }
                }
            }
        }
        return null
    }
}
