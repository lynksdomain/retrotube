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
import com.retrotube.app.network.NetworkShare
import com.retrotube.app.network.NetworkShareRepository
import com.retrotube.app.network.SmbClient
import jcifs.smb.SmbFile
import java.io.File
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
 * large library can't pile up unbounded blocked threads. SAF and SMB videos
 * share this same cache and pool, just with a different frame-extraction step.
 */
object ThumbnailLoader {

    private const val SAF_TIMEOUT_MS = 3_000L
    private const val SMB_TIMEOUT_MS = 10_000L

    /** How much of the file's start to pull over the network before decoding locally --
     *  see [extractSmbFrame] for why this exists instead of decoding straight off SMB. */
    private const val SMB_PARTIAL_DOWNLOAD_BYTES = 12L * 1024 * 1024

    private val decodeExecutor = Executors.newFixedThreadPool(4)
    private val waiterExecutor = Executors.newFixedThreadPool(4)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val cache = LruCache<String, Bitmap>(64)

    fun load(context: Context, uri: Uri, imageView: ImageView) {
        loadInternal(uri.toString(), imageView, SAF_TIMEOUT_MS) { extractFrame(context, uri) }
    }

    fun loadSmb(context: Context, shareRepository: NetworkShareRepository, key: String, shareId: String, relativePath: String, imageView: ImageView) {
        loadInternal(key, imageView, SMB_TIMEOUT_MS) { extractSmbFrame(context, shareRepository, shareId, relativePath) }
    }

    private fun loadInternal(key: String, imageView: ImageView, timeoutMs: Long, extract: () -> Bitmap?) {
        imageView.tag = key

        val cached = cache.get(key)
        if (cached != null) {
            imageView.setImageBitmap(cached)
            return
        }

        imageView.setImageDrawable(null)
        val future = decodeExecutor.submit(Callable { extract() })

        waiterExecutor.execute {
            val bitmap = try {
                future.get(timeoutMs, TimeUnit.MILLISECONDS)
            } catch (e: TimeoutException) {
                Log.w("ThumbnailLoader", "Timed out decoding frame for $key")
                future.cancel(true)
                null
            } catch (e: Exception) {
                Log.e("ThumbnailLoader", "Failed to extract frame for $key", e)
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

    /**
     * MediaMetadataRetriever.setDataSource(MediaDataSource) parses container metadata over
     * SMB fine (duration comes back correct), but actually decoding a frame through that
     * bridge consistently fails at the native layer on real hardware -- a platform/decoder
     * limitation with that I/O path, not something fixable from here. Downloading just the
     * file's first few megabytes to a local temp file and decoding *that* through the same
     * file-based path used for SAF videos sidesteps the bridge entirely and is reliable.
     * The tradeoff: this only ever gets a frame from very early in the file (whatever
     * decodes inside the downloaded prefix), not the "a few seconds in" frame the SAF path
     * prefers -- worth it for having a real thumbnail at all.
     */
    private fun extractSmbFrame(context: Context, shareRepository: NetworkShareRepository, shareId: String, relativePath: String): Bitmap? {
        val share = shareRepository.get(shareId) ?: return null
        val tempFile = File.createTempFile("smb_thumb_", ".tmp", context.cacheDir)
        return try {
            if (!downloadPrefix(share, relativePath, tempFile)) return null

            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(tempFile.absolutePath)
                // Same "skip the fade-in" preference as the SAF path -- the downloaded
                // prefix comfortably covers a few seconds of video at typical bitrates.
                retriever.getFrameAtTime(5_000_000L, MediaMetadataRetriever.OPTION_CLOSEST)
                    ?: retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    ?: retriever.frameAtTime
            } finally {
                retriever.release()
            }
        } catch (e: Exception) {
            null
        } finally {
            tempFile.delete()
        }
    }

    private fun downloadPrefix(share: NetworkShare, relativePath: String, destination: File): Boolean {
        val smbFile = SmbFile("${share.rootUrl}$relativePath", SmbClient.contextFor(share))
        var totalCopied = 0L
        smbFile.inputStream.use { input ->
            destination.outputStream().use { output ->
                val buffer = ByteArray(64 * 1024)
                while (totalCopied < SMB_PARTIAL_DOWNLOAD_BYTES) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    output.write(buffer, 0, read)
                    totalCopied += read
                }
            }
        }
        return totalCopied > 0
    }
}
