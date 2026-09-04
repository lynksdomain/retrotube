package com.retrotube.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.EditText
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.documentfile.provider.DocumentFile
import androidx.recyclerview.widget.LinearLayoutManager
import com.retrotube.app.collections.CollectionRepository
import com.retrotube.app.databinding.ActivityTvChannelDetailBinding
import com.retrotube.app.network.NetworkShareRepository
import com.retrotube.app.tv.TvChannelConfigRepository
import com.retrotube.app.tv.TvChannelSource
import com.retrotube.app.tv.TvChannelSourceAdapter

/**
 * What feeds one channel -- an ordered list of whole folders, whole
 * collections, or individual videos. Sources play back in the order they
 * were added, so this screen is add/remove only; there's no separate
 * reordering step to keep track of.
 */
class TvChannelDetailActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_CHANNEL_ID = "channel_id"
    }

    private lateinit var binding: ActivityTvChannelDetailBinding
    private lateinit var configRepository: TvChannelConfigRepository
    private lateinit var adapter: TvChannelSourceAdapter
    private lateinit var channelId: String

    private val pickLocalFolder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null) return@registerForActivityResult
        contentResolver.takePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION,
        )
        val name = DocumentFile.fromTreeUri(this, uri)?.name ?: getString(R.string.tv_source_folder_default_name)
        configRepository.addSource(channelId, TvChannelSource.LocalFolder(uri.toString(), name))
        refresh()
    }

    private val pickVideos = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        refresh()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent.getStringExtra(EXTRA_CHANNEL_ID)
        if (id == null) {
            finish()
            return
        }
        channelId = id

        binding = ActivityTvChannelDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)
        configRepository = TvChannelConfigRepository(this)

        adapter = TvChannelSourceAdapter { index ->
            configRepository.removeSource(channelId, index)
            refresh()
        }
        binding.sourceList.layoutManager = LinearLayoutManager(this)
        binding.sourceList.adapter = adapter

        binding.backButton.setOnClickListener { finish() }
        binding.channelNameText.setOnClickListener { promptRename() }
        binding.addSourceButton.setOnClickListener { promptAddSource() }

        refresh()
    }

    private fun refresh() {
        val channel = configRepository.getChannels().firstOrNull { it.id == channelId }
        if (channel == null) {
            finish()
            return
        }
        binding.channelNameText.text = channel.name
        binding.emptyStateText.visibility = if (channel.sources.isEmpty()) View.VISIBLE else View.GONE
        adapter.submitList(channel.sources)
    }

    private fun promptRename() {
        val currentName = configRepository.getChannels().firstOrNull { it.id == channelId }?.name.orEmpty()
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT
            setText(currentName)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.tv_rename_channel)
            .setView(input)
            .setPositiveButton(R.string.tv_add_channel_confirm) { _, _ ->
                val name = input.text?.toString()?.trim().orEmpty()
                if (name.isNotEmpty()) {
                    configRepository.renameChannel(channelId, name)
                    refresh()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun promptAddSource() {
        val options = arrayOf(
            getString(R.string.tv_add_source_local_folder),
            getString(R.string.tv_add_source_smb_folder),
            getString(R.string.tv_add_source_collection),
            getString(R.string.tv_add_source_videos),
        )
        AlertDialog.Builder(this)
            .setTitle(R.string.tv_add_source)
            .setItems(options) { _, index ->
                when (index) {
                    0 -> pickLocalFolder.launch(null)
                    1 -> promptAddSmbShare()
                    2 -> promptAddCollection()
                    3 -> pickVideos.launch(
                        Intent(this, TvChannelPickVideosActivity::class.java)
                            .putExtra(TvChannelPickVideosActivity.EXTRA_CHANNEL_ID, channelId),
                    )
                }
            }
            .show()
    }

    private fun promptAddSmbShare() {
        val shares = NetworkShareRepository(this).getAll()
        if (shares.isEmpty()) {
            AlertDialog.Builder(this).setMessage(R.string.tv_no_shares_connected).setPositiveButton(R.string.ok, null).show()
            return
        }
        val names = shares.map { it.displayName }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(R.string.tv_add_source_smb_folder)
            .setItems(names) { _, index ->
                val share = shares[index]
                configRepository.addSource(channelId, TvChannelSource.SmbFolder(share.id, "", share.displayName))
                refresh()
            }
            .show()
    }

    private fun promptAddCollection() {
        val collections = CollectionRepository(this).getAll()
        if (collections.isEmpty()) {
            AlertDialog.Builder(this).setMessage(R.string.tv_no_collections).setPositiveButton(R.string.ok, null).show()
            return
        }
        val names = collections.map { it.name }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(R.string.tv_add_source_collection)
            .setItems(names) { _, index ->
                val collection = collections[index]
                configRepository.addSource(channelId, TvChannelSource.Collection(collection.id, collection.name))
                refresh()
            }
            .show()
    }
}
