package com.retrotube.app.metadata.tmdb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FillBlanksPolicyTest {

    @Test
    fun `an unmatched show or episode always gets a full match`() {
        assertEquals(MatchAction.FULL_MATCH, FillBlanksPolicy.decideForShowOrEpisode(hasCustomTitle = false, forced = false))
    }

    @Test
    fun `an already-matched show is skipped entirely unless forced`() {
        assertEquals(MatchAction.SKIP, FillBlanksPolicy.decideForShowOrEpisode(hasCustomTitle = true, forced = false))
    }

    @Test
    fun `Force Refresh bypasses fill-blanks-only even if already matched`() {
        assertEquals(MatchAction.FULL_MATCH, FillBlanksPolicy.decideForShowOrEpisode(hasCustomTitle = true, forced = true))
    }

    @Test
    fun `an unmatched movie always gets a full match`() {
        val (action, needs) = FillBlanksPolicy.decideForMovie(
            hasCustomTitle = false, forced = false, runtimeIsNull = true, backdropAlreadyUpgraded = false,
        )
        assertEquals(MatchAction.FULL_MATCH, action)
        assertNull(needs)
    }

    @Test
    fun `a fully-backfilled already-matched movie is skipped, not re-matched`() {
        val (action, needs) = FillBlanksPolicy.decideForMovie(
            hasCustomTitle = true, forced = false, runtimeIsNull = false, backdropAlreadyUpgraded = true,
        )
        assertEquals(MatchAction.SKIP, action)
        assertNull(needs)
    }

    @Test
    fun `an already-matched movie missing only runtime gets backfill-only, not a re-match`() {
        val (action, needs) = FillBlanksPolicy.decideForMovie(
            hasCustomTitle = true, forced = false, runtimeIsNull = true, backdropAlreadyUpgraded = true,
        )
        assertEquals(MatchAction.BACKFILL_ONLY, action)
        assertTrue(needs!!.needsRuntimeAndTagline)
        assertTrue(!needs.needsBackdropUpgrade)
    }

    @Test
    fun `an already-matched movie missing only the backdrop upgrade gets backfill-only`() {
        val (action, needs) = FillBlanksPolicy.decideForMovie(
            hasCustomTitle = true, forced = false, runtimeIsNull = false, backdropAlreadyUpgraded = false,
        )
        assertEquals(MatchAction.BACKFILL_ONLY, action)
        assertTrue(!needs!!.needsRuntimeAndTagline)
        assertTrue(needs.needsBackdropUpgrade)
    }

    @Test
    fun `Force Refresh on a movie always fully re-matches, ignoring backfill state`() {
        val (action, needs) = FillBlanksPolicy.decideForMovie(
            hasCustomTitle = true, forced = true, runtimeIsNull = false, backdropAlreadyUpgraded = true,
        )
        assertEquals(MatchAction.FULL_MATCH, action)
        assertNull(needs)
    }
}
