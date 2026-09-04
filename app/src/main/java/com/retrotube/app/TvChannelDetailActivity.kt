package com.retrotube.app

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.retrotube.app.collections.CollectionRepository
import com.retrotube.app.databinding.ActivityTvChannelDetailBinding
import com.retrotube.app.library.LibraryRepository
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
        binding.addSourceButton.setOnClickListener { promptAddSource() }

        refresh()
    }

    private fun refresh() {
        val channels = configRepository.getChannels()
        val index = channels.indexOfFirst { it.id == channelId }
        if (index < 0) {
            finish()
            return
        }
        val channel = channels[index]
        binding.channelNameText.text = getString(R.string.tv_channel_number, index + 1)
        binding.emptyStateText.visibility = if (channel.sources.isEmpty()) View.VISIBLE else View.GONE
        adapter.submitList(channel.sources)
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
                    0 -> promptAddLocalFolder()
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

    /** Picks from folders already added to the library -- not a raw system
     *  folder browser, which could point a channel at something outside the
     *  library entirely rather than content already in it. */
    private fun promptAddLocalFolder() {
        val roots = LibraryRepository(this).getRootDocuments()
        if (roots.isEmpty()) {
            AlertDialog.Builder(this).setMessage(R.string.tv_no_local_folders).setPositiveButton(R.string.ok, null).show()
            return
        }
        val names = roots.map { it.name }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(R.string.tv_add_source_local_folder)
            .setItems(names) { _, index ->
                val root = roots[index]
                configRepository.addSource(channelId, TvChannelSource.LocalFolder(root.document.uri.toString(), root.name))
                refresh()
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
