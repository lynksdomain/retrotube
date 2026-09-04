package com.retrotube.app.network

import android.net.Uri

/**
 * A stable identity for an SMB file that carries no credentials -- just the
 * saved share's own id (so [NetworkShareRepository] can look up host/auth at
 * connection time) plus the file's path within that share. Everything that
 * already keys off a video's URI string (playback progress, per-video shader
 * settings, custom titles/posters, collections) keeps working unchanged,
 * since none of those care what scheme the URI uses.
 */
object SmbUri {
    private const val SCHEME = "smb"

    fun build(shareId: String, relativePath: String): Uri {
        val builder = Uri.Builder().scheme(SCHEME).authority(shareId)
        relativePath.split("/").filter { it.isNotEmpty() }.forEach { builder.appendPath(it) }
        return builder.build()
    }

    /** Returns (shareId, relativePath) if [uri] is one of ours, else null. */
    fun parse(uri: Uri): Pair<String, String>? {
        if (uri.scheme != SCHEME) return null
        val shareId = uri.authority ?: return null
        return shareId to uri.pathSegments.joinToString("/")
    }
}
