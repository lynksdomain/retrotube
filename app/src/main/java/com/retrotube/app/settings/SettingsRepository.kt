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

    private fun overrideKey(videoUri: String) = "$KEY_OVERRIDE_PREFIX$videoUri"

    companion object {
        private const val KEY_GLOBAL_DEFAULT = "global_default"
        private const val KEY_OVERRIDE_PREFIX = "override_"
    }
}
