package com.retrotube.app.settings

import android.content.Context

/**
 * Global default effect settings, plus optional per-file overrides keyed by
 * the video's content URI string. [effectiveSettings] resolves the override
 * if one exists, falling back to the global default otherwise.
 */
class SettingsRepository(context: Context) {

    private val prefs = context.getSharedPreferences("retrotube_settings", Context.MODE_PRIVATE)

    fun getGlobalDefault(): VideoEffectSettings =
        VideoEffectSettings.deserialize(prefs.getString(KEY_GLOBAL_DEFAULT, null))
            ?: VideoEffectSettings.DEFAULT

    fun setGlobalDefault(settings: VideoEffectSettings) {
        prefs.edit().putString(KEY_GLOBAL_DEFAULT, settings.serialize()).apply()
    }

    fun getOverride(videoUri: String): VideoEffectSettings? =
        VideoEffectSettings.deserialize(prefs.getString(overrideKey(videoUri), null))

    fun setOverride(videoUri: String, settings: VideoEffectSettings) {
        prefs.edit().putString(overrideKey(videoUri), settings.serialize()).apply()
    }

    fun clearOverride(videoUri: String) {
        prefs.edit().remove(overrideKey(videoUri)).apply()
    }

    fun effectiveSettings(videoUri: String): VideoEffectSettings =
        getOverride(videoUri) ?: getGlobalDefault()

    /** TV Mode forces its own preset/downscale (see [VideoEffectSettings.TV_MODE])
     *  regardless of the user's global default or any per-file override -- every
     *  channel should look the same. Curvature is the one exception: exposed live
     *  in the TV remote overlay, and persisted here independently of both that
     *  forced combination and the normal-playback global default/override, so
     *  toggling it in TV Mode never touches what a video looks like outside it. */
    fun getTvCurvatureEnabled(): Boolean = prefs.getBoolean(KEY_TV_CURVATURE, false)

    fun setTvCurvatureEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_TV_CURVATURE, enabled).apply()
    }

    /** Subtitle text size as a fraction of the player view's height, matching
     *  androidx.media3.ui.SubtitleView's own setFractionalTextSize scale. */
    fun getSubtitleSizeFraction(): Float = prefs.getFloat(KEY_SUBTITLE_SIZE, 0.0533f)

    fun setSubtitleSizeFraction(fraction: Float) {
        prefs.edit().putFloat(KEY_SUBTITLE_SIZE, fraction).apply()
    }

    private fun overrideKey(videoUri: String) = "$KEY_OVERRIDE_PREFIX$videoUri"

    companion object {
        private const val KEY_GLOBAL_DEFAULT = "global_default"
        private const val KEY_OVERRIDE_PREFIX = "override_"
        private const val KEY_TV_CURVATURE = "tv_curvature_enabled"
        private const val KEY_SUBTITLE_SIZE = "subtitle_size_fraction"
    }
}
