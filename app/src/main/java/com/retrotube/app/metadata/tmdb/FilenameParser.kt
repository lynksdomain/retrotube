package com.retrotube.app.metadata.tmdb

/** What could be recovered from a filename alone -- [season] is only ever set
 *  by an explicit season marker (never inferred from an absolute episode
 *  number; that reconciliation happens later, against real TMDB season data,
 *  in [EpisodeReconciler]). */
data class ParsedFilename(
    val title: String,
    val season: Int?,
    val episode: Int?,
)

/**
 * Recovers a title and a raw (season, episode) guess from a video's filename
 * alone -- no network calls, no TMDB awareness. Every pattern and its exact
 * priority order is load-bearing: real anime/TV release-naming conventions
 * are inconsistent enough that reordering these, or dropping the resolution/
 * year exclusions, silently misfires on real filenames (see the worked
 * examples in FilenameParserTest, which double as the spec for this file).
 */
object FilenameParser {

    // A fixed resolution value would otherwise false-positive as an episode
    // number in filenames like "...720p..." or "...1080p...".
    private val RESOLUTION_VALUES = setOf(240, 360, 480, 576, 720, 1080, 1440, 2160, 4320)
    private val YEAR_REGEX = Regex("(?:19|20)\\d{2}")

    // Release-group / encode noise, stripped from the front of a candidate
    // title once the season/episode/year boundary has already cut it down.
    private val NOISE_TOKENS = listOf(
        "720p", "1080p", "2160p", "480p", "bluray", "blu-ray", "webdl", "web-dl", "webrip", "web",
        "hdtv", "dvdrip", "brrip", "bdrip", "x264", "x265", "h264", "h265", "hevc", "aac", "ac3",
        "dts", "complete", "series", "season", "repack", "proper", "internal", "remux", "multi",
    )

    // --- Season/episode-together patterns, tried in this exact order; first match wins. ---
    private val SEASON_EPISODE = Regex("[Ss](\\d{1,2})[Ee](\\d{1,3})")
    private val N_BY_N = Regex("\\b(\\d{1,2})x(\\d{1,3})\\b")
    private val SEASON_ONLY = Regex("[Ss](\\d{1,2})(?!\\d)")

    // --- Episode-only patterns, tried in this exact order once no episode was
    // found above; first match wins. ---
    private val EPISODE_WORD = Regex("(?i)\\bEP?(?:ISODE)?\\.?\\s*(\\d{1,4})\\b")
    private val DASH_PREFIXED = Regex("-\\s*(\\d{1,4})(?!\\d)")
    private val BRACKETED = Regex("[\\[(](\\d{1,4})[])]")
    private val LAST_RESORT = Regex("(?<![A-Za-z\\d])(\\d{1,4})(?![A-Za-z\\d])")

    private val SEASON_FROM_FOLDER_NAME = Regex("(?i)season\\s*(\\d{1,3})")
    private val FOLDER_IS_JUST_SEASON = Regex("(?i)^s(\\d{1,3})$")

    /** [forMovie] skips the bare-isolated-digit last-resort tier of episode
     *  detection (see [findEpisodeOnly]) -- that heuristic exists for
     *  anime/TV releases with no explicit season/episode marker at all, and a
     *  movie never has (or needs) an episode number, but a real title can
     *  still contain a lone numeral of its own ("Deadpool 2", "Ocean's 8",
     *  "2012"). Without this gate, that numeral gets misread as a guessed
     *  episode number and used as the title-boundary cut, silently chopping
     *  the title back to something a different, wrong movie also matches --
     *  confirmed against real filenames, not a hypothetical: "Deadpool 2
     *  (2018)" and "Deadpool (2016)" both parsed down to just "Deadpool". */
    fun parse(rawFilename: String, forMovie: Boolean = false): ParsedFilename {
        val withoutExtension = rawFilename.substringBeforeLast('.', rawFilename)
        val normalized = withoutExtension.replace('_', ' ').replace('.', ' ')

        var season: Int? = null
        var episode: Int? = null
        val boundaries = mutableListOf<Int>()

        SEASON_EPISODE.find(normalized)?.let { match ->
            season = match.groupValues[1].toIntOrNull()
            episode = match.groupValues[2].toIntOrNull()
            boundaries += match.range.first
        }

        if (season == null) {
            N_BY_N.find(normalized)?.let { match ->
                season = match.groupValues[1].toIntOrNull()
                episode = match.groupValues[2].toIntOrNull()
                boundaries += match.range.first
            }
        }

        if (season == null) {
            SEASON_ONLY.find(normalized)?.let { match ->
                season = match.groupValues[1].toIntOrNull()
                boundaries += match.range.first
            }
        }

        if (episode == null && !forMovie) {
            findEpisodeOnly(normalized)?.let { (value, start) ->
                episode = value
                boundaries += start
            }
        }

        val yearMatch = YEAR_REGEX.find(normalized)
        yearMatch?.let { boundaries += it.range.first }

        val titleBoundary = trimTrailingOpenBracket(normalized, boundaries.minOrNull() ?: normalized.length)
        val title = extractTitle(normalized.substring(0, titleBoundary))

        return ParsedFilename(title = title, season = season, episode = episode)
    }

    /** Only used when the filename itself carried no season marker at all --
     *  e.g. a flat "Season 1-4 S01-04" release-group folder name. */
    fun seasonFromFolderName(folderName: String): Int? {
        FOLDER_IS_JUST_SEASON.find(folderName)?.let { return it.groupValues[1].toIntOrNull() }
        return SEASON_FROM_FOLDER_NAME.find(folderName)?.groupValues?.get(1)?.toIntOrNull()
    }

    /** Tries each episode-only pattern in priority order. The first three take
     *  their first surviving candidate; the last-resort pattern explicitly
     *  wants the *last* fully digit-isolated run in the string instead (a
     *  release group's own trailing catalog number is usually the actual
     *  episode when nothing more specific matched). */
    private fun findEpisodeOnly(normalized: String): Pair<Int, Int>? {
        for (regex in listOf(EPISODE_WORD, DASH_PREFIXED, BRACKETED)) {
            val match = regex.findAll(normalized).firstNotNullOfOrNull { match -> match.toValidCandidate() }
            if (match != null) return match
        }
        return LAST_RESORT.findAll(normalized).mapNotNull { it.toValidCandidate() }.lastOrNull()
    }

    private fun MatchResult.toValidCandidate(): Pair<Int, Int>? {
        val value = groupValues[1].toIntOrNull() ?: return null
        if (value in RESOLUTION_VALUES) return null
        if (YEAR_REGEX.matches(groupValues[1])) return null
        return value to range.first
    }

    /** A year/season/episode match immediately preceded by "(" or "[" (e.g.
     *  "Show Name (1999)") is a grouping bracket around that match, not part
     *  of the title -- walk the boundary back past any such bracket (and the
     *  whitespace around it) so it doesn't get left dangling on the title. */
    private fun trimTrailingOpenBracket(normalized: String, boundary: Int): Int {
        var end = boundary
        while (end > 0 && normalized[end - 1] == ' ') end--
        if (end > 0 && (normalized[end - 1] == '(' || normalized[end - 1] == '[')) end--
        return end
    }

    private fun extractTitle(candidate: String): String {
        val words = candidate.trim().split(Regex("\\s+"))
        val cutIndex = words.indexOfFirst { word -> word.lowercase() in NOISE_TOKENS }
        val kept = if (cutIndex >= 0) words.subList(0, cutIndex) else words
        val trimmed = kept.joinToString(" ").trim(' ', '-', '_')
        return trimmed.ifEmpty { candidate.trim() }.ifEmpty { candidate }
    }
}
