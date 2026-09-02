package com.retrotube.app.shader

/**
 * Forces the CRT shader to compute against a fixed low resolution regardless
 * of the source video's actual encode resolution -- otherwise scanline/mask
 * density is at the mercy of whatever the file happens to be encoded at
 * (e.g. 720 lines for a 960x720 file vs 2160 for 4K), which makes the effect
 * inconsistent across files and generally too fine to read as "CRT" at all.
 *
 * [targetHeight] is 0 for NATIVE, meaning skip the downscale pass entirely.
 */
enum class DownscaleTarget(val label: String, val targetHeight: Int) {
    NATIVE("Native (no downscale)", 0),
    P240("240p (chunky)", 240),
    P480("480p (medium)", 480),
    P720("720p (subtle)", 720),
}
