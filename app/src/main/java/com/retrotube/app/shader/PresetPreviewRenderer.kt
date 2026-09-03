package com.retrotube.app.shader

import android.graphics.Bitmap
import android.graphics.Matrix
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.GLES20
import android.opengl.GLUtils
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Renders each [ShaderPreset]'s real fragment shader once against a small
 * synthetic test pattern, off the video pipeline entirely -- a standalone
 * EGL pbuffer context rather than Media3's managed GL context, since this
 * runs at settings-screen time with no player/video involved. Curvature is
 * deliberately not applied: previews represent the preset itself, since
 * curvature is a separate, independent toggle.
 */
@UnstableApi
object PresetPreviewRenderer {

    private const val WIDTH = 160
    private const val HEIGHT = 120

    /** In-memory only -- cheap enough to regenerate per process, no need to persist. */
    private val cache = mutableMapOf<ShaderPreset, Bitmap>()

    fun getOrRender(preset: ShaderPreset): Bitmap? {
        cache[preset]?.let { return it }
        val bitmap = renderInternal(preset) ?: return null
        cache[preset] = bitmap
        return bitmap
    }

    private fun renderInternal(preset: ShaderPreset): Bitmap? {
        val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (display == EGL14.EGL_NO_DISPLAY) return null
        val version = IntArray(2)
        if (!EGL14.eglInitialize(display, version, 0, version, 1)) return null

        try {
            val configAttribs = intArrayOf(
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                EGL14.EGL_NONE,
            )
            val configs = arrayOfNulls<EGLConfig>(1)
            val numConfigs = IntArray(1)
            EGL14.eglChooseConfig(display, configAttribs, 0, configs, 0, 1, numConfigs, 0)
            val config = configs[0] ?: return null

            val contextAttribs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
            val context = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT, contextAttribs, 0)

            val surfaceAttribs = intArrayOf(EGL14.EGL_WIDTH, WIDTH, EGL14.EGL_HEIGHT, HEIGHT, EGL14.EGL_NONE)
            val surface = EGL14.eglCreatePbufferSurface(display, config, surfaceAttribs, 0)

            EGL14.eglMakeCurrent(display, surface, surface, context)

            val bitmap = try {
                drawPreset(preset)
            } finally {
                EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                EGL14.eglDestroySurface(display, surface)
                EGL14.eglDestroyContext(display, context)
            }
            return bitmap
        } finally {
            EGL14.eglTerminate(display)
        }
    }

    private fun drawPreset(preset: ShaderPreset): Bitmap {
        val sourceBitmap = TestPatternBitmap.generate(WIDTH, HEIGHT)

        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textures[0])
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, sourceBitmap, 0)

        val program = GlProgram(ShaderPreset.VERTEX_SHADER, ShaderPreset.buildFragmentShader(preset, curvatureEnabled = false))
        program.use()
        program.setSamplerTexIdUniform("uTexSampler", textures[0], 0)
        if (preset.usesResolutionX) program.setFloatUniform("uResolutionX", WIDTH.toFloat())
        if (preset.usesResolutionY) program.setFloatUniform("uResolutionY", HEIGHT.toFloat())
        if (preset.usesTime) program.setFloatUniform("uTime", 0f)
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
        GLES20.glViewport(0, 0, WIDTH, HEIGHT)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        val buffer = ByteBuffer.allocateDirect(WIDTH * HEIGHT * 4).order(ByteOrder.nativeOrder())
        GLES20.glReadPixels(0, 0, WIDTH, HEIGHT, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buffer)
        buffer.rewind()
        val raw = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        raw.copyPixelsFromBuffer(buffer)

        program.delete()
        GLES20.glDeleteTextures(1, textures, 0)
        sourceBitmap.recycle()

        // glReadPixels reads bottom-to-top; flip vertically to get a normal image.
        val flip = Matrix().apply { postScale(1f, -1f, WIDTH / 2f, HEIGHT / 2f) }
        val flipped = Bitmap.createBitmap(raw, 0, 0, WIDTH, HEIGHT, flip, false)
        if (flipped !== raw) raw.recycle()
        return flipped
    }
}
