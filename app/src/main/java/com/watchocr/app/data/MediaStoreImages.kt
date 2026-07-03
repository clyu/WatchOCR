package com.watchocr.app.data

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore

/** An image folder (MediaStore bucket) available on the device. */
data class ImageBucket(val id: Long, val name: String, val imageCount: Int)

/** A single image row from the MediaStore images collection. */
data class MediaImage(
    val uri: Uri,
    val displayName: String
)

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
        val names = LinkedHashMap<Long, String>()
        val counts = HashMap<Long, Int>()
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
                names.getOrPut(id) { cursor.getString(nameCol) ?: "(unnamed)" }
                counts[id] = (counts[id] ?: 0) + 1
            }
        }
        return names.map { (id, name) -> ImageBucket(id, name, counts[id] ?: 0) }
    }

    /**
     * Images in [bucketId] added to MediaStore at or after [addedSinceMillis],
     * oldest first. Rows marked IS_PENDING are excluded by default on Android
     * 10+, but writers that skip the pending pattern (direct File writes picked
     * up by the media scanner) can still surface rows whose file is not fully
     * written yet — callers must verify stability before reading the content.
     */
    fun queryBucketImages(context: Context, bucketId: Long, addedSinceMillis: Long): List<MediaImage> {
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME
        )
        val selection =
            "${MediaStore.Images.Media.BUCKET_ID} = ? AND ${MediaStore.Images.Media.DATE_ADDED} >= ?"
        // DATE_ADDED is stored in seconds.
        val selectionArgs = arrayOf(bucketId.toString(), (addedSinceMillis / 1000).toString())

        val images = mutableListOf<MediaImage>()
        context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projection,
            selection,
            selectionArgs,
            "${MediaStore.Images.Media.DATE_ADDED} ASC"
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                images.add(
                    MediaImage(
                        uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id),
                        displayName = cursor.getString(nameCol) ?: id.toString()
                    )
                )
            }
        }
        return images
    }
}
