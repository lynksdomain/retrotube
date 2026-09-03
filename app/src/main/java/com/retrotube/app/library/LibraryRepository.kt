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
                LibraryItem.VideoItem(doc, doc.name ?: "Untitled", pathLabelForSingleDocument(doc))
            }.getOrNull()
        }

    /** Immediate children of [folder] only -- subfolders first, then videos, both alphabetical. */
    fun listChildren(folder: DocumentFile): List<LibraryItem> {
        val folderPath = pathLabelFor(folder)
        val folders = mutableListOf<LibraryItem.FolderItem>()
        val videos = mutableListOf<LibraryItem.VideoItem>()
        val children = runCatching { folder.listFiles() }.getOrDefault(emptyArray())
        for (child in children) {
            when {
                child.isDirectory -> folders.add(LibraryItem.FolderItem(child, child.name ?: "Folder"))
                child.isFile && (child.type?.startsWith("video/") == true) ->
                    videos.add(LibraryItem.VideoItem(child, child.name ?: "Untitled", folderPath))
            }
        }
        return folders.sortedBy { it.name.lowercase() } + videos.sortedBy { it.name.lowercase() }
    }

    /**
     * The full breadcrumb for [folder] -- its own name plus every ancestor up to
     * (and including) whichever added root folder contains it, joined by "/". Walking
     * stops at the first registered root it hits rather than continuing past it into
     * the rest of the device's real filesystem path, which the SAF provider's own
     * parentFile chain doesn't otherwise know to stop at.
     */
    fun pathLabelFor(folder: DocumentFile): String {
        val rootIds = getRootDocuments()
            .mapNotNull { runCatching { DocumentsContract.getDocumentId(it.document.uri) }.getOrNull() }
            .toSet()

        val segments = mutableListOf<String>()
        var current: DocumentFile? = folder
        while (current != null) {
            val node = current
            val name = node.name ?: break
            segments.add(0, name)
            val currentId = runCatching { DocumentsContract.getDocumentId(node.uri) }.getOrNull()
            if (currentId != null && currentId in rootIds) break
            current = node.parentFile
        }
        return segments.joinToString("/")
    }

    /**
     * A [DocumentFile] from [DocumentFile.fromSingleUri] (as opposed to one reached by
     * walking down from a tree root via [DocumentFile.listFiles]) generally can't answer
     * `.parentFile` -- there's no tree context to walk up from a bare document URI, so
     * that call just returns null on most providers. This instead parses the document
     * id directly: Android's standard local-storage provider (what "Add Folder" almost
     * always resolves to) encodes ids as "<volume>:<relative/path/to/file>", which is
     * enough to recover the path without any tree navigation at all.
     */
    private fun pathLabelForSingleDocument(doc: DocumentFile): String {
        val videoSegments = relativePathSegments(doc.uri) ?: return ""
        if (videoSegments.size <= 1) return ""
        val parentSegments = videoSegments.dropLast(1)

        for (root in getRootDocuments()) {
            val rootSegments = relativePathSegments(root.document.uri) ?: continue
            if (rootSegments.isEmpty()) continue
            if (parentSegments.size >= rootSegments.size &&
                parentSegments.subList(0, rootSegments.size) == rootSegments
            ) {
                return parentSegments.subList(rootSegments.size - 1, parentSegments.size).joinToString("/")
            }
        }
        // Root not matched (moved/removed since) -- the immediate folder name beats nothing.
        return parentSegments.lastOrNull().orEmpty()
    }

    private fun relativePathSegments(uri: Uri): List<String>? {
        val documentId = runCatching { DocumentsContract.getDocumentId(uri) }.getOrNull() ?: return null
        val relativePath = documentId.substringAfter(':', missingDelimiterValue = "")
        if (relativePath.isEmpty()) return null
        return relativePath.split("/").filter { it.isNotEmpty() }
    }

    /**
     * Walks every added root folder recursively, for screens that need to see the
     * whole library as one flat list (picking videos for a collection) rather than
     * browsing folder-by-folder. [LibraryVideoEntry.pathLabel] is the full folder
     * path so a bare filename like "Episode 1" is still identifiable out of context.
     */
    fun getAllVideos(): List<LibraryVideoEntry> {
        val result = mutableListOf<LibraryVideoEntry>()
        for (root in getRootDocuments()) {
            collectVideosRecursively(root.document, root.name, result)
        }
        return result
    }

    private fun collectVideosRecursively(folder: DocumentFile, pathLabel: String, out: MutableList<LibraryVideoEntry>) {
        val children = runCatching { folder.listFiles() }.getOrDefault(emptyArray())
        for (child in children) {
            when {
                child.isDirectory -> collectVideosRecursively(child, "$pathLabel/${child.name}", out)
                child.isFile && (child.type?.startsWith("video/") == true) ->
                    out.add(LibraryVideoEntry(child, child.name ?: "Untitled", pathLabel))
            }
        }
    }

    companion object {
        private const val KEY_FOLDERS = "folders"
    }
}

/** One video found anywhere in the library tree, with its full folder path --
 *  used by the flat "pick videos for a collection" screen, distinct from
 *  [LibraryItem.VideoItem] which only carries its immediate parent's name. */
data class LibraryVideoEntry(val document: DocumentFile, val name: String, val pathLabel: String)
