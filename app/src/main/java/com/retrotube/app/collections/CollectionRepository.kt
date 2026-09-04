package com.retrotube.app.collections

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.LruCache
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

data class VideoCollection(val id: String, val name: String, val videoUris: List<String>)

/**
 * User-curated shelves that span folders -- unlike the library's folder tree,
 * a collection is just an ordered, named list of video URIs the user chose
 * to group together. Order is manual (drag to reorder) rather than derived,
 * so it's stored as a delimited list rather than sorted on read.
 */
class CollectionRepository(private val context: Context) {

    private val prefs = context.getSharedPreferences("retrotube_collections", Context.MODE_PRIVATE)
    private val posterDir = File(context.filesDir, "collection_posters").apply { mkdirs() }

    fun getAll(): List<VideoCollection> =
        idsInOrder().mapNotNull { id ->
            val name = prefs.getString(nameKey(id), null) ?: return@mapNotNull null
            VideoCollection(id, name, videoUris(id))
        }

    fun get(id: String): VideoCollection? {
        val name = prefs.getString(nameKey(id), null) ?: return null
        return VideoCollection(id, name, videoUris(id))
    }

    /** Creates a new collection containing just [videoUri], returning its id. */
    fun create(name: String, videoUri: String): String {
        val id = UUID.randomUUID().toString()
        val ids = idsInOrder() + id
        prefs.edit()
            .putString(idsKey(), ids.joinToString(","))
            .putString(nameKey(id), name)
            .putString(videosKey(id), videoUri)
            .apply()
        return id
    }

    fun addVideo(collectionId: String, videoUri: String) {
        val current = videoUris(collectionId)
        if (videoUri in current) return
        prefs.edit().putString(videosKey(collectionId), (current + videoUri).joinToString("|")).apply()
    }

    fun removeVideo(collectionId: String, videoUri: String) {
        val current = videoUris(collectionId)
        setVideoOrder(collectionId, current.filterNot { it == videoUri })
    }

    fun setVideoOrder(collectionId: String, videoUris: List<String>) {
        prefs.edit().putString(videosKey(collectionId), videoUris.joinToString("|")).apply()
    }

    fun delete(collectionId: String) {
        val ids = idsInOrder().filterNot { it == collectionId }
        prefs.getString(posterKey(collectionId), null)?.let { runCatching { File(it).delete() } }
        prefs.edit()
            .putString(idsKey(), ids.joinToString(","))
            .remove(nameKey(collectionId))
            .remove(videosKey(collectionId))
            .remove(posterKey(collectionId))
            .apply()
        posterCache.remove(collectionId)
    }

    /** A RecyclerView rebinds this on every scroll past the same card -- cached in memory
     *  (shared across every repository instance, not per-instance) so that doesn't mean
     *  re-decoding the same PNG from disk each time. */
    fun getPoster(collectionId: String): Bitmap? {
        posterCache.get(collectionId)?.let { return it }
        val path = prefs.getString(posterKey(collectionId), null) ?: return null
        val bitmap = runCatching { BitmapFactory.decodeFile(path) }.getOrNull() ?: return null
        posterCache.put(collectionId, bitmap)
        return bitmap
    }

    /** Decodes and downsamples whatever image the user picked -- a full-resolution
     *  phone photo is far more pixels than a poster card will ever need, and would
     *  otherwise sit fully decoded in memory for every visible collection card. */
    fun setPosterFromUri(collectionId: String, uri: Uri): Boolean {
        val bitmap = decodeSampledBitmap(uri) ?: return false
        val file = File(posterDir, "$collectionId.png")
        FileOutputStream(file).use { out -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, out) }
        prefs.edit().putString(posterKey(collectionId), file.absolutePath).apply()
        posterCache.put(collectionId, bitmap)
        return true
    }

    private fun decodeSampledBitmap(uri: Uri): Bitmap? {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) } ?: return null

        var sampleSize = 1
        val maxDimension = 720
        while (bounds.outWidth / sampleSize > maxDimension || bounds.outHeight / sampleSize > maxDimension) {
            sampleSize *= 2
        }

        val decodeOptions = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        return resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, decodeOptions) }
    }

    private fun videoUris(id: String): List<String> =
        prefs.getString(videosKey(id), null)?.split("|")?.filter { it.isNotBlank() } ?: emptyList()

    private fun idsInOrder(): List<String> =
        prefs.getString(idsKey(), null)?.split(",")?.filter { it.isNotBlank() } ?: emptyList()

    private fun idsKey() = "collection_ids"
    private fun nameKey(id: String) = "collection_${id}_name"
    private fun videosKey(id: String) = "collection_${id}_videos"
    private fun posterKey(id: String) = "collection_${id}_poster"

    companion object {
        private val posterCache = LruCache<String, Bitmap>(32)
    }
}
