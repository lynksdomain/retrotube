package com.retrotube.app.network

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import jcifs.smb.SmbFile
import jcifs.smb.SmbRandomAccessFile
import java.io.IOException

/**
 * Lets ExoPlayer read an SMB file directly, via jcifs-ng's random-access file
 * handle -- which is what makes seeking within the video actually work,
 * rather than only ever being able to stream from the start.
 *
 * Container parsers (MKV especially) read in a mix of tiny, sometimes
 * single-byte chunks while sniffing the format -- fine against a local file,
 * but each one would otherwise be its own SMB round trip. A private read-ahead
 * buffer absorbs that: real network reads only happen in [BUFFER_SIZE]
 * chunks, and small requests are served out of memory in between.
 */
@UnstableApi
class SmbDataSource(private val shareRepository: NetworkShareRepository) : BaseDataSource(/* isNetwork= */ true) {

    private var randomAccessFile: SmbRandomAccessFile? = null
    private var openUri: Uri? = null
    private var bytesRemaining: Long = 0

    private val readAheadBuffer = ByteArray(BUFFER_SIZE)
    private var bufferValidBytes = 0
    private var bufferPosition = 0

    override fun open(dataSpec: DataSpec): Long {
        val (shareId, relativePath) = SmbUri.parse(dataSpec.uri)
            ?: throw IOException("Not an smb:// uri: ${dataSpec.uri}")
        val share = shareRepository.get(shareId)
            ?: throw IOException("Unknown or removed network share")

        transferInitializing(dataSpec)

        val smbFile = SmbFile("${share.rootUrl}$relativePath", SmbClient.contextFor(share))
        val raf = smbFile.openRandomAccess("r")
        raf.seek(dataSpec.position)

        randomAccessFile = raf
        openUri = dataSpec.uri
        bufferValidBytes = 0
        bufferPosition = 0

        bytesRemaining = if (dataSpec.length != C.LENGTH_UNSET.toLong()) {
            dataSpec.length
        } else {
            raf.length() - dataSpec.position
        }
        if (bytesRemaining < 0) throw IOException("Requested range starts past the end of the file")

        transferStarted(dataSpec)
        return bytesRemaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT

        if (bufferPosition >= bufferValidBytes) {
            val raf = randomAccessFile ?: throw IOException("Data source not open")
            val toFetch = minOf(readAheadBuffer.size.toLong(), bytesRemaining).toInt()
            val fetched = raf.read(readAheadBuffer, 0, toFetch)
            if (fetched <= 0) throw IOException("Unexpected end of SMB stream")
            bufferValidBytes = fetched
            bufferPosition = 0
        }

        val bytesToCopy = minOf(length, bufferValidBytes - bufferPosition)
        System.arraycopy(readAheadBuffer, bufferPosition, buffer, offset, bytesToCopy)
        bufferPosition += bytesToCopy

        bytesRemaining -= bytesToCopy
        bytesTransferred(bytesToCopy)
        return bytesToCopy
    }

    override fun getUri(): Uri? = openUri

    override fun close() {
        openUri = null
        try {
            randomAccessFile?.close()
        } finally {
            randomAccessFile = null
            transferEnded()
        }
    }

    class Factory(private val shareRepository: NetworkShareRepository) : DataSource.Factory {
        override fun createDataSource(): DataSource = SmbDataSource(shareRepository)
    }

    companion object {
        private const val BUFFER_SIZE = 64 * 1024
    }
}
