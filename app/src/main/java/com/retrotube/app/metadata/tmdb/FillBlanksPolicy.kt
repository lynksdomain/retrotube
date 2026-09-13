package com.retrotube.app.metadata.tmdb

/** What a scrape pass should actually do to one item, decided purely from
 *  whether it already has a custom title and whether this pass is forced --
 *  the flag doesn't distinguish "a previous scrape set this" from "the user
 *  manually edited this"; both count as "already decided, don't touch." */
enum class MatchAction {
    /** Run a full fresh TMDB match and overwrite whatever was there. */
    FULL_MATCH,

    /** Already matched, not forced -- leave every field alone. */
    SKIP,

    /** Movies only: already matched, not forced, but has an independent gap
     *  worth filling in without re-matching or overwriting anything else. */
    BACKFILL_ONLY,
}

data class MovieBackfillNeeds(
    /** Runtime and tagline are fetched together the first time either is missing. */
    val needsRuntimeAndTagline: Boolean,
    /** A one-time upgrade to the larger backdrop size (see [TmdbClient]'s
     *  poster-vs-backdrop size distinction) -- tracked by
     *  [com.retrotube.app.metadata.VideoMetadataRepository.isBackdropUpgraded]
     *  so it only ever re-downloads once per video, not every library-wide pass. */
    val needsBackdropUpgrade: Boolean,
) {
    val hasAnyGap: Boolean get() = needsRuntimeAndTagline || needsBackdropUpgrade
}

/**
 * The single rule this whole matching engine hangs off of (spec §5.1): a
 * video already carrying a custom title is left alone by a normal pass,
 * full stop for shows/episodes -- and, for movies specifically, checked
 * against two independent backfill gaps that a pass can still fill in
 * without touching anything the user (or a prior match) already set.
 *
 * This partial-backfill-on-an-already-matched-movie behavior is exclusive to
 * the library-wide Refresh Library pass -- per-item Force Refresh always
 * bypasses this entirely via [forced], and there is deliberately no
 * folder-level Force Refresh for movies at all (see the refresh-action
 * scoping table in spec §5.6) -- only per-item movie Force Refresh and this
 * library-wide pass ever reach a movie's metadata.
 */
object FillBlanksPolicy {

    fun decideForShowOrEpisode(hasCustomTitle: Boolean, forced: Boolean): MatchAction =
        if (hasCustomTitle && !forced) MatchAction.SKIP else MatchAction.FULL_MATCH

    fun decideForMovie(
        hasCustomTitle: Boolean,
        forced: Boolean,
        runtimeIsNull: Boolean,
        backdropAlreadyUpgraded: Boolean,
    ): Pair<MatchAction, MovieBackfillNeeds?> {
        if (!hasCustomTitle || forced) return MatchAction.FULL_MATCH to null

        val needs = MovieBackfillNeeds(
            needsRuntimeAndTagline = runtimeIsNull,
            needsBackdropUpgrade = !backdropAlreadyUpgraded,
        )
        return if (needs.hasAnyGap) MatchAction.BACKFILL_ONLY to needs else MatchAction.SKIP to null
    }
}
