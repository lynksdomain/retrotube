package com.retrotube.app

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.documentfile.provider.DocumentFile
import androidx.recyclerview.widget.LinearLayoutManager
import com.retrotube.app.databinding.ActivityTvBrowseFolderBinding
import com.retrotube.app.library.LibraryRepository
import com.retrotube.app.metadata.VideoMetadataRepository
import com.retrotube.app.tv.TvBrowseRow
import com.retrotube.app.tv.TvBrowseRowAdapter
import com.retrotube.app.tv.TvChannelConfigRepository
import com.retrotube.app.tv.TvChannelSource

/**
 * Browses the library's own folder tree (never a raw system file picker) so a
 * channel's local-folder source can be the whole library root, any subfolder
 * inside it, or -- while browsing -- an individual video, without forcing one
 * level of granularity on everyone. Every folder gets its own add button, so
 * "the whole show" and "just this one folder inside it" are both one tap from
 * wherever you're standing, not an all-or-nothing root pick.
 */
class TvChannelPickLocalFolderActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_CHANNEL_ID = "channel_id"
    }

    private lateinit var binding: ActivityTvBrowseFolderBinding
    private lateinit var configRepository: TvChannelConfigRepository
    private lateinit var metadataRepository: VideoMetadataRepository
    private lateinit var adapter: TvBrowseRowAdapter
    private lateinit var channelId: String

    /** null means the virtual root level, showing every library root folder. */
    private var currentFolder: DocumentFile? = null
    private val backStack = mutableListOf<DocumentFile>()

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
        val parent = backStack.removeLastOrNull()
        if (parent != null || currentFolder != null) {
            currentFolder = parent
            render()
        } else {
            finish()
        }
    }

    private fun render() {
        val folder = currentFolder
        binding.screenTitle.text = folder?.name ?: getString(R.string.tv_pick_folder_title)

        if (folder != null) {
            binding.addFolderButton.visibility = View.VISIBLE
            binding.addFolderButton.text = getString(R.string.tv_add_this_folder, folder.name ?: "")
            binding.addFolderButton.setOnClickListener { addFolderSource(folder) }
        } else {
            binding.addFolderButton.visibility = View.GONE
        }

        val rows = if (folder == null) {
            LibraryRepository(this).getRootDocuments().map { root ->
                TvBrowseRow(
                    label = "📁 ${root.name}",
                    onLabelClick = { navigateInto(root.document) },
                    addButtonLabel = getString(R.string.tv_add_button),
                    onAddClick = { addFolderSource(root.document) },
                )
            }
        } else {
            val currentSources = configRepository.getChannels().firstOrNull { it.id == channelId }?.sources.orEmpty()
            val children = runCatching { folder.listFiles() }.getOrDefault(emptyArray())
            children.sortedBy { (it.name ?: "").lowercase() }.mapNotNull { child ->
                when {
                    child.isDirectory -> TvBrowseRow(
                        label = "📁 ${child.name}",
                        onLabelClick = { navigateInto(child) },
                        addButtonLabel = getString(R.string.tv_add_button),
                        onAddClick = { addFolderSource(child) },
                    )
                    child.isFile && (child.type?.startsWith("video/") == true) -> {
                        val uriString = child.uri.toString()
                        val alreadyAdded = currentSources.any { it is TvChannelSource.Video && it.uri == uriString }
                        val name = child.name ?: "Untitled"
                        TvBrowseRow(
                            label = if (alreadyAdded) "✓ $name" else "▶ $name",
                            onLabelClick = { toggleVideoSource(uriString, name) },
                        )
                    }
                    else -> null
                }
            }
        }
        binding.emptyStateText.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
        adapter.submitList(rows)
    }

    private fun navigateInto(folder: DocumentFile) {
        currentFolder?.let { backStack.add(it) }
        currentFolder = folder
        render()
    }

    private fun addFolderSource(folder: DocumentFile) {
        configRepository.addSource(channelId, TvChannelSource.LocalFolder(folder.uri.toString(), folder.name ?: "Folder"))
        Toast.makeText(this, getString(R.string.tv_source_added, folder.name), Toast.LENGTH_SHORT).show()
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
