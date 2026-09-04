package com.retrotube.app.tv

import android.net.Uri

data class TvChannelVideo(val uri: Uri, val displayName: String)

/** A channel is nothing but a number and an ordered list of videos -- channels
 *  are identified purely by position (CH 1, CH 2, ...), never a name, so
 *  there's nothing here to keep in sync with reordering. No schedule, no
 *  time-of-day logic either, just "what's the current position in this
 *  list," tracked by [TvChannelRepository]. */
data class TvChannel(
    val id: String,
    val number: Int,
    val videos: List<TvChannelVideo>,
)
