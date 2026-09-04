package com.retrotube.app.tv

import android.content.Context
import android.net.Uri
import com.retrotube.app.collections.CollectionRepository
import com.retrotube.app.library.LibraryRepository
import com.retrotube.app.metadata.VideoMetadataRepository
import com.retrotube.app.progress.PlaybackProgressRepository

/**
 * Builds TV Mode's channel list fresh every time -- there's no channel-editing
 * UI, channels are just a live view over folders and collections that already
 * exist. What *is* persisted is where each channel currently is, so flipping
 * back to a channel later picks up roughly where it left off instead of
 * re-rolling from the start (or, worse, a random point that could be a
 * spoiler for a show you're mid-way through).
 *
 * Shows and the Movies channel are drawn from the local (SAF) library only --
 * building the same view over an SMB share would mean recursively crawling
 * its whole folder tree on every TV Mode launch, which is real, repeated
 * network cost for something that's just background flavor. Collections
 * (and therefore the Wildcard pool) already include whatever's in them
 * regardless of scheme, so SMB content reaches TV Mode by way of a collection.
 */
class TvChannelRepository(
    context: Context,
    private val libraryRepository: LibraryRepository,
    private val collectionRepository: CollectionRepository,
    private val progressRepository: PlaybackProgressRepository,
    private val metadataRepository: VideoMetadataRepository,
) {
    private val prefs = context.getSharedPreferences("retrotube_tv_mode", Context.MODE_PRIVATE)

    fun getChannels(): List<TvChannel> {
        val allVideos = libraryRepository.getAllVideos()
        val byFolder = allVideos.groupBy { it.pathLabel }

        val showChannels = byFolder.entries
            .filter { it.value.size >= 2 }
            .sortedBy { it.key.lowercase() }
            .map { (pathLabel, entries) ->
                val videos = entries
                    .sortedBy { it.name.lowercase() }
                    .map { toChannelVideo(it.document.uri, it.name) }
                TvChannel(
                    id = "show:$pathLabel",
                    number = 0,
                    name = pathLabel.substringAfterLast('/'),
                    videos = videos,
                )
            }

        val collectionChannels = collectionRepository.getAll()
            .filter { it.videoUris.isNotEmpty() }
            .sortedBy { it.name.lowercase() }
            .map { collection ->
                TvChannel(
                    id = "collection:${collection.id}",
                    number = 0,
                    name = collection.name,
                    videos = collection.videoUris.map { toChannelVideo(Uri.parse(it), it.substringAfterLast('/')) },
                )
            }

        val movieEntries = byFolder.values.filter { it.size == 1 }.flatten()
        val moviesChannel = if (movieEntries.isEmpty()) {
            emptyList()
        } else {
            listOf(
                TvChannel(
                    id = "movies",
                    number = 0,
                    name = "Movies",
                    videos = movieEntries.sortedBy { it.name.lowercase() }.map { toChannelVideo(it.document.uri, it.name) },
                ),
            )
        }

        val wildcardPool = (allVideos.map { it.document.uri.toString() to it.name } +
            collectionChannels.flatMap { channel -> channel.videos.map { it.uri.toString() to it.displayName } })
            .distinctBy { it.first }
        val wildcardChannel = if (wildcardPool.isEmpty()) {
            emptyList()
        } else {
            listOf(
                TvChannel(
                    id = "wildcard",
                    number = 0,
                    name = "Wildcard",
                    videos = wildcardPool.shuffled().map { toChannelVideo(Uri.parse(it.first), it.second) },
                ),
            )
        }

        return (showChannels + collectionChannels + moviesChannel + wildcardChannel)
            .filter { it.videos.isNotEmpty() }
            .mapIndexed { index, channel -> channel.copy(number = index + 1) }
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
