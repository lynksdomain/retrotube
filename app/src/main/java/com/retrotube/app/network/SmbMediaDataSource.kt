package com.retrotube.app.network

import android.media.MediaDataSource
import jcifs.smb.SmbFile
import jcifs.smb.SmbRandomAccessFile

/**
 * Lets [android.media.MediaMetadataRetriever] read container metadata (duration,
 * used to size the thumbnail prefix download) straight off SMB with no local
 * download at all. This is reliable for metadata parsing -- unlike actually
 * decoding a video frame through this same bridge, which fails at the native
 * layer on real hardware (see [com.retrotube.app.library.ThumbnailLoader]).
 * A small read-ahead cache keyed on the last fetched range absorbs the
 * retriever's small, sometimes-repeated reads into real network fetches.
 */
class SmbMediaDataSource(
    private val share: NetworkShare,
    private val relativePath: String,
) : MediaDataSource() {

    private val smbFile = SmbFile("${share.rootUrl}$relativePath", SmbClient.contextFor(share))
    val fileSize: Long by lazy { smbFile.length() }
    private var randomAccessFile: SmbRandomAccessFile? = null

    private val cacheBuffer = ByteArray(BUFFER_SIZE)
    private var cacheStart = -1L
    private var cacheLength = 0

    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        if (position >= fileSize) return -1

        if (cacheStart < 0 || position < cacheStart || position >= cacheStart + cacheLength) {
            val raf = randomAccessFile ?: smbFile.openRandomAccess("r").also { randomAccessFile = it }
            raf.seek(position)
            val toRead = minOf(BUFFER_SIZE.toLong(), fileSize - position).toInt()
            val read = raf.read(cacheBuffer, 0, toRead)
            if (read <= 0) return -1
            cacheStart = position
            cacheLength = read
        }

        val offsetInCache = (position - cacheStart).toInt()
        val bytesToCopy = minOf(size, cacheLength - offsetInCache)
        System.arraycopy(cacheBuffer, offsetInCache, buffer, offset, bytesToCopy)
        return bytesToCopy
    }

    override fun getSize(): Long = fileSize

    override fun close() {
        randomAccessFile?.close()
        randomAccessFile = null
    }

    companion object {
        private const val BUFFER_SIZE = 64 * 1024
    }
}
