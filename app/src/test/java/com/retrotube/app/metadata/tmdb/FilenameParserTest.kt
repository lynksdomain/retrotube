package com.retrotube.app.metadata.tmdb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Every case here is a worked example from the Android parity spec (§5.2) --
 *  these are the exact outcomes the filename parser is required to produce,
 *  not just illustrative examples. */
class FilenameParserTest {

    @Test
    fun `strips year, season range, and encode tags down to just the show title`() {
        val result = FilenameParser.parse("Gilmore Girls.2000.S01-S07.720p.H265-Zero00")
        assertEquals("Gilmore Girls", result.title)
        assertNull(result.episode)
    }

    @Test
    fun `dash-prefixed episode number, the dominant anime fansub convention`() {
        val result = FilenameParser.parse("Death Note - 01.mkv")
        assertEquals("Death Note", result.title)
        assertEquals(1, result.episode)
    }

    @Test
    fun `E01 style episode marker`() {
        val result = FilenameParser.parse("Death Note E01.mkv")
        assertEquals(1, result.episode)
    }

    @Test
    fun `a resolution string is never mistaken for season times episode`() {
        val result = FilenameParser.parse("1920x1080")
        assertNull(result.season)
        assertNull(result.episode)
    }

    @Test
    fun `real failure case -- messy release-group folder name still yields a clean title`() {
        val result = FilenameParser.parse(
            "Courage the Cowardly Dog (1999) Season 1-4 S01-04 (1080p HMAX WEBDL x265 10bit AAC 2.0 EDGE2020)",
        )
        assertEquals("Courage the Cowardly Dog", result.title)
    }

    @Test
    fun `SxxExx takes priority over every other pattern`() {
        val result = FilenameParser.parse("Show Name S02E036.mkv")
        assertEquals(2, result.season)
        assertEquals(36, result.episode)
    }

    @Test
    fun `NxN style with word boundaries, e_g_ Sailor Moon`() {
        val result = FilenameParser.parse("Sailor Moon 2x041.mkv")
        assertEquals(2, result.season)
        assertEquals(41, result.episode)
    }

    @Test
    fun `a movie title's own numeral is not mistaken for an episode number`() {
        // Real failure case: without forMovie, the standalone "2" here got read as a
        // guessed episode number and used as the title-boundary cut, chopping the
        // title down to plain "Deadpool" -- identical to "Deadpool (2016)" -- so both
        // folders searched TMDB for the same wrong title and matched the same movie.
        val sequel = FilenameParser.parse("Deadpool 2 (2018) [1080p] [YTS.AM]", forMovie = true)
        assertEquals("Deadpool 2", sequel.title)
        assertNull(sequel.episode)

        val original = FilenameParser.parse("Deadpool (2016) [1080p] [YTS.AG]", forMovie = true)
        assertEquals("Deadpool", original.title)
    }

    @Test
    fun `season-from-folder-name fallback`() {
        assertEquals(1, FilenameParser.seasonFromFolderName("Season 1"))
        assertEquals(3, FilenameParser.seasonFromFolderName("S3"))
        assertNull(FilenameParser.seasonFromFolderName("Extras"))
    }
}
