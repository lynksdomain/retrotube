package com.retrotube.app.library

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.LruCache
import android.widget.ImageView
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Extracts a single frame per video file as a thumbnail, off the main thread,
 * with a small in-memory cache. [imageView] is tagged with the URI it was
 * asked to load so a slow decode landing after the view has been recycled
 * for a different item doesn't clobber the wrong row.
 *
 * MediaMetadataRetriever is known to hang (not just fail) on certain
 * codec/container combinations instead of throwing -- so decoding runs on a
 * small worker pool while a bounded "waiter" pool enforces a hard timeout via
 * Future.get(), falling back to "no thumbnail" rather than blocking that row
 * forever. A stuck decode leaks its pool thread; both pools are bounded
 * (rather than spawning a fresh raw Thread per request) so fast-scrolling a
 * large library can't pile up unbounded blocked threads.
 */
object ThumbnailLoader {

    private const val TIMEOUT_MS = 3_000L

    private val decodeExecutor = Executors.newFixedThreadPool(4)
    private val waiterExecutor = Executors.newFixedThreadPool(4)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val cache = LruCache<String, Bitmap>(64)

    fun load(context: Context, uri: Uri, imageView: ImageView) {
        val key = uri.toString()
        imageView.tag = key

        val cached = cache.get(key)
        if (cached != null) {
            imageView.setImageBitmap(cached)
            return
        }

        imageView.setImageBitmap(null)
        val future = decodeExecutor.submit(Callable { extractFrame(context, uri) })

        waiterExecutor.execute {
            val bitmap = try {
                future.get(TIMEOUT_MS, TimeUnit.MILLISECONDS)
            } catch (e: TimeoutException) {
                Log.w("ThumbnailLoader", "Timed out decoding frame for $uri")
                future.cancel(true)
                null
            } catch (e: Exception) {
                Log.e("ThumbnailLoader", "Failed to extract frame for $uri", e)
                null
            }
            if (bitmap != null) {
                cache.put(key, bitmap)
            }
            mainHandler.post {
                if (imageView.tag == key) {
                    imageView.setImageBitmap(bitmap)
                }
            }
        }
    }

    private fun extractFrame(context: Context, uri: Uri): Bitmap? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            // Frame 0 is very often a black fade-in for anime intros; a few seconds in
            // is far more likely to land on actual content.
            retriever.getFrameAtTime(5_000_000L, MediaMetadataRetriever.OPTION_CLOSEST)
                ?: retriever.getFrameAtTime(0)
                ?: retriever.frameAtTime
        } catch (e: Exception) {
            null
        } finally {
            retriever.release()
        }
    }
}
