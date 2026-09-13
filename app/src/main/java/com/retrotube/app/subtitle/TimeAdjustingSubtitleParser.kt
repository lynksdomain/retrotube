package com.retrotube.app.subtitle

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.util.Consumer
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.text.CuesWithTiming
import androidx.media3.extractor.text.SubtitleParser

/**
 * Applies the per-video delay/speed sync settings (see
 * [com.retrotube.app.metadata.VideoMetadataRepository]) to a delegate
 * parser's output. Only works for formats whose parser reports a real,
 * absolute [CuesWithTiming.startTimeUs] -- that's every batch-parsed text
 * format (SRT/VTT/SSA/ASS, whether sidecar or embedded), since the whole
 * file is parsed up front with timestamps relative to the media's own t=0.
 * Per-sample formats (PGS, VobSub) report [C.TIME_UNSET] and rely on the
 * container's own sample timestamp instead -- there's no absolute time to
 * adjust at the parser level for those, so they pass through unshifted.
 * That's a deliberate, reasonable split: PGS/VobSub come straight off a
 * disc and are already in sync with the video; a downloaded or hand-typed
 * SRT is the actual out-of-sync case these controls exist for.
 */
@UnstableApi
class TimeAdjustingSubtitleParser(
    private val delegate: SubtitleParser,
    private val delaySeconds: Float,
    private val speedMultiplier: Float,
) : SubtitleParser {

    override fun getCueReplacementBehavior(): Int = delegate.cueReplacementBehavior

    override fun parse(
        data: ByteArray,
        offset: Int,
        length: Int,
        outputOptions: SubtitleParser.OutputOptions,
        output: Consumer<CuesWithTiming>,
    ) {
        delegate.parse(data, offset, length, outputOptions) { cuesWithTiming ->
            output.accept(adjust(cuesWithTiming))
        }
    }

    override fun reset() {
        delegate.reset()
    }

    private fun adjust(original: CuesWithTiming): CuesWithTiming {
        if (original.startTimeUs == C.TIME_UNSET || (delaySeconds == 0f && speedMultiplier == 1f)) return original
        val delayUs = (delaySeconds * C.MICROS_PER_SECOND).toLong()
        val newStart = (original.startTimeUs / speedMultiplier).toLong() + delayUs
        val newDuration = if (original.durationUs == C.TIME_UNSET) {
            C.TIME_UNSET
        } else {
            (original.durationUs / speedMultiplier).toLong()
        }
        return CuesWithTiming(original.cues, newStart, newDuration)
    }
}

/** Wraps any other [SubtitleParser.Factory] (e.g. [com.retrotube.app.subtitle.vobsub.VobSubParserFactory])
 *  with [TimeAdjustingSubtitleParser], so delay/speed apply on top of whatever
 *  format-specific parsing already happens. */
@UnstableApi
class AdjustableSubtitleParserFactory(
    private val delegateFactory: SubtitleParser.Factory,
    private val delaySeconds: Float,
    private val speedMultiplier: Float,
) : SubtitleParser.Factory {

    override fun supportsFormat(format: Format): Boolean = delegateFactory.supportsFormat(format)

    override fun getCueReplacementBehavior(format: Format): Int = delegateFactory.getCueReplacementBehavior(format)

    override fun create(format: Format): SubtitleParser =
        TimeAdjustingSubtitleParser(delegateFactory.create(format), delaySeconds, speedMultiplier)
}
