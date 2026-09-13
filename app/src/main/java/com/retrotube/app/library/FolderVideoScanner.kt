package com.retrotube.app.library

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.retrotube.app.network.NetworkShareRepository
import com.retrotube.app.network.SmbBrowser
import com.retrotube.app.network.SmbUri

data class ScannedVideo(val uri: Uri, val name: String)
data class ScannedFolder(val folderKey: String, val name: String)

/**
 * Recursively lists a folder's videos (or its direct subfolders) for the
 * Phase 3 tagging flow and bucket content grids -- scheme-dispatched on the
 * folder key itself, so a local (SAF, `content://`) folder and an SMB
 * (`smb://`) one are equally taggable/scannable, the same way TV Mode's
 * channel sources already treat both as first-class (see
 * [com.retrotube.app.tv.TvChannelRepository]). An SMB folder key is just
 * [SmbUri.build]'s own URI form -- no new scheme invented for it.
 *
 * SMB calls here are real network I/O and must be run off the main thread,
 * same convention as everywhere else SMB is touched in this app.
 *
 * Every real scan is a genuinely expensive walk -- local (SAF) traversal is a
 * separate binder IPC round trip per file/folder via [DocumentFile], not a
 * plain filesystem read, and SMB is real network I/O on top of that. Without
 * caching, every screen that shows a folder's contents (Show Detail, Bucket
 * Content, Season Episodes, a library-wide refresh) re-walked the entire tree
 * from scratch on every single open, which is most of why the app felt slow.
 * Results are cached per folder key for the process's lifetime; only an
 * explicit refresh action (tagging, Force Refresh, Refresh Library/Bucket)
 * calls [invalidate] to force a fresh walk next time that folder's read.
 */
object FolderVideoScanner {

    private val videoCache = java.util.concurrent.ConcurrentHashMap<String, List<ScannedVideo>>()
    private val subfolderCache = java.util.concurrent.ConcurrentHashMap<String, List<ScannedFolder>>()

    fun listVideosRecursively(context: Context, folderKey: String): List<ScannedVideo> {
        videoCache[folderKey]?.let { return it }
        val uri = Uri.parse(folderKey)
        val (shareId, relativePath) = SmbUri.parse(uri) ?: return listLocalVideosRecursively(context, folderKey)
            .also { videoCache[folderKey] = it }
        return listSmbVideosRecursively(context, shareId, relativePath).also { videoCache[folderKey] = it }
    }

    /** One level only -- each direct subfolder becomes its own tagged show when a
     *  parent folder is tagged as "a category of shows" (spec's 3-way tagging flow). */
    fun listSubfolders(context: Context, folderKey: String): List<ScannedFolder> {
        subfolderCache[folderKey]?.let { return it }
        val uri = Uri.parse(folderKey)
        val (shareId, relativePath) = SmbUri.parse(uri) ?: return listLocalSubfolders(context, folderKey)
            .also { subfolderCache[folderKey] = it }
        return listSmbSubfolders(context, shareId, relativePath).also { subfolderCache[folderKey] = it }
    }

    /** Drops any cached scan for [folderKey] so the next [listVideosRecursively]/
     *  [listSubfolders] call does a real walk again -- call this from any action
     *  that could have actually changed what's in the folder (tagging it,
     *  Force Refresh, Refresh Library/Bucket, a WiFi Import landing new files). */
    fun invalidate(folderKey: String) {
        videoCache.remove(folderKey)
        subfolderCache.remove(folderKey)
    }

    /** True for any folder key this scanner can actually resolve -- used to guard
     *  against a stale/revoked local permission or a since-removed SMB share. */
    fun isReachable(context: Context, folderKey: String): Boolean {
        val uri = Uri.parse(folderKey)
        val smb = SmbUri.parse(uri)
        return if (smb != null) {
            NetworkShareRepository(context).get(smb.first) != null
        } else {
            runCatching { DocumentFile.fromTreeUri(context, uri) }.getOrNull() != null
        }
    }

    private fun listLocalVideosRecursively(context: Context, folderKey: String): List<ScannedVideo> {
        val root = runCatching { DocumentFile.fromTreeUri(context, Uri.parse(folderKey)) }.getOrNull() ?: return emptyList()
        val out = mutableListOf<DocumentFile>()
        collectLocal(root, out)
        return out.sortedBy { (it.name ?: "").lowercase() }.map { ScannedVideo(it.uri, it.name ?: "Untitled") }
    }

    private fun collectLocal(folder: DocumentFile, out: MutableList<DocumentFile>) {
        val children = runCatching { folder.listFiles() }.getOrDefault(emptyArray())
        for (child in children) {
            when {
                child.isDirectory -> collectLocal(child, out)
                child.isFile && (child.type?.startsWith("video/") == true) -> out.add(child)
            }
        }
    }

    private fun listLocalSubfolders(context: Context, folderKey: String): List<ScannedFolder> {
        val root = runCatching { DocumentFile.fromTreeUri(context, Uri.parse(folderKey)) }.getOrNull() ?: return emptyList()
        return runCatching { root.listFiles() }.getOrDefault(emptyArray())
            .filter { it.isDirectory }
            .sortedBy { (it.name ?: "").lowercase() }
            .map { ScannedFolder(it.uri.toString(), it.name ?: "Folder") }
    }

    private fun listSmbVideosRecursively(context: Context, shareId: String, relativePath: String): List<ScannedVideo> {
        val share = NetworkShareRepository(context).get(shareId) ?: return emptyList()
        val out = mutableListOf<ScannedVideo>()
        collectSmb(share, relativePath, out)
        return out.sortedBy { it.name.lowercase() }
    }

    private fun collectSmb(share: com.retrotube.app.network.NetworkShare, relativePath: String, out: MutableList<ScannedVideo>) {
        val children = runCatching { SmbBrowser.listChildren(share, relativePath) }.getOrDefault(emptyList())
        for (child in children) {
            when (child) {
                is LibraryItem.SmbFolderItem -> collectSmb(share, child.relativePath, out)
                is LibraryItem.SmbVideoItem -> out += ScannedVideo(child.uri, child.name)
                else -> Unit
            }
        }
    }

    private fun listSmbSubfolders(context: Context, shareId: String, relativePath: String): List<ScannedFolder> {
        val share = NetworkShareRepository(context).get(shareId) ?: return emptyList()
        val children = runCatching { SmbBrowser.listChildren(share, relativePath) }.getOrDefault(emptyList())
        return children.filterIsInstance<LibraryItem.SmbFolderItem>()
            .sortedBy { it.name.lowercase() }
            .map { ScannedFolder(SmbUri.build(shareId, it.relativePath).toString(), it.name) }
    }
}
