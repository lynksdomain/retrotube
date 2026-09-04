package com.retrotube.app.tv

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.retrotube.app.collections.CollectionRepository
import com.retrotube.app.library.LibraryItem
import com.retrotube.app.metadata.VideoMetadataRepository
import com.retrotube.app.network.NetworkShareRepository
import com.retrotube.app.network.SmbBrowser
import com.retrotube.app.network.SmbClient
import com.retrotube.app.progress.PlaybackProgressRepository
import jcifs.CIFSContext

/**
 * Resolves the user's own programmed channels (see [TvChannelConfigRepository])
 * into actual playable [TvChannel]s, expanding each channel's sources in the
 * order they were added -- "add this folder, then that one" plays in that
 * order without hand-ordering every episode. What's persisted *here* is where
 * each channel currently is, so flipping back to a channel later picks up
 * roughly where it left off instead of re-rolling from the start (or, worse,
 * a random point that could be a spoiler for a show you're mid-way through).
 *
 * A definition with a [TvChannelSource.SmbFolder] source needs real network
 * I/O to resolve (see [hasNetworkSource]) -- the caller is expected to resolve
 * those off the main thread with its own timeout, the same way the old
 * standalone "Network" channel worked, since a slow or unreachable share
 * should only cost that one channel rather than blocking TV Mode's launch.
 */
class TvChannelRepository(
    private val context: Context,
    private val collectionRepository: CollectionRepository,
    private val progressRepository: PlaybackProgressRepository,
    private val metadataRepository: VideoMetadataRepository,
) {
    private val prefs = context.getSharedPreferences("retrotube_tv_mode", Context.MODE_PRIVATE)

    fun hasNetworkSource(definition: TvChannelDefinition): Boolean =
        definition.sources.any { it is TvChannelSource.SmbFolder }

    /** Expands every source in [definition] into one flat video list, in source
     *  order. Local-only definitions are safe on the main thread; a definition
     *  with any [TvChannelSource.SmbFolder] source does real network I/O and
     *  must be called off it (see [hasNetworkSource]). Returns null if nothing
     *  resolved to any videos. */
    fun resolveChannel(definition: TvChannelDefinition): TvChannel? {
        val videos = definition.sources.flatMap { expandSource(it) }
        if (videos.isEmpty()) return null
        return TvChannel(id = definition.id, number = 0, videos = videos)
    }

    private fun expandSource(source: TvChannelSource): List<TvChannelVideo> = when (source) {
        is TvChannelSource.LocalFolder -> expandLocalFolder(source)
        is TvChannelSource.Collection -> expandCollection(source)
        is TvChannelSource.Video -> listOf(toChannelVideo(Uri.parse(source.uri), source.displayName))
        is TvChannelSource.SmbFolder -> expandSmbFolder(source)
    }

    private fun expandLocalFolder(source: TvChannelSource.LocalFolder): List<TvChannelVideo> {
        val root = runCatching { DocumentFile.fromTreeUri(context, Uri.parse(source.treeUri)) }.getOrNull()
            ?: return emptyList()
        val out = mutableListOf<DocumentFile>()
        collectLocalVideosRecursively(root, out)
        return out.sortedBy { (it.name ?: "").lowercase() }
            .map { toChannelVideo(it.uri, it.name ?: "Untitled") }
    }

    private fun collectLocalVideosRecursively(folder: DocumentFile, out: MutableList<DocumentFile>) {
        val children = runCatching { folder.listFiles() }.getOrDefault(emptyArray())
        for (child in children) {
            when {
                child.isDirectory -> collectLocalVideosRecursively(child, out)
                child.isFile && (child.type?.startsWith("video/") == true) -> out.add(child)
            }
        }
    }

    private fun expandCollection(source: TvChannelSource.Collection): List<TvChannelVideo> =
        collectionRepository.get(source.collectionId)?.videoUris.orEmpty()
            .map { toChannelVideo(Uri.parse(it), it.substringAfterLast('/')) }

    private fun expandSmbFolder(source: TvChannelSource.SmbFolder): List<TvChannelVideo> {
        val share = NetworkShareRepository(context).get(source.shareId) ?: return emptyList()
        // One context (and so one SMB session) reused for every folder in this
        // source's tree -- a fresh one per call re-negotiates the session from
        // scratch, which dominates the cost of listing a deep tree one call at
        // a time.
        val cifsContext = SmbClient.contextFor(share)
        val out = mutableListOf<TvChannelVideo>()
        collectSmbVideosRecursively(share, source.relativePath, cifsContext, out)
        return out
    }

    private fun collectSmbVideosRecursively(
        share: com.retrotube.app.network.NetworkShare,
        relativePath: String,
        context: CIFSContext,
        out: MutableList<TvChannelVideo>,
    ) {
        val children = runCatching { SmbBrowser.listChildren(share, relativePath, context) }.getOrDefault(emptyList())
        for (child in children) {
            when (child) {
                is LibraryItem.SmbFolderItem -> collectSmbVideosRecursively(share, child.relativePath, context, out)
                is LibraryItem.SmbVideoItem -> out.add(toChannelVideo(child.uri, child.name))
                else -> Unit
            }
        }
    }

    private fun toChannelVideo(uri: Uri, rawName: String): TvChannelVideo {
        val uriString = uri.toString()
        val displayName = metadataRepository.getCustomTitle(uriString) ?: cleanupName(rawName)
        return TvChannelVideo(uri, displayName)
    }

    private fun cleanupName(rawName: String): String =
        rawName
            .substringBeforeLast('.')
            .replace('_', ' ')
            .replace('.', ' ')
            .replace(Regex("\\s+"), " ")
            .trim()

    /** Where a channel currently is. A fresh channel (no saved position yet) starts
     *  at the furthest-along video with any saved watch progress, rather than
     *  always episode 1 -- if you're already partway through a show, TV Mode
     *  shouldn't act like you never started it. */
    fun getCurrentIndex(channel: TvChannel): Int {
        val saved = prefs.getInt(indexKey(channel.id), -1)
        if (saved in channel.videos.indices) return saved

        val lastWatchedIndex = channel.videos.indexOfLast { progressRepository.getProgress(it.uri.toString()) != null }
        return if (lastWatchedIndex >= 0) lastWatchedIndex else 0
    }

    /** Resets to 0 whenever the index actually changes -- a new video within the
     *  channel has nothing to resume, only flipping back to the same one does. */
    fun setCurrentIndex(channelId: String, index: Int) {
        val previous = prefs.getInt(indexKey(channelId), -1)
        prefs.edit().apply {
            putInt(indexKey(channelId), index)
            if (previous != index) putLong(positionKey(channelId), 0L)
        }.apply()
    }

    /** Mid-video position within a channel's current video -- separate from
     *  [PlaybackProgressRepository] entirely, so flipping between channels never
     *  touches Continue Watching, but flipping back to a channel you tuned away
     *  from still picks up where you left off instead of restarting the episode. */
    fun getSavedPositionMs(channelId: String): Long = prefs.getLong(positionKey(channelId), 0L)

    fun setSavedPositionMs(channelId: String, positionMs: Long) {
        prefs.edit().putLong(positionKey(channelId), positionMs).apply()
    }

    fun getLastChannelId(): String? = prefs.getString("last_channel_id", null)

    fun setLastChannelId(channelId: String) {
        prefs.edit().putString("last_channel_id", channelId).apply()
    }

    private fun indexKey(channelId: String) = "channel_${channelId}_index"
    private fun positionKey(channelId: String) = "channel_${channelId}_position_ms"
}
