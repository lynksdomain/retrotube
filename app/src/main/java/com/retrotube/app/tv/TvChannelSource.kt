package com.retrotube.app.tv

import org.json.JSONObject

/**
 * One thing feeding a user-programmed TV Mode channel -- a whole folder (local
 * or SMB), a whole collection, or a single video. A channel is just an ordered
 * list of these; at playback time each source expands to whatever videos it
 * currently contains, and sources are expanded in the order they were added,
 * so "add this show's folder, then that one" plays in that order without the
 * user having to hand-order individual episodes.
 */
sealed class TvChannelSource {
    abstract val displayName: String

    data class LocalFolder(val treeUri: String, override val displayName: String) : TvChannelSource()

    data class SmbFolder(
        val shareId: String,
        val relativePath: String,
        override val displayName: String,
    ) : TvChannelSource()

    data class Collection(val collectionId: String, override val displayName: String) : TvChannelSource()

    data class Video(val uri: String, override val displayName: String) : TvChannelSource()

    fun toJson(): JSONObject = JSONObject().apply {
        when (this@TvChannelSource) {
            is LocalFolder -> {
                put("type", "local_folder")
                put("treeUri", treeUri)
                put("displayName", displayName)
            }
            is SmbFolder -> {
                put("type", "smb_folder")
                put("shareId", shareId)
                put("relativePath", relativePath)
                put("displayName", displayName)
            }
            is Collection -> {
                put("type", "collection")
                put("collectionId", collectionId)
                put("displayName", displayName)
            }
            is Video -> {
                put("type", "video")
                put("uri", uri)
                put("displayName", displayName)
            }
        }
    }

    companion object {
        fun fromJson(json: JSONObject): TvChannelSource? = when (json.optString("type")) {
            "local_folder" -> LocalFolder(json.getString("treeUri"), json.getString("displayName"))
            "smb_folder" -> SmbFolder(json.getString("shareId"), json.getString("relativePath"), json.getString("displayName"))
            "collection" -> Collection(json.getString("collectionId"), json.getString("displayName"))
            "video" -> Video(json.getString("uri"), json.getString("displayName"))
            else -> null
        }
    }
}
