package com.retrotube.app.subtitle.vobsub

import android.graphics.Bitmap
import android.graphics.Color
import androidx.media3.common.C
import androidx.media3.common.text.Cue
import androidx.media3.common.util.Consumer
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.text.CuesWithTiming
import androidx.media3.extractor.text.SubtitleParser
import com.google.common.collect.ImmutableList

/**
 * VobSub (DVD subpicture) decoder -- the one bitmap subtitle format Media3
 * doesn't ship a parser for out of the box. PGS (Blu-ray) already has one,
 * androidx.media3.extractor.text.pgs.PgsParser -- confirmed by inspecting the
 * actual media3-extractor-1.4.1.aar, not assumed, so it needed no work here.
 *
 * Each Matroska block for an S_VOBSUB track is one full SPU packet -- same
 * layout as a raw .sub file packet: a 2-bit-per-pixel RLE-coded bitmap
 * (top field then bottom field, DVD being interlaced) plus a short control
 * sequence carrying the display area and a 4-entry palette/alpha mapping.
 * The reference algorithm here (nibble-accumulating variable-length RLE
 * codes, the SET_COLOR/SET_CONTR nibble layout, SET_DAREA's packed 12-bit
 * fields) is the same one nearly every open-source VobSub reader is derived
 * from (mplayer's spudec.c, VLC, ffmpeg's dvdsubdec.c) -- but it has NOT been
 * verified against a real .mkv+VobSub sample file, only against the published
 * format description, unlike the rest of this app's logic which has either
 * unit tests or on-device verification behind it. Treat it as "should work,
 * not yet proven" until tried against a real file.
 *
 * Reference frame is assumed to be 720x480 (NTSC DVD) for normalizing pixel
 * coordinates into Cue's fractional [0,1] positions -- a PAL rip (720x576)
 * will sit slightly high as a result; there's no way to tell which from the
 * SPU packet alone.
 */
@UnstableApi
class VobSubParser(private val palette: IntArray) : SubtitleParser {

    override fun getCueReplacementBehavior(): Int = CUE_REPLACEMENT_BEHAVIOR

    override fun parse(
        data: ByteArray,
        offset: Int,
        length: Int,
        outputOptions: SubtitleParser.OutputOptions,
        output: Consumer<CuesWithTiming>,
    ) {
        val cue = runCatching { decodePacket(data, offset, length) }.getOrNull()
        val cues = if (cue != null) ImmutableList.of(cue) else ImmutableList.of<Cue>()
        output.accept(CuesWithTiming(cues, C.TIME_UNSET, C.TIME_UNSET))
    }

    private fun decodePacket(data: ByteArray, offset: Int, length: Int): Cue? {
        val buffer = ParsableByteArray(data, offset + length)
        buffer.setPosition(offset)
        buffer.readUnsignedShort() // total packet size, unused -- the container already delineates this block
        val controlOffset = buffer.readUnsignedShort()
        if (controlOffset < offset || controlOffset >= offset + length) return null

        buffer.setPosition(controlOffset)
        buffer.readUnsignedShort() // DATE -- timing comes from the container sample instead
        buffer.readUnsignedShort() // NEXT -- only the first control sequence in the packet is read
        val event = parseControlSequence(buffer) ?: return null

        val width = event.x2 - event.x1 + 1
        val height = event.y2 - event.y1 + 1
        if (width <= 0 || height <= 0 || event.topFieldOffset < 0 || event.bottomFieldOffset < 0) return null

        val pixels = IntArray(width * height)
        decodeField(data, offset + event.topFieldOffset, width, height, rowStart = 0, pixels, event)
        decodeField(data, offset + event.bottomFieldOffset, width, height, rowStart = 1, pixels, event)

        val bitmap = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
        return Cue.Builder()
            .setBitmap(bitmap)
            .setPosition(event.x1.toFloat() / REFERENCE_WIDTH)
            .setPositionAnchor(Cue.ANCHOR_TYPE_START)
            .setLine(event.y1.toFloat() / REFERENCE_HEIGHT, Cue.LINE_TYPE_FRACTION)
            .setLineAnchor(Cue.ANCHOR_TYPE_START)
            .setSize(width.toFloat() / REFERENCE_WIDTH)
            .setBitmapHeight(height.toFloat() / REFERENCE_HEIGHT)
            .build()
    }

    private class ControlEvent(
        val x1: Int,
        val x2: Int,
        val y1: Int,
        val y2: Int,
        val topFieldOffset: Int,
        val bottomFieldOffset: Int,
        val pixelToPaletteIndex: IntArray,
        val pixelToAlpha: IntArray,
    )

    private fun parseControlSequence(buffer: ParsableByteArray): ControlEvent? {
        var x1 = 0
        var x2 = 0
        var y1 = 0
        var y2 = 0
        var topFieldOffset = -1
        var bottomFieldOffset = -1
        val pixelToPaletteIndex = intArrayOf(0, 1, 2, 3)
        val pixelToAlpha = intArrayOf(15, 15, 15, 15)

        while (buffer.bytesLeft() > 0) {
            when (val command = buffer.readUnsignedByte()) {
                0x00, 0x01, 0x02 -> Unit // force-start / start / stop -- no operand, timing handled by the container
                0x03 -> {
                    val b0 = buffer.readUnsignedByte()
                    val b1 = buffer.readUnsignedByte()
                    pixelToPaletteIndex[3] = (b0 shr 4) and 0xF
                    pixelToPaletteIndex[2] = b0 and 0xF
                    pixelToPaletteIndex[1] = (b1 shr 4) and 0xF
                    pixelToPaletteIndex[0] = b1 and 0xF
                }
                0x04 -> {
                    val b0 = buffer.readUnsignedByte()
                    val b1 = buffer.readUnsignedByte()
                    pixelToAlpha[3] = (b0 shr 4) and 0xF
                    pixelToAlpha[2] = b0 and 0xF
                    pixelToAlpha[1] = (b1 shr 4) and 0xF
                    pixelToAlpha[0] = b1 and 0xF
                }
                0x05 -> {
                    val b0 = buffer.readUnsignedByte()
                    val b1 = buffer.readUnsignedByte()
                    val b2 = buffer.readUnsignedByte()
                    val b3 = buffer.readUnsignedByte()
                    val b4 = buffer.readUnsignedByte()
                    val b5 = buffer.readUnsignedByte()
                    x1 = (b0 shl 4) or (b1 shr 4)
                    x2 = ((b1 and 0xF) shl 8) or b2
                    y1 = (b3 shl 4) or (b4 shr 4)
                    y2 = ((b4 and 0xF) shl 8) or b5
                }
                0x06 -> {
                    topFieldOffset = buffer.readUnsignedShort()
                    bottomFieldOffset = buffer.readUnsignedShort()
                }
                0xFF -> return ControlEvent(x1, x2, y1, y2, topFieldOffset, bottomFieldOffset, pixelToPaletteIndex, pixelToAlpha)
                else -> return null
            }
        }
        return null
    }

    /** Decodes every other row (interlaced field), each row an independently
     *  byte-aligned run of variable-length nibble-coded runs. [rowStart] is 0
     *  for the top field, 1 for the bottom. */
    private fun decodeField(data: ByteArray, byteOffset: Int, width: Int, height: Int, rowStart: Int, pixels: IntArray, event: ControlEvent) {
        var nibbleOffset = byteOffset * 2
        var y = rowStart
        while (y < height) {
            var x = 0
            while (x < width) {
                val (runLength, colorValue) = readRun(data, nibbleOffset)
                nibbleOffset = runLength.second
                val length = if (runLength.first == 0) width - x else minOf(runLength.first, width - x)
                val paletteIndex = event.pixelToPaletteIndex[colorValue]
                val alpha4 = event.pixelToAlpha[colorValue]
                val argb = if (alpha4 == 0) 0 else Color.argb(alpha4 * 17, Color.red(palette[paletteIndex]), Color.green(palette[paletteIndex]), Color.blue(palette[paletteIndex]))
                for (i in 0 until length) pixels[y * width + x + i] = argb
                x += length
            }
            // Byte-align before the next row -- each row's RLE data starts on a byte boundary.
            if (nibbleOffset % 2 != 0) nibbleOffset++
            y += 2
        }
    }

    /** Reads one variable-length RLE code: up to 4 nibbles, MSB-first, stopping
     *  as soon as the accumulated value clears that nibble count's threshold
     *  (4, 16, 64, then always stop at 4 nibbles) -- the standard VobSub/ffmpeg
     *  dvdsub RLE code. Returns ((runLength, colorValue), newNibbleOffset). A
     *  runLength of 0 means "the rest of this line." */
    private fun readRun(data: ByteArray, startNibbleOffset: Int): Pair<Pair<Int, Int>, Int> {
        var nibbleOffset = startNibbleOffset
        var value = nibble(data, nibbleOffset)
        nibbleOffset++
        if (value < 0x4) {
            value = (value shl 4) or nibble(data, nibbleOffset)
            nibbleOffset++
            if (value < 0x10) {
                value = (value shl 4) or nibble(data, nibbleOffset)
                nibbleOffset++
                if (value < 0x40) {
                    value = (value shl 4) or nibble(data, nibbleOffset)
                    nibbleOffset++
                }
            }
        }
        val runLength = value shr 2
        val colorValue = value and 3
        return (runLength to colorValue) to nibbleOffset
    }

    private fun nibble(data: ByteArray, nibbleOffset: Int): Int {
        val byteIndex = nibbleOffset / 2
        if (byteIndex >= data.size) return 0
        val byteValue = data[byteIndex].toInt() and 0xFF
        return if (nibbleOffset % 2 == 0) (byteValue shr 4) and 0xF else byteValue and 0xF
    }

    companion object {
        /** Bitmap subtitle formats replace the previous cue outright rather than
         *  merging with it -- same semantics PgsParser uses for the same reason
         *  (a new bitmap update is a whole new image, not an incremental edit). */
        private const val CUE_REPLACEMENT_BEHAVIOR = 2
        private const val REFERENCE_WIDTH = 720f
        private const val REFERENCE_HEIGHT = 480f

        /** Parses the "palette: rrggbb, rrggbb, ..." line out of the S_VOBSUB
         *  track's codec-private data (the .idx file's own text, passed through
         *  by MatroskaExtractor as the format's first initializationData entry). */
        fun parsePalette(initializationData: List<ByteArray>): IntArray {
            val fallback = IntArray(16) { Color.BLACK }
            val raw = initializationData.firstOrNull() ?: return fallback
            val text = String(raw, Charsets.ISO_8859_1)
            val line = text.lineSequence().firstOrNull { it.trim().startsWith("palette:", ignoreCase = true) } ?: return fallback
            val entries = line.substringAfter(":").split(",").map { it.trim() }
            val palette = IntArray(16) { Color.BLACK }
            for (i in entries.indices) {
                if (i >= 16) break
                val rgb = entries[i].toIntOrNull(16) ?: continue
                palette[i] = 0xFF000000.toInt() or rgb
            }
            return palette
        }
    }
}
