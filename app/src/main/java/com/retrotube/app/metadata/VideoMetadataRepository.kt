package com.retrotube.app.metadata

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import java.io.File
import java.io.FileOutputStream

/**
 * User-set overrides layered on top of whatever we can derive automatically,
 * plus everything the TMDB metadata-matching engine fills in -- all keyed by
 * the video's content URI string. Absence of a value just means "use the
 * automatic/default one", everywhere this is read.
 *
 * Every field added after the original title/thumbnail/tags trio reads back
 * as null (or a named default, e.g. subtitle speed defaulting to 1.0x) for
 * any video that predates it -- `SharedPreferences.getX(key, default)` always
 * returns that default for a missing key rather than crashing or requiring a
 * migration, so no special handling is needed for old saved data here.
 */
class VideoMetadataRepository(private val context: Context) {

    private val prefs = context.getSharedPreferences("retrotube_video_metadata", Context.MODE_PRIVATE)
    private val thumbnailDir = File(context.filesDir, "custom_thumbnails").apply { mkdirs() }
    private val backdropDir = File(context.filesDir, "video_backdrops").apply { mkdirs() }

    fun getCustomTitle(videoUri: String): String? = prefs.getString(titleKey(videoUri), null)

    fun setCustomTitle(videoUri: String, title: String) {
        prefs.edit().putString(titleKey(videoUri), title).apply()
    }

    fun clearCustomTitle(videoUri: String) {
        prefs.edit().remove(titleKey(videoUri)).apply()
    }

    /** A RecyclerView rebinds this on every scroll past the same row -- cached in memory
     *  (shared across every repository instance, not per-instance) so that doesn't mean
     *  re-decoding the same PNG from disk each time. */
    fun getCustomThumbnail(videoUri: String): Bitmap? {
        thumbnailCache.get(videoUri)?.let { return it }
        val path = prefs.getString(thumbnailKey(videoUri), null) ?: return null
        val bitmap = runCatching { BitmapFactory.decodeFile(path) }.getOrNull() ?: return null
        thumbnailCache.put(videoUri, bitmap)
        return bitmap
    }

    fun setCustomThumbnail(videoUri: String, bitmap: Bitmap) {
        val file = File(thumbnailDir, "${videoUri.hashCode()}.png")
        FileOutputStream(file).use { out -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, out) }
        prefs.edit().putString(thumbnailKey(videoUri), file.absolutePath).apply()
        thumbnailCache.put(videoUri, bitmap)
    }

    fun clearCustomThumbnail(videoUri: String) {
        prefs.getString(thumbnailKey(videoUri), null)?.let { runCatching { File(it).delete() } }
        prefs.edit().remove(thumbnailKey(videoUri)).apply()
        thumbnailCache.remove(videoUri)
    }

    fun getTags(videoUri: String): List<String> =
        prefs.getString(tagsKey(videoUri), null)
            ?.split(",")
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?: emptyList()

    fun setTags(videoUri: String, tags: List<String>) {
        val cleaned = tags.map { it.trim() }.filter { it.isNotEmpty() }
        if (cleaned.isEmpty()) {
            prefs.edit().remove(tagsKey(videoUri)).apply()
        } else {
            prefs.edit().putString(tagsKey(videoUri), cleaned.joinToString(",")).apply()
        }
    }

    // --- TMDB-scraped fields (metadata-matching engine) ---

    /** Whether this video has ever been matched (by TMDB id) or has a user-set
     *  custom title -- the fill-blanks-only rule's entire decision is "does
     *  this already have a non-null custom title", so this alone is what a
     *  normal (non-forced) scrape pass checks before touching anything. */
    fun getTmdbId(videoUri: String): Int? = prefs.getInt(tmdbIdKey(videoUri), -1).takeIf { it != -1 }

    fun setTmdbId(videoUri: String, id: Int) {
        prefs.edit().putInt(tmdbIdKey(videoUri), id).apply()
    }

    fun getYear(videoUri: String): Int? = prefs.getInt(yearKey(videoUri), -1).takeIf { it != -1 }

    fun setYear(videoUri: String, year: Int) {
        prefs.edit().putInt(yearKey(videoUri), year).apply()
    }

    fun getOverview(videoUri: String): String? = prefs.getString(overviewKey(videoUri), null)

    fun setOverview(videoUri: String, overview: String) {
        prefs.edit().putString(overviewKey(videoUri), overview).apply()
    }

    fun getGenres(videoUri: String): List<String> =
        prefs.getString(genresKey(videoUri), null)
            ?.split("|")
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?: emptyList()

    fun setGenres(videoUri: String, genres: List<String>) {
        prefs.edit().putString(genresKey(videoUri), genres.joinToString("|")).apply()
    }

    fun getRating(videoUri: String): Float? =
        if (prefs.contains(ratingKey(videoUri))) prefs.getFloat(ratingKey(videoUri), 0f) else null

    fun setRating(videoUri: String, rating: Float) {
        prefs.edit().putFloat(ratingKey(videoUri), rating).apply()
    }

    fun getSeasonNumber(videoUri: String): Int? = prefs.getInt(seasonKey(videoUri), -1).takeIf { it != -1 }

    fun setSeasonNumber(videoUri: String, season: Int) {
        prefs.edit().putInt(seasonKey(videoUri), season).apply()
    }

    fun getEpisodeNumber(videoUri: String): Int? = prefs.getInt(episodeKey(videoUri), -1).takeIf { it != -1 }

    fun setEpisodeNumber(videoUri: String, episode: Int) {
        prefs.edit().putInt(episodeKey(videoUri), episode).apply()
    }

    fun getAirDate(videoUri: String): String? = prefs.getString(airDateKey(videoUri), null)

    fun setAirDate(videoUri: String, airDate: String) {
        prefs.edit().putString(airDateKey(videoUri), airDate).apply()
    }

    /** Downloaded at w780 (see the TMDB client), not the w342 poster size --
     *  a movie's backdrop renders full-bleed across its whole detail screen. */
    fun getBackdrop(videoUri: String): Bitmap? {
        val path = prefs.getString(backdropKey(videoUri), null) ?: return null
        return runCatching { BitmapFactory.decodeFile(path) }.getOrNull()
    }

    fun setBackdrop(videoUri: String, bitmap: Bitmap) {
        val file = File(backdropDir, "${videoUri.hashCode()}.png")
        FileOutputStream(file).use { out -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, out) }
        prefs.edit().putString(backdropKey(videoUri), file.absolutePath).apply()
    }

    /** One-time migration flag: has this video's backdrop already been re-downloaded
     *  at the larger size, so the library-wide Refresh Library pass's movie-backdrop
     *  backfill doesn't repeat the download every single pass. */
    fun isBackdropUpgraded(videoUri: String): Boolean = prefs.getBoolean(backdropUpgradedKey(videoUri), false)

    fun setBackdropUpgraded(videoUri: String, upgraded: Boolean) {
        prefs.edit().putBoolean(backdropUpgradedKey(videoUri), upgraded).apply()
    }

    fun getRuntimeMinutes(videoUri: String): Int? = prefs.getInt(runtimeKey(videoUri), -1).takeIf { it != -1 }

    fun setRuntimeMinutes(videoUri: String, minutes: Int) {
        prefs.edit().putInt(runtimeKey(videoUri), minutes).apply()
    }

    fun getTagline(videoUri: String): String? = prefs.getString(taglineKey(videoUri), null)

    fun setTagline(videoUri: String, tagline: String) {
        prefs.edit().putString(taglineKey(videoUri), tagline).apply()
    }

    /** Hides a video from the Movies/bucket grids without touching the file or
     *  its tagged folder -- distinct from deleting or untagging. */
    fun isExcludedFromLibrary(videoUri: String): Boolean = prefs.getBoolean(excludedKey(videoUri), false)

    fun setExcludedFromLibrary(videoUri: String, excluded: Boolean) {
        prefs.edit().putBoolean(excludedKey(videoUri), excluded).apply()
    }

    // --- Subtitle sync/size, per-video (a delay/speed pairing is tied to one
    // specific file/release, not a global preference) ---

    fun getSubtitleDelaySeconds(videoUri: String): Float = prefs.getFloat(subtitleDelayKey(videoUri), 0f)

    fun setSubtitleDelaySeconds(videoUri: String, delaySeconds: Float) {
        prefs.edit().putFloat(subtitleDelayKey(videoUri), delaySeconds).apply()
    }

    fun getSubtitleSpeedMultiplier(videoUri: String): Float = prefs.getFloat(subtitleSpeedKey(videoUri), 1.0f)

    fun setSubtitleSpeedMultiplier(videoUri: String, speedMultiplier: Float) {
        prefs.edit().putFloat(subtitleSpeedKey(videoUri), speedMultiplier).apply()
    }

    private fun titleKey(videoUri: String) = "title_$videoUri"
    private fun thumbnailKey(videoUri: String) = "thumb_$videoUri"
    private fun tagsKey(videoUri: String) = "tags_$videoUri"
    private fun tmdbIdKey(videoUri: String) = "tmdb_id_$videoUri"
    private fun yearKey(videoUri: String) = "year_$videoUri"
    private fun overviewKey(videoUri: String) = "overview_$videoUri"
    private fun genresKey(videoUri: String) = "genres_$videoUri"
    private fun ratingKey(videoUri: String) = "rating_$videoUri"
    private fun seasonKey(videoUri: String) = "season_$videoUri"
    private fun episodeKey(videoUri: String) = "episode_$videoUri"
    private fun airDateKey(videoUri: String) = "air_date_$videoUri"
    private fun backdropKey(videoUri: String) = "backdrop_$videoUri"
    private fun backdropUpgradedKey(videoUri: String) = "backdrop_upgraded_$videoUri"
    private fun runtimeKey(videoUri: String) = "runtime_$videoUri"
    private fun taglineKey(videoUri: String) = "tagline_$videoUri"
    private fun excludedKey(videoUri: String) = "excluded_$videoUri"
    private fun subtitleDelayKey(videoUri: String) = "subtitle_delay_$videoUri"
    private fun subtitleSpeedKey(videoUri: String) = "subtitle_speed_$videoUri"

    companion object {
        private val thumbnailCache = LruCache<String, Bitmap>(64)
    }
}
