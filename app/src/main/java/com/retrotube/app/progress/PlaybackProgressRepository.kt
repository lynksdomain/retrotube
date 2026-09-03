package com.retrotube.app.progress

import android.content.Context

/**
 * Remembers how far into each video the user got, keyed by its content URI
 * string. A video within [FINISHED_THRESHOLD_MS] of its own duration is
 * treated as finished and its progress is dropped rather than saved, so
 * reopening it starts fresh instead of "resuming" the last two seconds.
 */
class PlaybackProgressRepository(context: Context) {

    private val prefs = context.getSharedPreferences("retrotube_progress", Context.MODE_PRIVATE)

    data class Progress(
        val positionMs: Long,
        val durationMs: Long,
        val updatedAtMs: Long,
        val hiddenFromContinueWatching: Boolean,
    ) {
        val fraction: Float get() = if (durationMs > 0) positionMs.toFloat() / durationMs else 0f
    }

    /** Real saved position regardless of [Progress.hiddenFromContinueWatching] -- hiding a
     *  video only affects the curated "Continue Watching" row, resuming still works
     *  everywhere else. */
    fun getProgress(videoUri: String): Progress? = parse(prefs.getString(videoUri, null))

    /** In-progress videos not hidden from the row, most recently watched first. */
    fun getAllProgress(): List<Pair<String, Progress>> =
        prefs.all.mapNotNull { (uri, raw) ->
            parse(raw as? String)?.let { uri to it }
        }.filterNot { it.second.hiddenFromContinueWatching }
            .sortedByDescending { it.second.updatedAtMs }

    fun saveProgress(videoUri: String, positionMs: Long, durationMs: Long) {
        if (durationMs <= 0) return
        if (positionMs >= durationMs - FINISHED_THRESHOLD_MS || positionMs <= START_THRESHOLD_MS) {
            clearProgress(videoUri)
            return
        }
        // Actively watching again un-hides it -- hiding is a one-time dismissal, not a
        // permanent block.
        write(videoUri, Progress(positionMs, durationMs, System.currentTimeMillis(), hiddenFromContinueWatching = false))
    }

    /** Removes [videoUri] from the Continue Watching row without touching its saved
     *  position -- opening it directly still resumes where it left off. */
    fun hideFromContinueWatching(videoUri: String) {
        val current = getProgress(videoUri) ?: return
        write(videoUri, current.copy(hiddenFromContinueWatching = true))
    }

    fun clearProgress(videoUri: String) {
        prefs.edit().remove(videoUri).apply()
    }

    private fun write(videoUri: String, progress: Progress) {
        val hidden = if (progress.hiddenFromContinueWatching) "1" else "0"
        prefs.edit()
            .putString(videoUri, "${progress.positionMs}|${progress.durationMs}|${progress.updatedAtMs}|$hidden")
            .apply()
    }

    private fun parse(raw: String?): Progress? {
        if (raw == null) return null
        val parts = raw.split("|")
        if (parts.size != 4) return null
        val position = parts[0].toLongOrNull() ?: return null
        val duration = parts[1].toLongOrNull() ?: return null
        val updatedAt = parts[2].toLongOrNull() ?: return null
        val hidden = parts[3] == "1"
        return Progress(position, duration, updatedAt, hidden)
    }

    companion object {
        private const val FINISHED_THRESHOLD_MS = 30_000L
        private const val START_THRESHOLD_MS = 5_000L
    }
}
