package com.retrotube.app

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.retrotube.app.databinding.ActivityTvBrowseFolderBinding
import com.retrotube.app.library.LibraryItem
import com.retrotube.app.metadata.VideoMetadataRepository
import com.retrotube.app.network.NetworkShare
import com.retrotube.app.network.NetworkShareRepository
import com.retrotube.app.network.SmbBrowser
import com.retrotube.app.tv.TvBrowseRow
import com.retrotube.app.tv.TvBrowseRowAdapter
import com.retrotube.app.tv.TvChannelConfigRepository
import com.retrotube.app.tv.TvChannelSource
import java.util.concurrent.Executors

/**
 * Browses a connected SMB share's own folder tree, the same way
 * [TvChannelPickLocalFolderActivity] browses the local library -- the whole
 * share, any subfolder inside it, or an individual video while browsing, each
 * one tap away. Every folder listing is real network I/O, so it runs off the
 * main thread.
 */
class TvChannelPickSmbFolderActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_CHANNEL_ID = "channel_id"
    }

    private lateinit var binding: ActivityTvBrowseFolderBinding
    private lateinit var configRepository: TvChannelConfigRepository
    private lateinit var metadataRepository: VideoMetadataRepository
    private lateinit var adapter: TvBrowseRowAdapter
    private lateinit var channelId: String

    private val ioExecutor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    /** null means the virtual root level, showing every connected share. */
    private var currentShare: NetworkShare? = null
    private var currentPath: String = ""
    private val pathBackStack = mutableListOf<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent.getStringExtra(EXTRA_CHANNEL_ID)
        if (id == null) {
            finish()
            return
        }
        channelId = id

        binding = ActivityTvBrowseFolderBinding.inflate(layoutInflater)
        setContentView(binding.root)
        configRepository = TvChannelConfigRepository(this)
        metadataRepository = VideoMetadataRepository(this)

        adapter = TvBrowseRowAdapter()
        binding.browseList.layoutManager = LinearLayoutManager(this)
        binding.browseList.adapter = adapter

        binding.backButton.setOnClickListener { onBrowseBack() }
        render()
    }

    private fun onBrowseBack() {
        val share = currentShare
        if (share == null) {
            finish()
            return
        }
        val parentPath = pathBackStack.removeLastOrNull()
        if (parentPath != null) {
            currentPath = parentPath
            render()
        } else {
            currentShare = null
            currentPath = ""
            render()
        }
    }

    private fun render() {
        val share = currentShare
        binding.screenTitle.text = share?.let { currentPath.ifEmpty { it.displayName } } ?: getString(R.string.tv_pick_share_title)

        if (share != null) {
            binding.addFolderButton.visibility = View.VISIBLE
            val label = currentPath.substringAfterLast('/').ifEmpty { share.displayName }
            binding.addFolderButton.text = getString(R.string.tv_add_this_folder, label)
            binding.addFolderButton.setOnClickListener { addFolderSource(share, currentPath, label) }
        } else {
            binding.addFolderButton.visibility = View.GONE
        }

        if (share == null) {
            val shares = NetworkShareRepository(this).getAll()
            adapter.submitList(
                shares.map { s ->
                    TvBrowseRow(
                        label = "🌐 ${s.displayName}",
                        onLabelClick = { navigateIntoShare(s) },
                        addButtonLabel = getString(R.string.tv_add_button),
                        onAddClick = { addFolderSource(s, "", s.displayName) },
                    )
                },
            )
            binding.emptyStateText.visibility = if (shares.isEmpty()) View.VISIBLE else View.GONE
            return
        }

        loadChildrenAsync(share, currentPath)
    }

    private fun loadChildrenAsync(share: NetworkShare, path: String) {
        adapter.submitList(emptyList())
        ioExecutor.execute {
            val children = runCatching { SmbBrowser.listChildren(share, path) }.getOrDefault(emptyList())
            val currentSources = configRepository.getChannels().firstOrNull { it.id == channelId }?.sources.orEmpty()
            val rows = children.mapNotNull { child ->
                when (child) {
                    is LibraryItem.SmbFolderItem -> TvBrowseRow(
                        label = "📁 ${child.name}",
                        onLabelClick = { navigateIntoFolder(child.relativePath) },
                        addButtonLabel = getString(R.string.tv_add_button),
                        onAddClick = { addFolderSource(share, child.relativePath, child.name) },
                    )
                    is LibraryItem.SmbVideoItem -> {
                        val uriString = child.uri.toString()
                        val alreadyAdded = currentSources.any { it is TvChannelSource.Video && it.uri == uriString }
                        TvBrowseRow(
                            label = if (alreadyAdded) "✓ ${child.name}" else "▶ ${child.name}",
                            onLabelClick = { toggleVideoSource(uriString, child.name) },
                        )
                    }
                    else -> null
                }
            }
            mainHandler.post {
                if (currentShare == share && currentPath == path) {
                    adapter.submitList(rows)
                    binding.emptyStateText.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
                }
            }
        }
    }

    private fun navigateIntoShare(share: NetworkShare) {
        currentShare = share
        currentPath = ""
        pathBackStack.clear()
        render()
    }

    private fun navigateIntoFolder(path: String) {
        pathBackStack.add(currentPath)
        currentPath = path
        render()
    }

    private fun addFolderSource(share: NetworkShare, path: String, label: String) {
        configRepository.addSource(channelId, TvChannelSource.SmbFolder(share.id, path, label))
        Toast.makeText(this, getString(R.string.tv_source_added, label), Toast.LENGTH_SHORT).show()
    }

    private fun toggleVideoSource(uriString: String, name: String) {
        val currentSources = configRepository.getChannels().firstOrNull { it.id == channelId }?.sources.orEmpty()
        val alreadyAdded = currentSources.any { it is TvChannelSource.Video && it.uri == uriString }
        if (alreadyAdded) {
            configRepository.removeVideoSource(channelId, uriString)
        } else {
            val title = metadataRepository.getCustomTitle(uriString) ?: name
            configRepository.addVideoSourceIfAbsent(channelId, uriString, title)
        }
        render()
    }
}
