package com.retrotube.app.library

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.LruCache
import android.view.animation.AlphaAnimation
import android.view.animation.Animation
import android.widget.ImageView
import com.retrotube.app.network.NetworkShare
import com.retrotube.app.network.NetworkShareRepository
import com.retrotube.app.network.SmbClient
import com.retrotube.app.network.SmbMediaDataSource
import jcifs.smb.SmbFile
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Extracts a single frame per video file as a thumbnail, off the main thread, with
 * two layers of caching so the same frame is never decoded twice: an in-memory
 * LruCache for the current session, and a disk cache (keyed by URI) that survives
 * app restarts -- the point that matters most for SMB, where a cache miss means a
 * real network download, not just a local decode.
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

    private const val SAF_TIMEOUT_MS = 3_000L
    private const val SMB_TIMEOUT_MS = 15_000L

    /** How far into the video to aim for -- late enough that logos, black cold-opens,
     *  and fade-ins are very likely behind us. */
    private const val TARGET_SNAPSHOT_US = 20_000_000L

    /** SMB has no notion of "decode this one frame remotely" (see [extractSmbFrame]),
     *  so how much of the file to download is sized from the file's own average
     *  bitrate (duration/size, read cheaply over SMB metadata parsing) rather than a
     *  single fixed guess -- a flat number is either wasteful for a low-bitrate TV
     *  episode or too small to reach 20s into a high-bitrate 4K movie. */
    private const val PREFIX_SAFETY_FACTOR = 1.3
    private const val MIN_PREFIX_BYTES = 6L * 1024 * 1024
    private const val MAX_PREFIX_BYTES = 48L * 1024 * 1024
    private const val DEFAULT_PREFIX_BYTES = 16L * 1024 * 1024

    private val decodeExecutor = Executors.newFixedThreadPool(4)
    private val waiterExecutor = Executors.newFixedThreadPool(4)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val memoryCache = LruCache<String, Bitmap>(64)

    fun load(context: Context, uri: Uri, imageView: ImageView) {
        loadInternal(context, uri.toString(), imageView, SAF_TIMEOUT_MS) { extractFrame(context, uri) }
    }

    fun loadSmb(context: Context, shareRepository: NetworkShareRepository, key: String, shareId: String, relativePath: String, imageView: ImageView) {
        loadInternal(context, key, imageView, SMB_TIMEOUT_MS) { extractSmbFrame(context, shareRepository, shareId, relativePath) }
    }

    private fun loadInternal(context: Context, key: String, imageView: ImageView, timeoutMs: Long, extract: () -> Bitmap?) {
        imageView.tag = key

        val memCached = memoryCache.get(key)
        if (memCached != null) {
            imageView.clearAnimation()
            imageView.alpha = 1f
            imageView.setImageBitmap(memCached)
            return
        }

        imageView.setImageDrawable(null)
        startLoadingPulse(imageView)
        val diskFile = diskCacheFile(context, key)
        val future = decodeExecutor.submit(Callable {
            val fromDisk = if (diskFile.exists()) {
                runCatching { BitmapFactory.decodeFile(diskFile.absolutePath) }.getOrNull()
            } else {
                null
            }
            fromDisk ?: extract()?.also { saveToDiskCache(diskFile, it) }
        })

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
                memoryCache.put(key, bitmap)
            }
            mainHandler.post {
                if (imageView.tag == key) {
                    imageView.clearAnimation()
                    imageView.alpha = 1f
                    imageView.setImageBitmap(bitmap)
                }
            }
        }
    }

    /** A slow breathing fade on the placeholder itself -- distinguishes "still working
     *  on it" from a poster that just happens to be a dark or black frame, which the
     *  static placeholder color alone couldn't. */
    private fun startLoadingPulse(view: ImageView) {
        val animation = AlphaAnimation(0.35f, 0.85f).apply {
            duration = 650
            repeatMode = Animation.REVERSE
            repeatCount = Animation.INFINITE
        }
        view.startAnimation(animation)
    }

    private fun diskCacheFile(context: Context, key: String): File {
        val dir = File(context.cacheDir, "auto_thumbnails").apply { mkdirs() }
        return File(dir, "${key.hashCode()}.png")
    }

    private fun saveToDiskCache(file: File, bitmap: Bitmap) {
        runCatching {
            FileOutputStream(file).use { out -> bitmap.compress(Bitmap.CompressFormat.PNG, 90, out) }
        }
    }

    private fun extractFrame(context: Context, uri: Uri): Bitmap? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            retriever.getFrameAtTime(TARGET_SNAPSHOT_US, MediaMetadataRetriever.OPTION_CLOSEST)
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
     * limitation with that I/O path, not something fixable from here. Downloading a
     * bitrate-sized prefix of the file to a local temp file and decoding *that* through the
     * same file-based path used for SAF videos sidesteps the bridge entirely and is reliable.
     */
    private fun extractSmbFrame(context: Context, shareRepository: NetworkShareRepository, shareId: String, relativePath: String): Bitmap? {
        val share = shareRepository.get(shareId) ?: return null
        val prefixBytes = estimatePrefixBytes(share, relativePath)
        val tempFile = File.createTempFile("smb_thumb_", ".tmp", context.cacheDir)
        return try {
            if (!downloadPrefix(share, relativePath, tempFile, prefixBytes)) return null

            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(tempFile.absolutePath)
                retriever.getFrameAtTime(TARGET_SNAPSHOT_US, MediaMetadataRetriever.OPTION_CLOSEST)
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

    /** Sized from the file's own average bitrate rather than a fixed guess, since a flat
     *  number can't serve both a low-bitrate TV episode and a high-bitrate 4K movie well --
     *  falls back to a flat default if the duration probe itself fails for any reason. */
    private fun estimatePrefixBytes(share: NetworkShare, relativePath: String): Long {
        val dataSource = SmbMediaDataSource(share, relativePath)
        return dataSource.use {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(it)
                val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull()
                val fileSize = it.fileSize
                if (durationMs == null || durationMs <= 0 || fileSize <= 0) {
                    DEFAULT_PREFIX_BYTES
                } else {
                    val bytesPerMs = fileSize.toDouble() / durationMs
                    val estimated = (bytesPerMs * (TARGET_SNAPSHOT_US / 1000.0) * PREFIX_SAFETY_FACTOR).toLong()
                    estimated.coerceIn(MIN_PREFIX_BYTES, MAX_PREFIX_BYTES)
                }
            } catch (e: Exception) {
                DEFAULT_PREFIX_BYTES
            } finally {
                retriever.release()
            }
        }
    }

    private fun downloadPrefix(share: NetworkShare, relativePath: String, destination: File, prefixBytes: Long): Boolean {
        val smbFile = SmbFile("${share.rootUrl}$relativePath", SmbClient.contextFor(share))
        var totalCopied = 0L
        smbFile.inputStream.use { input ->
            destination.outputStream().use { output ->
                val buffer = ByteArray(64 * 1024)
                while (totalCopied < prefixBytes) {
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
