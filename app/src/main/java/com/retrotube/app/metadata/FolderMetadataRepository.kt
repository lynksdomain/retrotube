package com.retrotube.app.metadata

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import java.io.File
import java.io.FileOutputStream

/**
 * TMDB-scraped (or user-overridden) metadata for a tagged show folder --
 * distinct from [VideoMetadataRepository], which is per-video. A folder's own
 * title/poster/overview only matter once it's tagged as a show (see
 * [com.retrotube.app.library.TaggedFolderRepository]); an untagged folder has
 * none of this and isn't expected to.
 */
class FolderMetadataRepository(private val context: Context) {

    private val prefs = context.getSharedPreferences("retrotube_folder_metadata", Context.MODE_PRIVATE)
    private val posterDir = File(context.filesDir, "folder_posters").apply { mkdirs() }
    private val backdropDir = File(context.filesDir, "folder_backdrops").apply { mkdirs() }

    fun getCustomTitle(folderKey: String): String? = prefs.getString(titleKey(folderKey), null)

    fun setCustomTitle(folderKey: String, title: String) {
        prefs.edit().putString(titleKey(folderKey), title).apply()
    }

    fun getPoster(folderKey: String): Bitmap? = cachedBitmap("poster_$folderKey") {
        prefs.getString(posterKey(folderKey), null)
    }

    fun setPoster(folderKey: String, bitmap: Bitmap) {
        val file = File(posterDir, "${folderKey.hashCode()}.png")
        FileOutputStream(file).use { out -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, out) }
        prefs.edit().putString(posterKey(folderKey), file.absolutePath).apply()
        bitmapCache.put("poster_$folderKey", bitmap)
    }

    /** Downloaded at a larger size than the poster (w780 vs. w342, see the TMDB
     *  client) -- a backdrop renders full-bleed across a whole detail screen,
     *  several times wider on screen than a poster grid tile. */
    fun getBackdrop(folderKey: String): Bitmap? = cachedBitmap("backdrop_$folderKey") {
        prefs.getString(backdropKey(folderKey), null)
    }

    fun setBackdrop(folderKey: String, bitmap: Bitmap) {
        val file = File(backdropDir, "${folderKey.hashCode()}.png")
        FileOutputStream(file).use { out -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, out) }
        prefs.edit().putString(backdropKey(folderKey), file.absolutePath).apply()
        bitmapCache.put("backdrop_$folderKey", bitmap)
    }

    /** A season's own poster (TMDB has one per season, distinct from the show's
     *  own poster) -- falls back to the show poster at the call site if a
     *  season never got one (e.g. TMDB has no season-specific art for it). */
    fun getSeasonPoster(folderKey: String, seasonNumber: Int): Bitmap? =
        cachedBitmap("season_poster_${folderKey}_$seasonNumber") {
            prefs.getString(seasonPosterKey(folderKey, seasonNumber), null)
        }

    fun setSeasonPoster(folderKey: String, seasonNumber: Int, bitmap: Bitmap) {
        val file = File(posterDir, "${folderKey.hashCode()}_s$seasonNumber.png")
        FileOutputStream(file).use { out -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, out) }
        prefs.edit().putString(seasonPosterKey(folderKey, seasonNumber), file.absolutePath).apply()
        bitmapCache.put("season_poster_${folderKey}_$seasonNumber", bitmap)
    }

    /** Every poster/backdrop grid (Library buckets, Bucket Content, Show Detail's
     *  season grid) rebinds its whole adapter on every refresh (`notifyDataSetChanged`,
     *  not `DiffUtil`), which without this decoded the same PNG off disk on every
     *  single rebind -- a real, avoidable cost repeated on every scroll/refresh,
     *  same reasoning as [com.retrotube.app.metadata.VideoMetadataRepository]'s own
     *  thumbnail cache. */
    private fun cachedBitmap(cacheKey: String, pathProvider: () -> String?): Bitmap? {
        bitmapCache.get(cacheKey)?.let { return it }
        val path = pathProvider() ?: return null
        val bitmap = runCatching { BitmapFactory.decodeFile(path) }.getOrNull() ?: return null
        bitmapCache.put(cacheKey, bitmap)
        return bitmap
    }

    fun getTmdbShowId(folderKey: String): Int? = prefs.getInt(tmdbIdKey(folderKey), -1).takeIf { it != -1 }

    fun setTmdbShowId(folderKey: String, id: Int) {
        prefs.edit().putInt(tmdbIdKey(folderKey), id).apply()
    }

    fun clearTmdbShowId(folderKey: String) {
        prefs.edit().remove(tmdbIdKey(folderKey)).apply()
    }

    fun getOverview(folderKey: String): String? = prefs.getString(overviewKey(folderKey), null)

    fun setOverview(folderKey: String, overview: String) {
        prefs.edit().putString(overviewKey(folderKey), overview).apply()
    }

    fun getGenres(folderKey: String): List<String> =
        prefs.getString(genresKey(folderKey), null)
            ?.split("|")
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?: emptyList()

    fun setGenres(folderKey: String, genres: List<String>) {
        prefs.edit().putString(genresKey(folderKey), genres.joinToString("|")).apply()
    }

    fun getRating(folderKey: String): Float? =
        if (prefs.contains(ratingKey(folderKey))) prefs.getFloat(ratingKey(folderKey), 0f) else null

    fun setRating(folderKey: String, rating: Float) {
        prefs.edit().putFloat(ratingKey(folderKey), rating).apply()
    }

    private fun titleKey(folderKey: String) = "title_$folderKey"
    private fun posterKey(folderKey: String) = "poster_$folderKey"
    private fun seasonPosterKey(folderKey: String, seasonNumber: Int) = "poster_${folderKey}_season_$seasonNumber"
    private fun backdropKey(folderKey: String) = "backdrop_$folderKey"
    private fun tmdbIdKey(folderKey: String) = "tmdb_id_$folderKey"
    private fun overviewKey(folderKey: String) = "overview_$folderKey"
    private fun genresKey(folderKey: String) = "genres_$folderKey"
    private fun ratingKey(folderKey: String) = "rating_$folderKey"

    companion object {
        private val bitmapCache = LruCache<String, Bitmap>(64)
    }
}
