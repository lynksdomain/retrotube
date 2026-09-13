package com.retrotube.app.metadata.tmdb

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.retrotube.app.metadata.FolderMetadataRepository
import com.retrotube.app.metadata.VideoMetadataRepository
import java.net.HttpURLConnection
import java.net.URL

/** Just enough to match a video against TMDB -- deliberately not a
 *  DocumentFile/SAF/SMB type, so this engine stays decoupled from where the
 *  file actually lives. Callers (the tagging flow, a folder browser, a
 *  library-wide refresh) resolve their own video lists and pass these in. */
data class VideoRef(val uri: String, val filename: String)

data class ShowMatchOutcome(val matchedTitle: String?, val episodesMatched: Int, val found: Boolean)

/**
 * Orchestrates [FilenameParser] + [EpisodeReconciler] + [TmdbClient] +
 * [FillBlanksPolicy] against the actual per-video/per-folder metadata stores.
 * Every entry point here is real network I/O (TMDB requests, poster/backdrop
 * downloads) and must run off the main thread.
 */
class MatchingEngine(
    private val videoMetadataRepository: VideoMetadataRepository,
    private val folderMetadataRepository: FolderMetadataRepository,
) {

    /** A whole folder tagged "A Single TV Show" -- matches the folder itself
     *  against TMDB (once, cached as a pinned show id), then every video
     *  underneath it as one of that show's episodes. [forced] bypasses
     *  fill-blanks-only for every episode, matching Force Refresh's
     *  whole-folder scope (spec §5.6) -- there is no equivalent forced mode
     *  that *doesn't* also re-match the folder's own show identity, since a
     *  wrong show match is exactly the failure case Force Refresh exists to
     *  recover from (see FillBlanksPolicy's doc comment). */
    fun matchShowFolder(folderKey: String, videos: List<VideoRef>, forced: Boolean): ShowMatchOutcome {
        var tmdbShowId = folderMetadataRepository.getTmdbShowId(folderKey)
        val hasCustomTitle = folderMetadataRepository.getCustomTitle(folderKey) != null
        val needsShowMatch = tmdbShowId == null || (forced) || !hasCustomTitle

        if (needsShowMatch) {
            val searchTitle = videos.firstOrNull()?.let { FilenameParser.parse(it.filename).title }
                ?: return ShowMatchOutcome(null, 0, found = false)
            val topShow = TmdbClient.searchMulti(searchTitle).firstOrNull { it.mediaType == "tv" }
                ?: return ShowMatchOutcome(null, 0, found = false)
            tmdbShowId = topShow.id
        }
        val id = tmdbShowId ?: return ShowMatchOutcome(null, 0, found = false)
        val showDetails = TmdbClient.getShowDetails(id) ?: return ShowMatchOutcome(null, 0, found = false)

        folderMetadataRepository.setTmdbShowId(folderKey, id)
        folderMetadataRepository.setCustomTitle(folderKey, showDetails.name)
        showDetails.overview?.let { folderMetadataRepository.setOverview(folderKey, it) }
        folderMetadataRepository.setGenres(folderKey, showDetails.genres)
        showDetails.rating?.let { folderMetadataRepository.setRating(folderKey, it) }
        TmdbClient.posterUrl(showDetails.posterPath)?.let { url ->
            downloadBitmap(url)?.let { folderMetadataRepository.setPoster(folderKey, it) }
        }
        TmdbClient.backdropUrl(showDetails.backdropPath)?.let { url ->
            downloadBitmap(url)?.let { folderMetadataRepository.setBackdrop(folderKey, it) }
        }
        // Each season has its own poster on TMDB (already came back with the show
        // details, no extra request needed) -- distinct from the show's own poster,
        // which every season tile fell back to before this existed.
        for (season in showDetails.seasons) {
            TmdbClient.posterUrl(season.posterPath)?.let { url ->
                downloadBitmap(url)?.let { folderMetadataRepository.setSeasonPoster(folderKey, season.seasonNumber, it) }
            }
        }

        val seasonCounts = showDetails.seasons.map { SeasonEpisodeCounts(it.seasonNumber, it.episodeCount) }
        var episodesMatched = 0
        for (video in videos) {
            if (matchEpisode(video, id, seasonCounts, forced)) episodesMatched++
        }
        return ShowMatchOutcome(showDetails.name, episodesMatched, found = true)
    }

    /** One episode against an already-known show id + season list. Used both
     *  by [matchShowFolder]'s own loop and directly for a single-episode
     *  Force Refresh (spec §5.6's narrowest refresh scope). Returns whether it
     *  actually matched anything, purely so callers can report a count. */
    fun matchEpisode(video: VideoRef, tmdbShowId: Int, seasons: List<SeasonEpisodeCounts>, forced: Boolean): Boolean {
        val hasCustomTitle = videoMetadataRepository.getCustomTitle(video.uri) != null
        if (FillBlanksPolicy.decideForShowOrEpisode(hasCustomTitle, forced) == MatchAction.SKIP) return false

        val parsed = FilenameParser.parse(video.filename)
        val guessedEpisode = parsed.episode ?: return false
        val reconciled = EpisodeReconciler.reconcile(parsed.season, guessedEpisode, seasons) ?: return false
        val episodeDetails = TmdbClient.getEpisodeDetails(tmdbShowId, reconciled.season, reconciled.episode) ?: return false

        videoMetadataRepository.setTmdbId(video.uri, tmdbShowId)
        videoMetadataRepository.setCustomTitle(video.uri, episodeDetails.name ?: "Episode ${reconciled.episode}")
        videoMetadataRepository.setSeasonNumber(video.uri, reconciled.season)
        videoMetadataRepository.setEpisodeNumber(video.uri, reconciled.episode)
        episodeDetails.overview?.let { videoMetadataRepository.setOverview(video.uri, it) }
        episodeDetails.airDate?.let { videoMetadataRepository.setAirDate(video.uri, it) }
        episodeDetails.rating?.let { videoMetadataRepository.setRating(video.uri, it) }
        TmdbClient.posterUrl(episodeDetails.stillPath)?.let { url ->
            downloadBitmap(url)?.let { videoMetadataRepository.setCustomThumbnail(video.uri, it) }
        }
        return true
    }

    /** One movie file, matched independently of any other video (spec §5.6:
     *  movies only ever get a per-item Force Refresh or reached via the
     *  library-wide Refresh Library pass -- never a folder-level Force
     *  Refresh). [forced] always does a full fresh match; otherwise
     *  [FillBlanksPolicy] decides between skipping, a full match, or the
     *  independent runtime/tagline + backdrop-upgrade backfill. */
    fun matchMovie(video: VideoRef, forced: Boolean): Boolean {
        val hasCustomTitle = videoMetadataRepository.getCustomTitle(video.uri) != null
        val runtimeIsNull = videoMetadataRepository.getRuntimeMinutes(video.uri) == null
        val backdropUpgraded = videoMetadataRepository.isBackdropUpgraded(video.uri)
        val (action, needs) = FillBlanksPolicy.decideForMovie(hasCustomTitle, forced, runtimeIsNull, backdropUpgraded)

        return when (action) {
            MatchAction.SKIP -> false
            MatchAction.BACKFILL_ONLY -> backfillMovie(video, needs)
            MatchAction.FULL_MATCH -> fullyMatchMovie(video)
        }
    }

    private fun backfillMovie(video: VideoRef, needs: MovieBackfillNeeds?): Boolean {
        if (needs == null || !needs.hasAnyGap) return false
        val tmdbId = videoMetadataRepository.getTmdbId(video.uri) ?: return false
        val details = TmdbClient.getMovieDetails(tmdbId) ?: return false

        if (needs.needsRuntimeAndTagline) {
            details.runtimeMinutes?.let { videoMetadataRepository.setRuntimeMinutes(video.uri, it) }
            details.tagline?.let { videoMetadataRepository.setTagline(video.uri, it) }
        }
        if (needs.needsBackdropUpgrade) {
            TmdbClient.backdropUrl(details.backdropPath)?.let { url ->
                downloadBitmap(url)?.let { videoMetadataRepository.setBackdrop(video.uri, it) }
            }
            videoMetadataRepository.setBackdropUpgraded(video.uri, true)
        }
        return true
    }

    private fun fullyMatchMovie(video: VideoRef): Boolean {
        val parsed = FilenameParser.parse(video.filename, forMovie = true)
        val topMovie = TmdbClient.searchMulti(parsed.title).firstOrNull { it.mediaType == "movie" } ?: return false
        return applyMovieMatch(video, topMovie.id)
    }

    /** Applies one specific, already-chosen TMDB movie id to a video -- used by
     *  the Edit Info screen's "Find Match Online" picker, where the user (not
     *  the filename-parsing chain) has decided which TMDB result is correct. */
    fun applyMovieMatch(video: VideoRef, tmdbId: Int): Boolean {
        val details = TmdbClient.getMovieDetails(tmdbId) ?: return false

        videoMetadataRepository.setTmdbId(video.uri, details.id)
        videoMetadataRepository.setCustomTitle(video.uri, details.title)
        details.year?.let { videoMetadataRepository.setYear(video.uri, it) }
        details.overview?.let { videoMetadataRepository.setOverview(video.uri, it) }
        videoMetadataRepository.setGenres(video.uri, details.genres)
        details.rating?.let { videoMetadataRepository.setRating(video.uri, it) }
        details.runtimeMinutes?.let { videoMetadataRepository.setRuntimeMinutes(video.uri, it) }
        details.tagline?.let { videoMetadataRepository.setTagline(video.uri, it) }
        TmdbClient.posterUrl(details.posterPath)?.let { url ->
            downloadBitmap(url)?.let { videoMetadataRepository.setCustomThumbnail(video.uri, it) }
        }
        TmdbClient.backdropUrl(details.backdropPath)?.let { url ->
            downloadBitmap(url)?.let { videoMetadataRepository.setBackdrop(video.uri, it) }
        }
        // A fresh full match already has the larger backdrop size -- no separate
        // upgrade pass needed later for a video matched this way.
        videoMetadataRepository.setBackdropUpgraded(video.uri, true)
        return true
    }

    private fun downloadBitmap(url: String): Bitmap? {
        val connection = URL(url).openConnection() as HttpURLConnection
        return try {
            connection.connectTimeout = 8_000
            connection.readTimeout = 8_000
            connection.inputStream.use { BitmapFactory.decodeStream(it) }
        } catch (e: Exception) {
            null
        } finally {
            connection.disconnect()
        }
    }
}
