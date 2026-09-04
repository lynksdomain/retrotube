package com.retrotube.app.tv

import android.net.Uri

data class TvChannelVideo(val uri: Uri, val displayName: String)

/** A channel is nothing but a name and an ordered list of videos -- there's no
 *  schedule, no time-of-day logic, just "what's the current position in this
 *  list," tracked by [TvChannelRepository]. */
data class TvChannel(
    val id: String,
    val number: Int,
    val name: String,
    val videos: List<TvChannelVideo>,
)
