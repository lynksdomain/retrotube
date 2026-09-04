package com.retrotube.app.metadata

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
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

    /** A RecyclerView rebinds this on every scroll past the same row -- cached in memory
     *  (shared across every repository instance, not per-instance) so that doesn't mean
     *  re-decoding the same PNG from disk each time. */
    fun getCustomThumbnail(videoUri: String): Bitmap? {
        thumbnailCache.get(videoUri)?.let { return it }
        val path = prefs.getString(thumbnailKey(videoUri), null) ?: return null
        val bitmap = runCatching { BitmapFactory.decodeFile(path) }.getOrNull() ?: return null
        thumbnailCache.put(videoUri, bitmap)
        return bitmap
    }

    fun setCustomThumbnail(videoUri: String, bitmap: Bitmap) {
        val file = File(thumbnailDir, "${videoUri.hashCode()}.png")
        FileOutputStream(file).use { out -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, out) }
        prefs.edit().putString(thumbnailKey(videoUri), file.absolutePath).apply()
        thumbnailCache.put(videoUri, bitmap)
    }

    fun clearCustomThumbnail(videoUri: String) {
        prefs.getString(thumbnailKey(videoUri), null)?.let { runCatching { File(it).delete() } }
        prefs.edit().remove(thumbnailKey(videoUri)).apply()
        thumbnailCache.remove(videoUri)
    }

    fun getTags(videoUri: String): List<String> =
        prefs.getString(tagsKey(videoUri), null)
            ?.split(",")
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?: emptyList()

    fun setTags(videoUri: String, tags: List<String>) {
        val cleaned = tags.map { it.trim() }.filter { it.isNotEmpty() }
        if (cleaned.isEmpty()) {
            prefs.edit().remove(tagsKey(videoUri)).apply()
        } else {
            prefs.edit().putString(tagsKey(videoUri), cleaned.joinToString(",")).apply()
        }
    }

    private fun titleKey(videoUri: String) = "title_$videoUri"
    private fun thumbnailKey(videoUri: String) = "thumb_$videoUri"
    private fun tagsKey(videoUri: String) = "tags_$videoUri"

    companion object {
        private val thumbnailCache = LruCache<String, Bitmap>(64)
    }
}
