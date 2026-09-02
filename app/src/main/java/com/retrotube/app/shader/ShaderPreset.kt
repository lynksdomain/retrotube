package com.retrotube.app.shader

/**
 * Fixed video effect presets, chosen before playback and immutable for the
 * session. Each is a single-pass approximation of a well-known RetroArch /
 * CRT-hobbyist shader, grouped by GPU cost tier so the UI can steer weaker
 * devices toward cheaper looks.
 *
 * Curvature/geometry warp is intentionally NOT part of any preset -- it's a
 * separate orthogonal toggle (see [CrtGlEffect]) so picking a preset never
 * forces a curvature decision. Each preset's GLSL is written as an
 * `applyEffect(uv, rawUv)` function: `uv` is what to sample the texture at
 * (warped if curvature is on), `rawUv` is the untouched screen-space
 * coordinate (used for vignette centering / scanline phase so those don't
 * warp along with the geometry).
 */
enum class ShaderPreset(
    val label: String,
    val tier: CostTier,
    val usesResolutionX: Boolean,
    val usesResolutionY: Boolean,
    val usesTime: Boolean,
) {
    NONE("None", CostTier.LOW, usesResolutionX = false, usesResolutionY = false, usesTime = false),

    // --- Low cost: single texture sample plus trivial ALU math ---
    ZFAST_CRT("zfast-crt", CostTier.LOW, usesResolutionX = false, usesResolutionY = true, usesTime = false),
    PHOSPHOR_MONO("phosphor-mono", CostTier.LOW, usesResolutionX = false, usesResolutionY = false, usesTime = false),
    DECONVERGE("deconverge", CostTier.LOW, usesResolutionX = true, usesResolutionY = false, usesTime = false),

    // --- Medium cost: a few extra samples per pixel, or per-frame noise ---
    CRT_EASYMODE("crt-easymode", CostTier.MEDIUM, usesResolutionX = true, usesResolutionY = true, usesTime = false),
    VHS("vhs", CostTier.MEDIUM, usesResolutionX = true, usesResolutionY = false, usesTime = true),

    // --- High cost: multi-sample blends, wide taps ---
    CRT_GUEST_ADVANCED("crt-guest-advanced", CostTier.HIGH, usesResolutionX = true, usesResolutionY = true, usesTime = false),
    NTSC("ntsc", CostTier.HIGH, usesResolutionX = true, usesResolutionY = false, usesTime = false);

    enum class CostTier(val label: String) {
        LOW("Low"),
        MEDIUM("Medium"),
        HIGH("High"),
    }

    companion object {
        const val VERTEX_SHADER = """
            attribute vec4 aPosition;
            attribute vec4 aTexCoords;
            varying vec2 vTexCoords;
            void main() {
                gl_Position = aPosition;
                vTexCoords = aTexCoords.xy;
            }
        """

        // Shared geometry warp, independent of any preset. Barrel-style pincushion
        // curvature; out-of-bounds pixels are clipped to black by the caller.
        const val CURVATURE_WARP_FUNCTION = """
            vec2 warpUv(vec2 uv) {
                uv = uv * 2.0 - 1.0;
                vec2 offset = uv.yx * uv.yx * 0.03;
                uv = uv + uv * offset;
                uv = uv * 0.5 + 0.5;
                return uv;
            }
        """

        private const val NONE_EFFECT = """
            vec4 applyEffect(vec2 uv, vec2 rawUv) {
                return texture2D(uTexSampler, uv);
            }
        """

        // zfast-crt: cheap scanlines + mild vignette. One texture sample, trivial ALU cost.
        private const val ZFAST_CRT_EFFECT = """
            vec4 applyEffect(vec2 uv, vec2 rawUv) {
                vec4 color = texture2D(uTexSampler, uv);

                float scanline = sin(rawUv.y * uResolutionY * 3.14159);
                float scanlineDarken = 0.85 + 0.15 * scanline;

                vec2 centered = rawUv - 0.5;
                float vignette = 1.0 - dot(centered, centered) * 0.35;

                return vec4(color.rgb * scanlineDarken * vignette, color.a);
            }
        """

        // phosphor-mono: single-color-channel terminal/monochrome monitor grade.
        // Cheapest possible preset with the biggest perceptual change.
        private const val PHOSPHOR_MONO_EFFECT = """
            vec4 applyEffect(vec2 uv, vec2 rawUv) {
                vec4 color = texture2D(uTexSampler, uv);
                float luma = dot(color.rgb, vec3(0.299, 0.587, 0.114));
                vec3 phosphor = vec3(0.10, 1.0, 0.35) * luma;
                return vec4(phosphor, color.a);
            }
        """

        // deconverge: simulates misaligned CRT electron guns by sampling R/G/B
        // from slightly offset texture coordinates. Three samples, no loops.
        private const val DECONVERGE_EFFECT = """
            vec4 applyEffect(vec2 uv, vec2 rawUv) {
                float offset = 1.5 / uResolutionX;
                float r = texture2D(uTexSampler, uv + vec2(offset, 0.0)).r;
                float g = texture2D(uTexSampler, uv).g;
                float b = texture2D(uTexSampler, uv - vec2(offset, 0.0)).b;
                return vec4(r, g, b, 1.0);
            }
        """

        // crt-easymode: scanlines with adjustable-looking mask + soft phosphor glow.
        private const val CRT_EASYMODE_EFFECT = """
            vec4 applyEffect(vec2 uv, vec2 rawUv) {
                vec4 color = texture2D(uTexSampler, uv);

                float scanline = sin(rawUv.y * uResolutionY * 3.14159);
                float scanlineDarken = 0.80 + 0.20 * scanline;

                float maskPhase = mod(rawUv.x * uResolutionX, 3.0);
                vec3 mask = vec3(1.0);
                if (maskPhase < 1.0) mask = vec3(1.1, 0.85, 0.85);
                else if (maskPhase < 2.0) mask = vec3(0.85, 1.1, 0.85);
                else mask = vec3(0.85, 0.85, 1.1);

                vec3 glow = color.rgb * 0.15;

                vec2 centered = rawUv - 0.5;
                float vignette = 1.0 - dot(centered, centered) * 0.45;

                vec3 result = (color.rgb * mask * scanlineDarken + glow) * vignette;
                return vec4(result, color.a);
            }
        """

        // vhs: tape wobble + chroma smear + per-frame tracking noise bands.
        // The loud, unmistakable "novelty" preset -- animated, uses uTime.
        private const val VHS_EFFECT = """
            float rand(vec2 co) {
                return fract(sin(dot(co.xy, vec2(12.9898, 78.233))) * 43758.5453);
            }

            vec4 applyEffect(vec2 uv, vec2 rawUv) {
                vec2 wobbledUv = uv;
                float wobble = sin(rawUv.y * 40.0 + uTime * 6.0) * 0.0015;
                wobbledUv.x += wobble;

                float smear = 3.0 / uResolutionX;
                float r = texture2D(uTexSampler, wobbledUv + vec2(smear, 0.0)).r;
                float g = texture2D(uTexSampler, wobbledUv).g;
                float b = texture2D(uTexSampler, wobbledUv - vec2(smear, 0.0)).b;

                float noiseLine = step(0.996, rand(vec2(rawUv.y * 200.0, floor(uTime * 10.0))));
                vec3 color = vec3(r, g, b) + noiseLine * 0.4;

                float scan = sin(rawUv.y * 500.0) * 0.04;
                color -= scan;

                return vec4(color, 1.0);
            }
        """

        // crt-guest-advanced: mask + halation, multi-sample. Curvature lives in the
        // separate warp toggle now, not baked in here.
        private const val CRT_GUEST_ADVANCED_EFFECT = """
            vec4 applyEffect(vec2 uv, vec2 rawUv) {
                vec4 color = texture2D(uTexSampler, uv);

                float scanline = sin(rawUv.y * uResolutionY * 3.14159);
                float scanlineDarken = 0.75 + 0.25 * scanline;

                float maskPhase = mod(rawUv.x * uResolutionX, 3.0);
                vec3 mask = vec3(1.0);
                if (maskPhase < 1.0) mask = vec3(1.15, 0.80, 0.80);
                else if (maskPhase < 2.0) mask = vec3(0.80, 1.15, 0.80);
                else mask = vec3(0.80, 0.80, 1.15);

                vec3 blurSample =
                    texture2D(uTexSampler, uv + vec2(1.5 / uResolutionX, 0.0)).rgb +
                    texture2D(uTexSampler, uv - vec2(1.5 / uResolutionX, 0.0)).rgb;
                vec3 halation = blurSample * 0.12;

                vec2 centered = rawUv - 0.5;
                float vignette = 1.0 - dot(centered, centered) * 0.55;

                vec3 result = (color.rgb * mask * scanlineDarken + halation) * vignette;
                return vec4(result, color.a);
            }
        """

        // ntsc: approximates composite-video color bleed and softened chroma
        // by blending a wide horizontal chroma sample back onto sharp luma.
        private const val NTSC_EFFECT = """
            vec4 applyEffect(vec2 uv, vec2 rawUv) {
                float texel = 1.0 / uResolutionX;

                vec3 c0 = texture2D(uTexSampler, uv).rgb;
                vec3 cL1 = texture2D(uTexSampler, uv - vec2(texel, 0.0)).rgb;
                vec3 cL2 = texture2D(uTexSampler, uv - vec2(texel * 2.0, 0.0)).rgb;
                vec3 cR1 = texture2D(uTexSampler, uv + vec2(texel, 0.0)).rgb;
                vec3 cR2 = texture2D(uTexSampler, uv + vec2(texel * 2.0, 0.0)).rgb;

                float lumaC = dot(c0, vec3(0.299, 0.587, 0.114));
                vec3 chromaBlend = (cL2 + cL1 + c0 + cR1 + cR2) / 5.0;
                vec3 chroma = chromaBlend - vec3(dot(chromaBlend, vec3(0.299, 0.587, 0.114)));

                vec3 result = vec3(lumaC) + chroma * 1.4;
                return vec4(result, 1.0);
            }
        """

        fun effectFunctionFor(preset: ShaderPreset): String = when (preset) {
            NONE -> NONE_EFFECT
            ZFAST_CRT -> ZFAST_CRT_EFFECT
            PHOSPHOR_MONO -> PHOSPHOR_MONO_EFFECT
            DECONVERGE -> DECONVERGE_EFFECT
            CRT_EASYMODE -> CRT_EASYMODE_EFFECT
            VHS -> VHS_EFFECT
            CRT_GUEST_ADVANCED -> CRT_GUEST_ADVANCED_EFFECT
            NTSC -> NTSC_EFFECT
        }

        /**
         * Composes the full fragment shader for a preset + independent curvature
         * toggle. Curvature warps `uv` (used for texture sampling) but leaves
         * `rawUv` (used for vignette/scanline screen-space math) untouched, so
         * every preset works with or without curvature with no special-casing.
         */
        fun buildFragmentShader(preset: ShaderPreset, curvatureEnabled: Boolean): String {
            val uniforms = buildString {
                append("uniform sampler2D uTexSampler;\n")
                if (preset.usesResolutionX) append("uniform float uResolutionX;\n")
                if (preset.usesResolutionY) append("uniform float uResolutionY;\n")
                if (preset.usesTime) append("uniform float uTime;\n")
            }

            val uvComputation = if (curvatureEnabled) {
                """
                vec2 rawUv = vTexCoords;
                vec2 uv = warpUv(rawUv);
                if (uv.x < 0.0 || uv.x > 1.0 || uv.y < 0.0 || uv.y > 1.0) {
                    gl_FragColor = vec4(0.0, 0.0, 0.0, 1.0);
                    return;
                }
                """
            } else {
                """
                vec2 rawUv = vTexCoords;
                vec2 uv = rawUv;
                """
            }

            return """
                precision highp float;
                $uniforms
                varying vec2 vTexCoords;

                ${if (curvatureEnabled) CURVATURE_WARP_FUNCTION else ""}

                ${effectFunctionFor(preset)}

                void main() {
                    $uvComputation
                    gl_FragColor = applyEffect(uv, rawUv);
                }
            """
        }
    }
}
