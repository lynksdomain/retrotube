package com.retrotube.app.metadata.tmdb

/** One TMDB season's real episode count. */
data class SeasonEpisodeCounts(val seasonNumber: Int, val episodeCount: Int)

data class ReconciledEpisode(val season: Int, val episode: Int)

/**
 * Reconciles a raw (season, episode) guess parsed from a filename against a
 * show's real TMDB season list. Handles two real, common release patterns
 * without any manual flag distinguishing which one applies:
 *  - **Literal**: the guessed season is real and the episode number fits
 *    inside it (e.g. ReBoot's "2x01" happens to be genuinely season 2,
 *    episode 1).
 *  - **Absolute/continuous**: the guessed number is actually a whole-series
 *    episode count some releases use instead of per-season numbering (Dragon
 *    Ball Z's "S02E036" is really the show's 36th episode overall; Sailor
 *    Moon's "2x041" is the same pattern; Yu Yu Hakusho files often carry no
 *    season marker at all and need this to land anywhere).
 *
 * The algorithm below gets both cases right from the same input by trying
 * literal first and only falling back to the absolute walk if that fails --
 * never by inspecting the release's naming style.
 */
object EpisodeReconciler {

    fun reconcile(
        guessedSeason: Int?,
        guessedEpisode: Int,
        seasons: List<SeasonEpisodeCounts>,
    ): ReconciledEpisode? {
        val orderedSeasons = seasons.sortedBy { it.seasonNumber }

        if (guessedSeason != null) {
            val realSeason = orderedSeasons.firstOrNull { it.seasonNumber == guessedSeason }
            if (realSeason != null && guessedEpisode in 1..realSeason.episodeCount) {
                return ReconciledEpisode(guessedSeason, guessedEpisode)
            }
        }

        // Absolute/continuous: walk every season in order, subtracting each
        // one's episode count from the remaining number until it fits inside one.
        var remaining = guessedEpisode
        for (season in orderedSeasons) {
            if (season.episodeCount <= 0) continue
            if (remaining <= season.episodeCount) {
                return ReconciledEpisode(season.seasonNumber, remaining)
            }
            remaining -= season.episodeCount
        }
        return null
    }
}
