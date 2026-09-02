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

/**
 * Wraps a [ShaderPreset] as a Media3 [GlEffect] so it can be passed to
 * ExoPlayer.setVideoEffects(). One GL program per preset, fixed at
 * playback start -- no runtime parameter tweaking or hot-swap.
 *
 * NOTE: SingleFrameGlShaderProgram / GlProgram are @UnstableApi and have
 * shifted across media3 releases. Verify method signatures against the
 * media3-effect version pinned in app/build.gradle.kts on first build.
 */
@UnstableApi
class CrtGlEffect(
    private val preset: ShaderPreset,
    private val curvatureEnabled: Boolean,
) : GlEffect {

    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram {
        return CrtShaderProgram(preset, curvatureEnabled, useHdr)
    }

    private class CrtShaderProgram(
        private val preset: ShaderPreset,
        curvatureEnabled: Boolean,
        useHdr: Boolean,
    ) : SingleFrameGlShaderProgram(useHdr) {

        private val fragmentShader = ShaderPreset.buildFragmentShader(preset, curvatureEnabled)
        private var glProgram: GlProgram? = null
        private var width: Int = 0
        private var height: Int = 0

        override fun configure(inputWidth: Int, inputHeight: Int): Size {
            width = inputWidth
            height = inputHeight
            glProgram = GlProgram(ShaderPreset.VERTEX_SHADER, fragmentShader)
            return Size(inputWidth, inputHeight)
        }

        override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
            val program = glProgram ?: return
            program.use()
            program.setSamplerTexIdUniform("uTexSampler", inputTexId, /* texUnitIndex= */ 0)
            // Only set uniforms the preset's shader actually declares -- an unused uniform
            // gets stripped by the GLSL compiler, and setting a stripped uniform throws.
            if (preset.usesResolutionX) {
                program.setFloatUniform("uResolutionX", width.toFloat())
            }
            if (preset.usesResolutionY) {
                program.setFloatUniform("uResolutionY", height.toFloat())
            }
            if (preset.usesTime) {
                program.setFloatUniform("uTime", presentationTimeUs / 1_000_000f)
            }
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
    }
}
