package com.retrotube.app.tv

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.Shader
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.View
import kotlin.math.hypot
import kotlin.random.Random

/**
 * Cheap "no signal" static for the moment between TV Mode channel changes --
 * a small buffer of random black/white/gray pixels, redrawn a few times a
 * second and stretched with nearest-neighbor sampling so it stays blocky
 * rather than blurring into gray, the way an analog tuner's snow looks.
 * Deliberately not a GL shader: it only needs to run for a few hundred
 * milliseconds between videos, so a plain Canvas bitmap is simpler and only
 * has to look right for that long.
 *
 * Scanlines + a vignette are drawn on top -- a cheap Canvas approximation of
 * the zfast-crt look every TV Mode channel plays with (see
 * [com.retrotube.app.settings.VideoEffectSettings.TV_MODE]), so the static
 * doesn't read as a different, uncorrected layer from the video it's
 * covering for.
 */
class TvStaticView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    companion object {
        private const val NOISE_WIDTH = 160
        private const val NOISE_HEIGHT = 90
        private const val FRAME_INTERVAL_MS = 60L
        private const val SCANLINE_SPACING_PX = 3f
    }

    private val noiseBitmap = Bitmap.createBitmap(NOISE_WIDTH, NOISE_HEIGHT, Bitmap.Config.ARGB_8888)
    private val pixels = IntArray(NOISE_WIDTH * NOISE_HEIGHT)
    private val paint = Paint().apply { isFilterBitmap = false }
    private val scanlinePaint = Paint().apply { color = 0x30000000 }
    private var vignettePaint: Paint? = null
    private val destRect = Rect()
    private val handler = Handler(Looper.getMainLooper())
    private var running = false

    private val frameTick = object : Runnable {
        override fun run() {
            if (!running) return
            drawNoiseFrame()
            invalidate()
            handler.postDelayed(this, FRAME_INTERVAL_MS)
        }
    }

    private fun drawNoiseFrame() {
        for (i in pixels.indices) {
            val gray = Random.nextInt(256)
            pixels[i] = (0xFF shl 24) or (gray shl 16) or (gray shl 8) or gray
        }
        noiseBitmap.setPixels(pixels, 0, NOISE_WIDTH, 0, 0, NOISE_WIDTH, NOISE_HEIGHT)
    }

    fun start() {
        if (running) return
        running = true
        handler.post(frameTick)
    }

    fun stop() {
        running = false
        handler.removeCallbacks(frameTick)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val radius = hypot(w / 2f, h / 2f)
        vignettePaint = Paint().apply {
            shader = RadialGradient(
                w / 2f, h / 2f, radius,
                0x00000000, 0x99000000.toInt(),
                Shader.TileMode.CLAMP,
            )
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        destRect.set(0, 0, width, height)
        canvas.drawBitmap(noiseBitmap, null, destRect, paint)

        var y = 0f
        while (y < height) {
            canvas.drawRect(0f, y, width.toFloat(), y + 1f, scanlinePaint)
            y += SCANLINE_SPACING_PX
        }

        vignettePaint?.let { canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), it) }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        stop()
    }
}
