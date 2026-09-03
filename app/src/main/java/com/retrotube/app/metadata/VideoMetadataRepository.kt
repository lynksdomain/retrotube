package com.retrotube.app.metadata

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import java.io.FileOutputStream

/**
 * User-set overrides layered on top of whatever we can derive automatically --
 * a display title and a poster frame, both keyed by the video's content URI
 * string. Absence of an override just means "use the automatic one",
 * everywhere this is read.
 */
class VideoMetadataRepository(private val context: Context) {

    private val prefs = context.getSharedPreferences("retrotube_video_metadata", Context.MODE_PRIVATE)
    private val thumbnailDir = File(context.filesDir, "custom_thumbnails").apply { mkdirs() }

    fun getCustomTitle(videoUri: String): String? = prefs.getString(titleKey(videoUri), null)

    fun setCustomTitle(videoUri: String, title: String) {
        prefs.edit().putString(titleKey(videoUri), title).apply()
    }

    fun clearCustomTitle(videoUri: String) {
        prefs.edit().remove(titleKey(videoUri)).apply()
    }

    fun getCustomThumbnail(videoUri: String): Bitmap? {
        val path = prefs.getString(thumbnailKey(videoUri), null) ?: return null
        return runCatching { BitmapFactory.decodeFile(path) }.getOrNull()
    }

    fun setCustomThumbnail(videoUri: String, bitmap: Bitmap) {
        val file = File(thumbnailDir, "${videoUri.hashCode()}.png")
        FileOutputStream(file).use { out -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, out) }
        prefs.edit().putString(thumbnailKey(videoUri), file.absolutePath).apply()
    }

    fun clearCustomThumbnail(videoUri: String) {
        prefs.getString(thumbnailKey(videoUri), null)?.let { runCatching { File(it).delete() } }
        prefs.edit().remove(thumbnailKey(videoUri)).apply()
    }

    private fun titleKey(videoUri: String) = "title_$videoUri"
    private fun thumbnailKey(videoUri: String) = "thumb_$videoUri"
}
