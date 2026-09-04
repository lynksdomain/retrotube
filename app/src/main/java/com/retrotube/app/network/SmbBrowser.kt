package com.retrotube.app.network

import com.retrotube.app.library.LibraryItem
import jcifs.CIFSContext
import jcifs.smb.SmbFile

/**
 * Lists a folder inside a connected SMB share, mirroring
 * [com.retrotube.app.library.LibraryRepository.listChildren]'s shape (subfolders
 * first, then videos, both alphabetical) but over the network instead of SAF.
 * Every call here is real network I/O and must run off the main thread.
 */
object SmbBrowser {

    private val VIDEO_EXTENSIONS = setOf(
        "mkv", "mp4", "avi", "mov", "webm", "m4v", "wmv", "flv", "ts", "m2ts", "3gp",
    )

    /** [context] defaults to a fresh one for a single one-off listing; a caller
     *  walking many folders in the same share (see TvChannelRepository's network
     *  crawl) should build one [CIFSContext] via [SmbClient.contextFor] and pass
     *  it into every call instead -- a fresh context re-negotiates the SMB session
     *  from scratch, which is the dominant cost of listing a deep tree one call
     *  at a time. */
    fun listChildren(share: NetworkShare, relativePath: String, context: CIFSContext = SmbClient.contextFor(share)): List<LibraryItem> {
        val folder = SmbFile(folderUrl(share, relativePath), context)
        val children = folder.listFiles() ?: emptyArray()

        val folders = mutableListOf<LibraryItem.SmbFolderItem>()
        val videos = mutableListOf<LibraryItem.SmbVideoItem>()
        val pathLabel = pathLabelFor(share, relativePath)

        for (child in children) {
            val childName = child.name.trimEnd('/')
            val childRelativePath = if (relativePath.isEmpty()) childName else "$relativePath/$childName"
            if (child.isDirectory) {
                folders.add(LibraryItem.SmbFolderItem(share.id, childRelativePath, childName))
            } else if (child.isFile && !childName.startsWith("._") && childName != ".DS_Store") {
                // macOS shares expose AppleDouble sidecar files ("._Real Name.mkv") and
                // .DS_Store alongside the real ones -- same name pattern, tiny dummy
                // content, and a fittingly useless-looking scanline glitch when
                // ExoPlayer tries to decode one as if it were the actual video.
                val extension = childName.substringAfterLast('.', "").lowercase()
                if (extension in VIDEO_EXTENSIONS) {
                    videos.add(LibraryItem.SmbVideoItem(share.id, childRelativePath, childName, pathLabel))
                }
            }
        }
        return folders.sortedBy { it.name.lowercase() } + videos.sortedBy { it.name.lowercase() }
    }

    private fun pathLabelFor(share: NetworkShare, relativePath: String): String =
        if (relativePath.isEmpty()) share.displayName else "${share.displayName}/$relativePath"

    private fun folderUrl(share: NetworkShare, relativePath: String): String {
        val trimmed = relativePath.trim('/')
        return if (trimmed.isEmpty()) share.rootUrl else "${share.rootUrl}$trimmed/"
    }
}
