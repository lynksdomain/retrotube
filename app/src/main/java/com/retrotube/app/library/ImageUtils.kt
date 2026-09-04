package com.retrotube.app.library

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri

/** Shared by anything that lets a user pick an arbitrary photo as artwork
 *  (collection posters, custom video thumbnails) -- a full-resolution phone
 *  photo is far more pixels than a poster card will ever need, and would sit
 *  fully decoded in memory for every visible card otherwise. */
object ImageUtils {
    fun decodeSampledBitmap(context: Context, uri: Uri, maxDimension: Int = 720): Bitmap? {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) } ?: return null

        var sampleSize = 1
        while (bounds.outWidth / sampleSize > maxDimension || bounds.outHeight / sampleSize > maxDimension) {
            sampleSize *= 2
        }

        val decodeOptions = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        return resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, decodeOptions) }
    }
}
