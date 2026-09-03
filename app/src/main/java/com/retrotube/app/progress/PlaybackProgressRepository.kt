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

    data class Progress(val positionMs: Long, val durationMs: Long, val updatedAtMs: Long) {
        val fraction: Float get() = if (durationMs > 0) positionMs.toFloat() / durationMs else 0f
    }

    fun getProgress(videoUri: String): Progress? = parse(prefs.getString(videoUri, null))

    /** All in-progress videos, most recently watched first -- feeds "Continue Watching". */
    fun getAllProgress(): List<Pair<String, Progress>> =
        prefs.all.mapNotNull { (uri, raw) ->
            parse(raw as? String)?.let { uri to it }
        }.sortedByDescending { it.second.updatedAtMs }

    fun saveProgress(videoUri: String, positionMs: Long, durationMs: Long) {
        if (durationMs <= 0) return
        if (positionMs >= durationMs - FINISHED_THRESHOLD_MS || positionMs <= START_THRESHOLD_MS) {
            clearProgress(videoUri)
            return
        }
        prefs.edit().putString(videoUri, "$positionMs|$durationMs|${System.currentTimeMillis()}").apply()
    }

    fun clearProgress(videoUri: String) {
        prefs.edit().remove(videoUri).apply()
    }

    private fun parse(raw: String?): Progress? {
        if (raw == null) return null
        val parts = raw.split("|")
        if (parts.size != 3) return null
        val position = parts[0].toLongOrNull() ?: return null
        val duration = parts[1].toLongOrNull() ?: return null
        val updatedAt = parts[2].toLongOrNull() ?: return null
        return Progress(position, duration, updatedAt)
    }

    companion object {
        private const val FINISHED_THRESHOLD_MS = 30_000L
        private const val START_THRESHOLD_MS = 5_000L
    }
}
