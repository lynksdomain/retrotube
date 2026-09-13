package com.retrotube.app.metadata.tmdb

import com.retrotube.app.BuildConfig
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class TmdbSearchResult(
    val id: Int,
    val mediaType: String, // "movie" or "tv"
    val title: String,
    val year: Int?,
    val posterPath: String?,
)

data class SeasonSummary(
    val seasonNumber: Int,
    val episodeCount: Int,
    val name: String?,
    val posterPath: String?,
)

data class TmdbShowDetails(
    val id: Int,
    val name: String,
    val overview: String?,
    val genres: List<String>,
    val rating: Float?,
    val posterPath: String?,
    val backdropPath: String?,
    val seasons: List<SeasonSummary>,
)

data class TmdbMovieDetails(
    val id: Int,
    val title: String,
    val year: Int?,
    val overview: String?,
    val genres: List<String>,
    val rating: Float?,
    val posterPath: String?,
    val backdropPath: String?,
    val runtimeMinutes: Int?,
    val tagline: String?,
)

data class TmdbSeasonDetails(val name: String?, val overview: String?, val posterPath: String?)

data class TmdbEpisodeDetails(
    val name: String?,
    val overview: String?,
    val stillPath: String?,
    val rating: Float?,
    val airDate: String?,
)

/**
 * Thin TMDB v3 REST client over plain `HttpURLConnection` + `org.json` --
 * consistent with the rest of this app's dependency-light approach (its own
 * GLSL shaders, its own SMB client via jcifs-ng directly) rather than pulling
 * in Retrofit/OkHttp for a handful of simple GET endpoints.
 *
 * Every call here is real network I/O and must run off the main thread, same
 * convention as [com.retrotube.app.network.SmbClient]/`SmbBrowser`.
 */
object TmdbClient {

    private const val BASE_URL = "https://api.themoviedb.org/3"
    private const val IMAGE_BASE_URL = "https://image.tmdb.org/t/p"

    /** Posters render inside a poster-grid tile; a backdrop renders full-bleed
     *  across an entire detail screen -- several times wider on screen than a
     *  poster grid tile, so it needs a genuinely larger source size or every
     *  device visibly upscales/blurs it. Don't use one size for both. */
    private const val POSTER_SIZE = "w342"
    private const val BACKDROP_SIZE = "w780"

    fun posterUrl(path: String?): String? = path?.let { "$IMAGE_BASE_URL/$POSTER_SIZE$it" }
    fun backdropUrl(path: String?): String? = path?.let { "$IMAGE_BASE_URL/$BACKDROP_SIZE$it" }

    fun isConfigured(): Boolean = BuildConfig.TMDB_API_KEY.isNotBlank()

    fun searchMulti(query: String): List<TmdbSearchResult> {
        val json = get("/search/multi", mapOf("query" to query)) ?: return emptyList()
        val results = json.optJSONArray("results") ?: return emptyList()
        return (0 until results.length()).mapNotNull { i ->
            val item = results.optJSONObject(i) ?: return@mapNotNull null
            val mediaType = item.optString("media_type")
            if (mediaType != "movie" && mediaType != "tv") return@mapNotNull null
            val title = if (mediaType == "movie") item.optString("title") else item.optString("name")
            val dateField = if (mediaType == "movie") "release_date" else "first_air_date"
            TmdbSearchResult(
                id = item.optInt("id"),
                mediaType = mediaType,
                title = title,
                year = item.optString(dateField).take(4).toIntOrNull(),
                posterPath = item.optString("poster_path").ifEmpty { null },
            )
        }
    }

    fun getShowDetails(showId: Int): TmdbShowDetails? {
        val json = get("/tv/$showId", emptyMap()) ?: return null
        val genres = jsonArrayNames(json, "genres")
        val seasons = json.optJSONArray("seasons")?.let { array ->
            (0 until array.length()).mapNotNull { i ->
                val season = array.optJSONObject(i) ?: return@mapNotNull null
                val seasonNumber = season.optInt("season_number", -1)
                if (seasonNumber < 0) return@mapNotNull null
                SeasonSummary(
                    seasonNumber = seasonNumber,
                    episodeCount = season.optInt("episode_count", 0),
                    name = season.optString("name").ifEmpty { null },
                    posterPath = season.optString("poster_path").ifEmpty { null },
                )
            }
        } ?: emptyList()
        return TmdbShowDetails(
            id = json.optInt("id"),
            name = json.optString("name"),
            overview = json.optString("overview").ifEmpty { null },
            genres = genres,
            rating = optRating(json),
            posterPath = json.optString("poster_path").ifEmpty { null },
            backdropPath = json.optString("backdrop_path").ifEmpty { null },
            seasons = seasons,
        )
    }

    fun getMovieDetails(movieId: Int): TmdbMovieDetails? {
        val json = get("/movie/$movieId", emptyMap()) ?: return null
        return TmdbMovieDetails(
            id = json.optInt("id"),
            title = json.optString("title"),
            year = json.optString("release_date").take(4).toIntOrNull(),
            overview = json.optString("overview").ifEmpty { null },
            genres = jsonArrayNames(json, "genres"),
            rating = optRating(json),
            posterPath = json.optString("poster_path").ifEmpty { null },
            backdropPath = json.optString("backdrop_path").ifEmpty { null },
            runtimeMinutes = json.optInt("runtime", -1).takeIf { it >= 0 },
            tagline = json.optString("tagline").ifEmpty { null },
        )
    }

    fun getSeasonDetails(showId: Int, seasonNumber: Int): TmdbSeasonDetails? {
        val json = get("/tv/$showId/season/$seasonNumber", emptyMap()) ?: return null
        return TmdbSeasonDetails(
            name = json.optString("name").ifEmpty { null },
            overview = json.optString("overview").ifEmpty { null },
            posterPath = json.optString("poster_path").ifEmpty { null },
        )
    }

    fun getEpisodeDetails(showId: Int, seasonNumber: Int, episodeNumber: Int): TmdbEpisodeDetails? {
        val json = get("/tv/$showId/season/$seasonNumber/episode/$episodeNumber", emptyMap()) ?: return null
        return TmdbEpisodeDetails(
            name = json.optString("name").ifEmpty { null },
            overview = json.optString("overview").ifEmpty { null },
            stillPath = json.optString("still_path").ifEmpty { null },
            rating = optRating(json),
            airDate = json.optString("air_date").ifEmpty { null },
        )
    }

    private fun jsonArrayNames(json: JSONObject, field: String): List<String> =
        json.optJSONArray(field)?.let { array ->
            (0 until array.length()).mapNotNull { array.optJSONObject(it)?.optString("name")?.ifEmpty { null } }
        } ?: emptyList()

    private fun optRating(json: JSONObject): Float? =
        json.optDouble("vote_average", -1.0).takeIf { it >= 0 }?.toFloat()

    private fun get(path: String, params: Map<String, String>): JSONObject? {
        if (!isConfigured()) return null
        val query = buildString {
            append("api_key=").append(urlEncode(BuildConfig.TMDB_API_KEY))
            for ((key, value) in params) {
                append('&').append(urlEncode(key)).append('=').append(urlEncode(value))
            }
        }
        val connection = URL("$BASE_URL$path?$query").openConnection() as HttpURLConnection
        return try {
            connection.connectTimeout = 8_000
            connection.readTimeout = 8_000
            connection.requestMethod = "GET"
            if (connection.responseCode != HttpURLConnection.HTTP_OK) return null
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            JSONObject(body)
        } catch (e: Exception) {
            null
        } finally {
            connection.disconnect()
        }
    }

    private fun urlEncode(value: String): String = URLEncoder.encode(value, "UTF-8")
}
