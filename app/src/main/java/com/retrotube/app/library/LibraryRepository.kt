package com.retrotube.app.library

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile

/**
 * Tracks which folders (SAF tree URIs) the user has pointed the library at.
 * Browsing is folder-by-folder (mirroring the real on-disk structure) rather
 * than one flattened list -- two identically-named files in different
 * folders are only ever shown together if they're actually in the same
 * folder, since you navigate to them separately.
 */
class LibraryRepository(private val context: Context) {

    private val prefs = context.getSharedPreferences("retrotube_library", Context.MODE_PRIVATE)

    /**
     * A previously-added folder's permission can vanish out from under us (user revokes
     * it in system settings, an SD card is removed, etc.) -- SAF calls throw
     * SecurityException in that case, so each folder is resolved defensively and just
     * dropped from the list rather than crashing the whole library screen.
     */
    fun getRootDocuments(): List<LibraryItem.FolderItem> =
        prefs.getStringSet(KEY_FOLDERS, emptySet()).orEmpty().mapNotNull { uriString ->
            runCatching {
                val treeUri = Uri.parse(uriString)
                val doc = DocumentFile.fromTreeUri(context, treeUri) ?: return@mapNotNull null
                LibraryItem.FolderItem(doc, doc.name ?: "Folder")
            }.getOrNull()
        }.sortedBy { it.name.lowercase() }

    fun addFolder(treeUri: Uri) {
        context.contentResolver.takePersistableUriPermission(
            treeUri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION,
        )
        val current = prefs.getStringSet(KEY_FOLDERS, emptySet()).orEmpty().toMutableSet()
        current.add(treeUri.toString())
        prefs.edit().putStringSet(KEY_FOLDERS, current).apply()
    }

    /**
     * [treeUri] here is typically a DocumentFile.uri from a root [LibraryItem.FolderItem],
     * which SAF expands to a "tree/.../document/..." form -- NOT the same string as the
     * plain tree URI we originally stored. Comparing by tree-document-id instead of raw
     * string avoids that mismatch silently making this a no-op.
     */
    fun removeRoot(treeUri: Uri) {
        val targetTreeId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull()
        val current = prefs.getStringSet(KEY_FOLDERS, emptySet()).orEmpty()
        val (toRemove, toKeep) = current.partition { stored ->
            val storedTreeId = runCatching { DocumentsContract.getTreeDocumentId(Uri.parse(stored)) }.getOrNull()
            storedTreeId != null && storedTreeId == targetTreeId
        }
        prefs.edit().putStringSet(KEY_FOLDERS, toKeep.toMutableSet()).apply()

        for (removedUriString in toRemove) {
            try {
                context.contentResolver.releasePersistableUriPermission(
                    Uri.parse(removedUriString),
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            } catch (e: SecurityException) {
                // Permission was already gone -- nothing to release.
            }
        }
    }

    /**
     * Resolves raw video URI strings (from [com.retrotube.app.progress.PlaybackProgressRepository])
     * back into browsable [LibraryItem.VideoItem]s, in the order given. A URI silently drops
     * out if its file was deleted or its permission is gone, same defensive pattern as
     * [getRootDocuments]/[listChildren].
     */
    fun resolveVideoItems(uriStrings: List<String>): List<LibraryItem.VideoItem> =
        uriStrings.mapNotNull { uriString ->
            runCatching {
                val doc = DocumentFile.fromSingleUri(context, Uri.parse(uriString)) ?: return@mapNotNull null
                if (!doc.exists()) return@mapNotNull null
                val locationHint = runCatching { doc.parentFile?.name }.getOrNull().orEmpty()
                LibraryItem.VideoItem(doc, doc.name ?: "Untitled", locationHint)
            }.getOrNull()
        }

    /** Immediate children of [folder] only -- subfolders first, then videos, both alphabetical. */
    fun listChildren(folder: DocumentFile): List<LibraryItem> {
        val folderName = folder.name ?: "Folder"
        val folders = mutableListOf<LibraryItem.FolderItem>()
        val videos = mutableListOf<LibraryItem.VideoItem>()
        val children = runCatching { folder.listFiles() }.getOrDefault(emptyArray())
        for (child in children) {
            when {
                child.isDirectory -> folders.add(LibraryItem.FolderItem(child, child.name ?: "Folder"))
                child.isFile && (child.type?.startsWith("video/") == true) ->
                    videos.add(LibraryItem.VideoItem(child, child.name ?: "Untitled", folderName))
            }
        }
        return folders.sortedBy { it.name.lowercase() } + videos.sortedBy { it.name.lowercase() }
    }

    companion object {
        private const val KEY_FOLDERS = "folders"
    }
}
