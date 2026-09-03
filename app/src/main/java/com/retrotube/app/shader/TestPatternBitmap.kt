package com.retrotube.app.shader

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint

/**
 * A self-drawn (no copyright concerns) test card used to render preset preview
 * thumbnails: color bars for mask/chroma separation, a gray/white strip and
 * text for luma/edge detail, giving every preset something representative to
 * visibly act on (scanlines, chroma smear, composite bleed, etc).
 */
object TestPatternBitmap {

    fun generate(width: Int, height: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint()

        val barColors = intArrayOf(
            Color.WHITE, Color.YELLOW, Color.CYAN, Color.GREEN,
            Color.MAGENTA, Color.RED, Color.BLUE,
        )
        val barWidth = width.toFloat() / barColors.size
        val barsBottom = height * 0.65f
        for ((i, color) in barColors.withIndex()) {
            paint.color = color
            canvas.drawRect(i * barWidth, 0f, (i + 1) * barWidth, barsBottom, paint)
        }

        paint.color = Color.DKGRAY
        canvas.drawRect(0f, barsBottom, width.toFloat(), height * 0.85f, paint)

        paint.color = Color.WHITE
        paint.isFakeBoldText = true
        paint.textSize = height * 0.14f
        canvas.drawText("RETROTUBE", width * 0.04f, height * 0.97f, paint)

        return bitmap
    }
}
