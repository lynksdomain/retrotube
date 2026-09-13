package com.retrotube.app.library

import android.content.Context
import kotlin.math.roundToInt

/** Every card grid in the app (Library's buckets, Sources' folders/videos, a
 *  bucket's own content, season/episode grids) was hardcoded to a fixed 3
 *  columns -- fine on a narrow phone, but on a wider/landscape screen that
 *  stretches each 5:6 card so tall that even the first row needs a vertical
 *  scroll to see in full. Spans are computed from the actual screen width
 *  instead, targeting a fixed tile width so tile height (and so row height)
 *  stays consistent across screen sizes. One shared target width/ratio for
 *  every card grid, not a per-screen tweak -- a card looks and sizes the
 *  same everywhere it shows up, folder or poster or bucket alike (see the
 *  matching 5:6 `layout_constraintDimensionRatio` on item_folder.xml,
 *  item_video.xml, item_poster_tile.xml and item_bucket_tile.xml). */
object GridSpanCalculator {
    private const val TARGET_TILE_WIDTH_DP = 170
    private const val MIN_SPAN_COUNT = 3

    fun spanCount(context: Context, targetTileWidthDp: Int = TARGET_TILE_WIDTH_DP): Int {
        val metrics = context.resources.displayMetrics
        val screenWidthDp = metrics.widthPixels / metrics.density
        return (screenWidthDp / targetTileWidthDp).roundToInt().coerceAtLeast(MIN_SPAN_COUNT)
    }
}
