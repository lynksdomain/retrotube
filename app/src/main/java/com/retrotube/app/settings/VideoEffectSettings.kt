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

        /** TV Mode always uses this exact combination, independent of whatever the
         *  user has set as their global default -- every channel should look and
         *  feel the same, and a heavier preset (or one that's off) would make some
         *  channels feel "gated" behind a nicer look than others. zfast-crt is the
         *  cheapest preset (a plain texture sample plus scanline/vignette math), so
         *  it holds up flipping rapidly between channels on any device. The 240p
         *  downscale is what actually makes it register at a glance -- at native
         *  resolution the scanlines are too fine to read as "CRT" while flipping
         *  past a channel for a couple seconds. */
        val TV_MODE = VideoEffectSettings(
            preset = ShaderPreset.ZFAST_CRT,
            curvatureEnabled = false,
            downscale = DownscaleTarget.P240,
            aspectMode = AspectRatioFrameLayout.RESIZE_MODE_FIT,
        )

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
