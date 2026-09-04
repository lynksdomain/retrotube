package com.retrotube.app.settings

import androidx.media3.ui.AspectRatioFrameLayout
import com.retrotube.app.shader.DownscaleTarget
import com.retrotube.app.shader.ShaderPreset

/** The full set of effect choices applied to a single playback session. */
data class VideoEffectSettings(
    val preset: ShaderPreset,
    val curvatureEnabled: Boolean,
    val downscale: DownscaleTarget,
    val aspectMode: Int,
) {
    fun serialize(): String =
        listOf(preset.name, curvatureEnabled.toString(), downscale.name, aspectMode.toString())
            .joinToString("|")

    companion object {
        val DEFAULT = VideoEffectSettings(
            preset = ShaderPreset.ZFAST_CRT,
            curvatureEnabled = false,
            downscale = DownscaleTarget.NATIVE,
            aspectMode = AspectRatioFrameLayout.RESIZE_MODE_FIT,
        )

        /** TV Mode forces a visible downscale -- at native resolution, scanline/mask
         *  presets are too fine-grained to read as "CRT" at a glance, and a channel
         *  you're flipping past for a couple seconds needs the effect to register
         *  immediately, not reward close inspection. */
        val TV_MODE = DEFAULT.copy(downscale = DownscaleTarget.P480)

        fun deserialize(raw: String?): VideoEffectSettings? {
            if (raw == null) return null
            val parts = raw.split("|")
            if (parts.size != 4) return null
            return try {
                VideoEffectSettings(
                    preset = ShaderPreset.valueOf(parts[0]),
                    curvatureEnabled = parts[1].toBoolean(),
                    downscale = DownscaleTarget.valueOf(parts[2]),
                    aspectMode = parts[3].toInt(),
                )
            } catch (e: IllegalArgumentException) {
                null
            }
        }
    }
}
