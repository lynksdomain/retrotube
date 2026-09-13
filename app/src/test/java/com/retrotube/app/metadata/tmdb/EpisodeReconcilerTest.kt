package com.retrotube.app.metadata.tmdb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EpisodeReconcilerTest {

    @Test
    fun `Dragon Ball Z S02E036 is really the show's 36th episode overall`() {
        // Season 1 has 35 episodes; episode 36 doesn't fit in season 2 (guessed
        // literally) but does land as season 2's 1st episode once treated as
        // an absolute, whole-series count.
        val seasons = listOf(
            SeasonEpisodeCounts(seasonNumber = 1, episodeCount = 35),
            SeasonEpisodeCounts(seasonNumber = 2, episodeCount = 35),
        )
        val result = EpisodeReconciler.reconcile(guessedSeason = 2, guessedEpisode = 36, seasons = seasons)
        assertEquals(ReconciledEpisode(season = 2, episode = 1), result)
    }

    @Test
    fun `ReBoot 2x01 is genuinely literal -- season 2, episode 1`() {
        val seasons = listOf(
            SeasonEpisodeCounts(seasonNumber = 1, episodeCount = 13),
            SeasonEpisodeCounts(seasonNumber = 2, episodeCount = 10),
        )
        val result = EpisodeReconciler.reconcile(guessedSeason = 2, guessedEpisode = 1, seasons = seasons)
        assertEquals(ReconciledEpisode(season = 2, episode = 1), result)
    }

    @Test
    fun `no season guess at all still resolves via the absolute walk (Yu Yu Hakusho style)`() {
        val seasons = listOf(
            SeasonEpisodeCounts(seasonNumber = 1, episodeCount = 20),
            SeasonEpisodeCounts(seasonNumber = 2, episodeCount = 27),
        )
        val result = EpisodeReconciler.reconcile(guessedSeason = null, guessedEpisode = 25, seasons = seasons)
        assertEquals(ReconciledEpisode(season = 2, episode = 5), result)
    }

    @Test
    fun `a number past every season's total episode count resolves to nothing`() {
        val seasons = listOf(SeasonEpisodeCounts(seasonNumber = 1, episodeCount = 12))
        val result = EpisodeReconciler.reconcile(guessedSeason = 1, guessedEpisode = 99, seasons = seasons)
        assertNull(result)
    }
}
