package com.retrotube.app.network

import android.content.Context
import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.TransferListener

/**
 * Routes each request to [SmbDataSource] for an `smb://` URI, or a normal
 * [DefaultDataSource] (`content://`, `file://`, ...) for everything else --
 * lets one [androidx.media3.exoplayer.source.DefaultMediaSourceFactory] (and
 * its automatic merging of a [androidx.media3.common.MediaItem]'s attached
 * subtitle configurations into the player's actual track set) handle a local
 * video and an SMB video the same way.
 *
 * Before this, an SMB video was played through a hand-built
 * [androidx.media3.exoplayer.source.ProgressiveMediaSource] using
 * [SmbDataSource.Factory] directly -- the one path that never went through
 * [androidx.media3.exoplayer.source.DefaultMediaSourceFactory]'s subtitle
 * merging at all, so a sidecar (or any other external subtitle) got resolved
 * and attached to the `MediaItem` correctly, downloaded correctly, and then
 * never actually showed up as a selectable track: confirmed on-device, not
 * assumed -- `Tracks` came back with zero text groups despite the attached
 * config, for every SMB video, regardless of whether a sidecar existed.
 *
 * Which concrete `DataSource` handles a request isn't known until [open] sees
 * the real [DataSpec.uri] (the main video and its sidecar, if any, are
 * different URIs resolved by the same factory instance), so this can't just
 * pick a factory upfront -- it has to decide fresh on every [open].
 */
@UnstableApi
class DispatchingDataSource(
    private val context: Context,
    private val shareRepository: NetworkShareRepository,
) : DataSource {

    private val transferListeners = mutableListOf<TransferListener>()
    private var delegate: DataSource? = null

    override fun open(dataSpec: DataSpec): Long {
        val chosen: DataSource = if (SmbUri.parse(dataSpec.uri) != null) {
            SmbDataSource(shareRepository)
        } else {
            DefaultDataSource.Factory(context).createDataSource()
        }
        transferListeners.forEach { chosen.addTransferListener(it) }
        delegate = chosen
        return chosen.open(dataSpec)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        delegate?.read(buffer, offset, length) ?: -1

    override fun getUri(): Uri? = delegate?.uri

    override fun close() {
        delegate?.close()
        delegate = null
    }

    override fun addTransferListener(transferListener: TransferListener) {
        transferListeners += transferListener
        delegate?.addTransferListener(transferListener)
    }

    class Factory(private val context: Context, private val shareRepository: NetworkShareRepository) : DataSource.Factory {
        override fun createDataSource(): DataSource = DispatchingDataSource(context, shareRepository)
    }
}
