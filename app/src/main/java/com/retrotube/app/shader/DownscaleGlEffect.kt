package com.retrotube.app.shader

import android.content.Context
import android.opengl.GLES20
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram
import androidx.media3.effect.SingleFrameGlShaderProgram
import kotlin.math.roundToInt

/**
 * Renders the input frame down to a small, aspect-preserving buffer sized to
 * roughly [targetHeight] scanlines. Meant to run immediately before
 * [CrtGlEffect] in the effect chain so the CRT math has a believably low-res
 * grid to work with, independent of the source video's own encode
 * resolution. A simple 3x3 box average (rather than a single bilinear tap)
 * avoids aliasing/moire when the downscale ratio is large (e.g. a 4K source
 * down to 240p).
 *
 * [targetHeight] is snapped to the nearest scanline count that divides
 * evenly into [referenceHeight] (the actual display height in pixels), so
 * the final upscale back to the screen is always a clean integer multiple
 * (e.g. exactly 4x) instead of a fractional one (e.g. 1.33x) that would
 * otherwise blur some scanlines more than others purely from rounding.
 */
@UnstableApi
class DownscaleGlEffect(targetHeight: Int, referenceHeight: Int) : GlEffect {

    private val snappedTargetHeight = run {
        val scaleFactor = (referenceHeight.toFloat() / targetHeight).roundToInt().coerceAtLeast(1)
        referenceHeight / scaleFactor
    }

    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram {
        return DownscaleShaderProgram(snappedTargetHeight, useHdr)
    }

    private class DownscaleShaderProgram(
        private val targetHeight: Int,
        useHdr: Boolean,
    ) : SingleFrameGlShaderProgram(useHdr) {

        private var glProgram: GlProgram? = null
        private var outputWidth: Int = 0
        private var outputHeight: Int = 0

        override fun configure(inputWidth: Int, inputHeight: Int): Size {
            outputHeight = targetHeight
            outputWidth = (inputWidth.toFloat() * targetHeight / inputHeight).toInt().coerceAtLeast(1)
            glProgram = GlProgram(ShaderPreset.VERTEX_SHADER, FRAGMENT_SHADER)
            return Size(outputWidth, outputHeight)
        }

        override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
            val program = glProgram ?: return
            program.use()
            program.setSamplerTexIdUniform("uTexSampler", inputTexId, /* texUnitIndex= */ 0)
            program.setFloatUniform("uDestTexelX", 1f / outputWidth)
            program.setFloatUniform("uDestTexelY", 1f / outputHeight)
            program.setBufferAttribute(
                "aPosition",
                GlUtil.getNormalizedCoordinateBounds(),
                GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE,
            )
            program.setBufferAttribute(
                "aTexCoords",
                GlUtil.getTextureCoordinateBounds(),
                GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE,
            )
            program.bindAttributesAndUniforms()
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        }

        override fun release() {
            super.release()
            glProgram?.delete()
        }

        companion object {
            private const val FRAGMENT_SHADER = """
                precision mediump float;
                uniform sampler2D uTexSampler;
                uniform float uDestTexelX;
                uniform float uDestTexelY;
                varying vec2 vTexCoords;

                void main() {
                    vec3 sum = vec3(0.0);
                    for (int dx = -1; dx <= 1; dx++) {
                        for (int dy = -1; dy <= 1; dy++) {
                            vec2 offset = vec2(float(dx) * uDestTexelX * 0.5, float(dy) * uDestTexelY * 0.5);
                            sum += texture2D(uTexSampler, vTexCoords + offset).rgb;
                        }
                    }
                    gl_FragColor = vec4(sum / 9.0, 1.0);
                }
            """
        }
    }
}
