package com.retrotube.app.tv

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** A user-programmed channel definition -- just an ordered list of sources,
 *  resolved into an actual [TvChannel] (with real videos) by
 *  [TvChannelRepository] at playback time. Channels are identified purely by
 *  their position in [TvChannelConfigRepository.getChannels] (CH 1, CH 2,
 *  ...), never a name -- there's no field here to rename. */
data class TvChannelDefinition(
    val id: String,
    val sources: List<TvChannelSource>,
    /** Optional, user-entered -- shown as the row subtitle alongside the
     *  source count. Channels still have no *name*, only a position/number;
     *  this is purely descriptive ("late-night anime," "background noise"). */
    val description: String? = null,
)

/**
 * Persists the user's own TV Mode channel setup -- just an ordered list the
 * user builds themselves, no auto-derivation from folders. Stored as one JSON
 * blob -- small, nested, and optional-field-heavy enough that a delimited-
 * string convention would need its own escaping scheme for no real benefit.
 */
class TvChannelConfigRepository(context: Context) {

    private val prefs = context.getSharedPreferences("retrotube_tv_channels", Context.MODE_PRIVATE)

    /** CH 01 always exists, even on a brand-new install -- there's no setup
     *  wizard gate before TV Mode; the lineup just starts with one empty,
     *  non-deletable channel the user builds onto (same seed-on-read pattern
     *  as [com.retrotube.app.bucket.BucketRepository]'s TV/Movies buckets). */
    fun getChannels(): List<TvChannelDefinition> {
        val raw = prefs.getString(KEY_CHANNELS, null)
        if (raw == null) {
            val seeded = listOf(TvChannelDefinition(id = UUID.randomUUID().toString(), sources = emptyList()))
            saveChannels(seeded)
            return seeded
        }
        val array = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        val parsed = (0 until array.length()).mapNotNull { i -> parseChannel(array.optJSONObject(i)) }
        if (parsed.isEmpty()) {
            val seeded = listOf(TvChannelDefinition(id = UUID.randomUUID().toString(), sources = emptyList()))
            saveChannels(seeded)
            return seeded
        }
        return parsed
    }

    fun saveChannels(channels: List<TvChannelDefinition>) {
        val array = JSONArray()
        channels.forEach { array.put(it.toJson()) }
        prefs.edit().putString(KEY_CHANNELS, array.toString()).apply()
    }

    /** Always appends -- the new channel becomes whatever the next sequential
     *  number is (channel count + 1), never named or numbered by the caller. */
    fun addChannel(): TvChannelDefinition {
        val channel = TvChannelDefinition(id = UUID.randomUUID().toString(), sources = emptyList())
        saveChannels(getChannels() + channel)
        return channel
    }

    /** CH 01 always exists -- deleting it would leave the whole lineup unable
     *  to boot into a first channel, so the channel currently in that first
     *  position is the one delete refuses, not a fixed id (deleting anywhere
     *  else can still promote a different channel into position one via
     *  reordering, same as before). No-op if [channelId] is that first channel. */
    fun deleteChannel(channelId: String) {
        val channels = getChannels()
        if (channels.firstOrNull()?.id == channelId) return
        saveChannels(channels.filterNot { it.id == channelId })
    }

    fun setDescription(channelId: String, description: String?) {
        saveChannels(
            getChannels().map { if (it.id == channelId) it.copy(description = description?.ifBlank { null }) else it },
        )
    }

    fun reorderChannels(orderedIds: List<String>) {
        val byId = getChannels().associateBy { it.id }
        saveChannels(orderedIds.mapNotNull { byId[it] })
    }

    fun addSource(channelId: String, source: TvChannelSource) {
        saveChannels(
            getChannels().map { if (it.id == channelId) it.copy(sources = it.sources + source) else it },
        )
    }

    fun removeSource(channelId: String, sourceIndex: Int) {
        saveChannels(
            getChannels().map {
                if (it.id == channelId) it.copy(sources = it.sources.filterIndexed { i, _ -> i != sourceIndex }) else it
            },
        )
    }

    /** Individual videos are added one at a time from a checkable list (see
     *  TvChannelPickVideosActivity) -- toggling the same video twice is a
     *  no-op rather than a duplicate source. */
    fun addVideoSourceIfAbsent(channelId: String, uri: String, displayName: String) {
        val channels = getChannels()
        val channel = channels.firstOrNull { it.id == channelId } ?: return
        if (channel.sources.any { it is TvChannelSource.Video && it.uri == uri }) return
        saveChannels(
            channels.map { if (it.id == channelId) it.copy(sources = it.sources + TvChannelSource.Video(uri, displayName)) else it },
        )
    }

    fun removeVideoSource(channelId: String, uri: String) {
        saveChannels(
            getChannels().map {
                if (it.id == channelId) {
                    it.copy(sources = it.sources.filterNot { source -> source is TvChannelSource.Video && source.uri == uri })
                } else {
                    it
                }
            },
        )
    }

    private fun TvChannelDefinition.toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        val sourcesArray = JSONArray()
        sources.forEach { sourcesArray.put(it.toJson()) }
        put("sources", sourcesArray)
        if (description != null) put("description", description)
    }

    private fun parseChannel(json: JSONObject?): TvChannelDefinition? {
        if (json == null) return null
        val id = json.optString("id").ifEmpty { return null }
        val sourcesArray = json.optJSONArray("sources") ?: JSONArray()
        val sources = (0 until sourcesArray.length()).mapNotNull { i ->
            TvChannelSource.fromJson(sourcesArray.optJSONObject(i) ?: return@mapNotNull null)
        }
        val description = json.optString("description").ifEmpty { null }
        return TvChannelDefinition(id, sources, description)
    }

    companion object {
        private const val KEY_CHANNELS = "channel_definitions"
    }
}
