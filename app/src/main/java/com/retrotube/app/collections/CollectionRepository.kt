package com.retrotube.app.collections

import android.content.Context
import java.util.UUID

data class VideoCollection(val id: String, val name: String, val videoUris: List<String>)

/**
 * User-curated shelves that span folders -- unlike the library's folder tree,
 * a collection is just an ordered, named list of video URIs the user chose
 * to group together. Order is manual (drag to reorder) rather than derived,
 * so it's stored as a delimited list rather than sorted on read.
 */
class CollectionRepository(context: Context) {

    private val prefs = context.getSharedPreferences("retrotube_collections", Context.MODE_PRIVATE)

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

    fun setVideoOrder(collectionId: String, videoUris: List<String>) {
        prefs.edit().putString(videosKey(collectionId), videoUris.joinToString("|")).apply()
    }

    fun delete(collectionId: String) {
        val ids = idsInOrder().filterNot { it == collectionId }
        prefs.edit()
            .putString(idsKey(), ids.joinToString(","))
            .remove(nameKey(collectionId))
            .remove(videosKey(collectionId))
            .apply()
    }

    private fun videoUris(id: String): List<String> =
        prefs.getString(videosKey(id), null)?.split("|")?.filter { it.isNotBlank() } ?: emptyList()

    private fun idsInOrder(): List<String> =
        prefs.getString(idsKey(), null)?.split(",")?.filter { it.isNotBlank() } ?: emptyList()

    private fun idsKey() = "collection_ids"
    private fun nameKey(id: String) = "collection_${id}_name"
    private fun videosKey(id: String) = "collection_${id}_videos"
}
