package com.retrotube.app.subtitle.vobsub

import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.text.DefaultSubtitleParserFactory
import androidx.media3.extractor.text.SubtitleParser

/** Adds VobSub support on top of everything Media3's own [DefaultSubtitleParserFactory]
 *  already handles (which, confirmed against the actual media3-extractor jar,
 *  already includes PGS) -- delegates every other format straight through. */
@UnstableApi
class VobSubParserFactory : SubtitleParser.Factory {

    private val default = DefaultSubtitleParserFactory()

    override fun supportsFormat(format: Format): Boolean =
        format.sampleMimeType == MimeTypes.APPLICATION_VOBSUB || default.supportsFormat(format)

    override fun getCueReplacementBehavior(format: Format): Int =
        if (format.sampleMimeType == MimeTypes.APPLICATION_VOBSUB) {
            VobSubParser(VobSubParser.parsePalette(format.initializationData)).cueReplacementBehavior
        } else {
            default.getCueReplacementBehavior(format)
        }

    override fun create(format: Format): SubtitleParser =
        if (format.sampleMimeType == MimeTypes.APPLICATION_VOBSUB) {
            VobSubParser(VobSubParser.parsePalette(format.initializationData))
        } else {
            default.create(format)
        }
}
