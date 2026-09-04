package com.retrotube.app.tv

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** A user-programmed channel definition -- just a name and an ordered list of
 *  sources, resolved into an actual [TvChannel] (with real videos) by
 *  [TvChannelRepository] at playback time. */
data class TvChannelDefinition(
    val id: String,
    val name: String,
    val sources: List<TvChannelSource>,
)

/**
 * Persists the user's own TV Mode channel setup -- replaces recomputing
 * channels fresh from folders/collections every launch. Every library looks
 * different, so instead of one fixed auto-derivation this is just an ordered
 * list the user builds themselves (optionally seeded from auto-detection by
 * [TvAutoSeeder] on first setup, see [PlayerActivity]/`TvSetupActivity`).
 * Stored as one JSON blob -- small, nested, and optional-field-heavy enough
 * that the delimited-string convention used elsewhere (see
 * [com.retrotube.app.collections.CollectionRepository]) would need its own
 * escaping scheme for no real benefit.
 */
class TvChannelConfigRepository(context: Context) {

    private val prefs = context.getSharedPreferences("retrotube_tv_channels", Context.MODE_PRIVATE)

    /** No config yet means this is the very first TV Mode launch -- the setup
     *  wizard should run before anything else. */
    fun isConfigured(): Boolean = prefs.contains(KEY_CHANNELS)

    fun getChannels(): List<TvChannelDefinition> {
        val raw = prefs.getString(KEY_CHANNELS, null) ?: return emptyList()
        val array = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        return (0 until array.length()).mapNotNull { i -> parseChannel(array.optJSONObject(i)) }
    }

    fun saveChannels(channels: List<TvChannelDefinition>) {
        val array = JSONArray()
        channels.forEach { array.put(it.toJson()) }
        prefs.edit().putString(KEY_CHANNELS, array.toString()).apply()
    }

    fun addChannel(name: String): TvChannelDefinition {
        val channel = TvChannelDefinition(id = UUID.randomUUID().toString(), name = name, sources = emptyList())
        saveChannels(getChannels() + channel)
        return channel
    }

    fun renameChannel(channelId: String, name: String) {
        saveChannels(getChannels().map { if (it.id == channelId) it.copy(name = name) else it })
    }

    fun deleteChannel(channelId: String) {
        saveChannels(getChannels().filterNot { it.id == channelId })
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
     *  TvChannelPickVideosActivity), same interaction as adding to a collection --
     *  so, like [com.retrotube.app.collections.CollectionRepository.addVideo],
     *  toggling the same video twice is a no-op rather than a duplicate source. */
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
        put("name", name)
        val sourcesArray = JSONArray()
        sources.forEach { sourcesArray.put(it.toJson()) }
        put("sources", sourcesArray)
    }

    private fun parseChannel(json: JSONObject?): TvChannelDefinition? {
        if (json == null) return null
        val id = json.optString("id").ifEmpty { return null }
        val name = json.optString("name").ifEmpty { return null }
        val sourcesArray = json.optJSONArray("sources") ?: JSONArray()
        val sources = (0 until sourcesArray.length()).mapNotNull { i ->
            TvChannelSource.fromJson(sourcesArray.optJSONObject(i) ?: return@mapNotNull null)
        }
        return TvChannelDefinition(id, name, sources)
    }

    companion object {
        private const val KEY_CHANNELS = "channel_definitions"
    }
}
