package com.retrotube.app.subtitle

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.retrotube.app.network.NetworkShareRepository
import com.retrotube.app.network.SmbClient
import com.retrotube.app.network.SmbUri
import jcifs.smb.SmbFile

data class SidecarSubtitle(val uri: Uri, val mimeType: String)

/** Looks for a same-basename subtitle file next to a video (§6.1's sidecar tier
 *  -- highest priority after an already-cached pick). Handles both a local
 *  (SAF) video and one on a connected SMB share -- the latter needs its own
 *  share-listing round trip since [DocumentFile] only ever understands
 *  `content://` URIs.
 *
 *  [findSidecar] must always be called off the main thread: the SMB path is
 *  real network I/O (jcifs's own session/context setup alone throws
 *  NetworkOnMainThreadException), and even the local path is a real SAF IPC
 *  call. See [com.retrotube.app.PlayerActivity.checkForSidecarAsync]. */
object SidecarLoader {

    private val EXTENSIONS = listOf(
        ".srt" to "application/x-subrip",
        ".vtt" to "text/vtt",
        ".ssa" to "text/x-ssa",
        ".ass" to "text/x-ssa",
    )

    fun findSidecar(context: Context, videoUri: Uri): SidecarSubtitle? {
        SmbUri.parse(videoUri)?.let { (shareId, relativePath) ->
            return findSmbSidecar(context, shareId, relativePath)
        }
        return findLocalSidecar(context, videoUri)
    }

    private fun findLocalSidecar(context: Context, videoUri: Uri): SidecarSubtitle? {
        // fromSingleUri would build a SingleDocumentFile here, whose
        // getParentFile() *always* returns null -- single-document files never
        // track a parent, regardless of the URI's own shape. Every local video
        // URI this app ever plays came from a tree walk (FolderVideoScanner),
        // so it's already a real tree-document URI; fromTreeUri is what
        // actually preserves the parent-navigability needed to find a sibling.
        val videoDoc = runCatching { DocumentFile.fromTreeUri(context, videoUri) }.getOrNull() ?: return null
        val parent = videoDoc.parentFile ?: return null
        val baseName = videoDoc.name?.substringBeforeLast('.') ?: return null
        val siblings = runCatching { parent.listFiles() }.getOrDefault(emptyArray())

        for ((extension, mimeType) in EXTENSIONS) {
            val match = siblings.firstOrNull { it.name.equals("$baseName$extension", ignoreCase = true) }
            if (match != null) return SidecarSubtitle(match.uri, mimeType)
        }
        return null
    }

    /** Same idea as [findLocalSidecar], but the "directory listing" is a real
     *  SMB round trip (raw jcifs, not [com.retrotube.app.network.SmbBrowser] --
     *  that one filters out everything but folders/videos, exactly the sibling
     *  files a sidecar search needs to see). The returned URI is a normal
     *  [SmbUri], so it plays through [com.retrotube.app.network.SmbDataSource]
     *  the same as the video itself -- no separate download step needed. */
    private fun findSmbSidecar(context: Context, shareId: String, relativePath: String): SidecarSubtitle? {
        val share = NetworkShareRepository(context).get(shareId) ?: return null
        val slashIndex = relativePath.lastIndexOf('/')
        val parentPath = if (slashIndex >= 0) relativePath.substring(0, slashIndex) else ""
        val videoFileName = if (slashIndex >= 0) relativePath.substring(slashIndex + 1) else relativePath
        val baseName = videoFileName.substringBeforeLast('.')

        val folderUrl = if (parentPath.isEmpty()) share.rootUrl else "${share.rootUrl}$parentPath/"
        val siblingNames = runCatching {
            val folder = SmbFile(folderUrl, SmbClient.contextFor(share))
            folder.listFiles()?.map { it.name.trimEnd('/') } ?: emptyList()
        }.getOrDefault(emptyList())

        for ((extension, mimeType) in EXTENSIONS) {
            val matchName = siblingNames.firstOrNull { it.equals("$baseName$extension", ignoreCase = true) } ?: continue
            val subtitleRelativePath = if (parentPath.isEmpty()) matchName else "$parentPath/$matchName"
            return SidecarSubtitle(SmbUri.build(shareId, subtitleRelativePath), mimeType)
        }
        return null
    }
}
