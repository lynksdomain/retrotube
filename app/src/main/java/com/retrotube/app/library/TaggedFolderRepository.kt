package com.retrotube.app.library

import android.content.Context
import com.retrotube.app.bucket.ContentKind

data class TaggedFolder(
    val folderKey: String,
    val bucketId: String,
    val contentKind: ContentKind,
    /** The local library root's SAF tree URI string, or the SMB share id, that
     *  this folder lives under -- lets a tagged folder trace back to whichever
     *  source it needs re-resolving/re-scraping through. */
    val rootOrShareId: String,
)

/**
 * The index that turns a raw folder (local or SMB) into "this feeds bucket X,
 * as a show/movie source" -- everything the Shows/Movies/custom-bucket grids
 * read from. Untagging a folder is instant and non-destructive: it only
 * removes the folder from this index, never touches the folder's own files
 * or its scraped per-video/per-folder metadata (that stays cached in case the
 * folder gets tagged again later).
 */
class TaggedFolderRepository(context: Context) {

    private val prefs = context.getSharedPreferences("retrotube_tagged_folders", Context.MODE_PRIVATE)

    fun getAll(): List<TaggedFolder> = folderKeysInOrder().mapNotNull { get(it) }

    fun get(folderKey: String): TaggedFolder? {
        val bucketId = prefs.getString(bucketKey(folderKey), null) ?: return null
        val kindName = prefs.getString(kindKey(folderKey), null) ?: return null
        val kind = runCatching { ContentKind.valueOf(kindName) }.getOrNull() ?: return null
        val rootOrShareId = prefs.getString(rootKey(folderKey), null) ?: return null
        return TaggedFolder(folderKey, bucketId, kind, rootOrShareId)
    }

    fun getForBucket(bucketId: String): List<TaggedFolder> = getAll().filter { it.bucketId == bucketId }

    fun isTagged(folderKey: String): Boolean = prefs.contains(bucketKey(folderKey))

    fun tag(folderKey: String, bucketId: String, contentKind: ContentKind, rootOrShareId: String) {
        val keys = folderKeysInOrder()
        val updatedKeys = if (folderKey in keys) keys else keys + folderKey
        prefs.edit()
            .putString(keysListKey(), updatedKeys.joinToString(KEY_SEPARATOR))
            .putString(bucketKey(folderKey), bucketId)
            .putString(kindKey(folderKey), contentKind.name)
            .putString(rootKey(folderKey), rootOrShareId)
            .apply()
    }

    fun untag(folderKey: String) {
        val keys = folderKeysInOrder().filterNot { it == folderKey }
        prefs.edit()
            .putString(keysListKey(), keys.joinToString(KEY_SEPARATOR))
            .remove(bucketKey(folderKey))
            .remove(kindKey(folderKey))
            .remove(rootKey(folderKey))
            .apply()
    }

    private fun folderKeysInOrder(): List<String> =
        prefs.getString(keysListKey(), null)?.split(KEY_SEPARATOR)?.filter { it.isNotEmpty() } ?: emptyList()

    private fun keysListKey() = "tagged_folder_keys"
    private fun bucketKey(folderKey: String) = "folder_${folderKey}_bucket"
    private fun kindKey(folderKey: String) = "folder_${folderKey}_kind"
    private fun rootKey(folderKey: String) = "folder_${folderKey}_root"

    companion object {
        // A folder key is a full URI/path string, which a comma or pipe could
        // plausibly appear inside -- U+0001 is a control character neither
        // local paths nor SMB paths ever use, so it's a safe list separator.
        private const val KEY_SEPARATOR = ""
    }
}
