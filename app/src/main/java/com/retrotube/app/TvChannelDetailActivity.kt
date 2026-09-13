package com.retrotube.app

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.EditText
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.retrotube.app.databinding.ActivityTvChannelDetailBinding
import com.retrotube.app.tv.TvChannelConfigRepository
import com.retrotube.app.tv.TvChannelSourceAdapter
import com.retrotube.app.util.applyTopBarInset

/**
 * What feeds one channel -- an ordered list of whole folders or individual
 * videos, referenced directly. Sources play back in the order they were
 * added, so this screen is add/remove only; there's no separate reordering
 * step to keep track of.
 */
class TvChannelDetailActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_CHANNEL_ID = "channel_id"
    }

    private lateinit var binding: ActivityTvChannelDetailBinding
    private lateinit var configRepository: TvChannelConfigRepository
    private lateinit var adapter: TvChannelSourceAdapter
    private lateinit var channelId: String
    private var editMode: Boolean = false

    private val pickResult = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
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
        binding.root.applyTopBarInset()
        configRepository = TvChannelConfigRepository(this)

        adapter = TvChannelSourceAdapter { index ->
            configRepository.removeSource(channelId, index)
            refresh()
        }
        binding.sourceList.layoutManager = LinearLayoutManager(this)
        binding.sourceList.adapter = adapter

        binding.backButton.setOnClickListener { finish() }
        binding.addSourceButton.setOnClickListener { promptAddSource() }
        binding.editButton.setOnClickListener { toggleEditMode() }
        binding.descriptionText.setOnClickListener { promptEditDescription() }

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
        binding.descriptionText.text = channel.description ?: getString(R.string.tv_channel_add_description)
        binding.emptyStateText.visibility = if (channel.sources.isEmpty()) View.VISIBLE else View.GONE
        adapter.submitList(channel.sources)
    }

    private fun toggleEditMode() {
        editMode = !editMode
        binding.editButton.setText(if (editMode) R.string.done else R.string.edit)
        adapter.setEditMode(editMode)
    }

    private fun promptEditDescription() {
        val current = configRepository.getChannels().firstOrNull { it.id == channelId }?.description
        val input = EditText(this).apply { setText(current) }
        AlertDialog.Builder(this)
            .setTitle(R.string.tv_channel_edit_description)
            .setView(input)
            .setPositiveButton(R.string.ok) { _, _ ->
                configRepository.setDescription(channelId, input.text?.toString())
                refresh()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun promptAddSource() {
        val options = arrayOf(
            getString(R.string.tv_add_source_local_folder),
            getString(R.string.tv_add_source_smb_folder),
            getString(R.string.tv_add_source_videos),
        )
        AlertDialog.Builder(this)
            .setTitle(R.string.tv_add_source)
            .setItems(options) { _, index ->
                when (index) {
                    0 -> pickResult.launch(
                        Intent(this, TvChannelPickLocalFolderActivity::class.java)
                            .putExtra(TvChannelPickLocalFolderActivity.EXTRA_CHANNEL_ID, channelId),
                    )
                    1 -> pickResult.launch(
                        Intent(this, TvChannelPickSmbFolderActivity::class.java)
                            .putExtra(TvChannelPickSmbFolderActivity.EXTRA_CHANNEL_ID, channelId),
                    )
                    2 -> pickResult.launch(
                        Intent(this, TvChannelPickVideosActivity::class.java)
                            .putExtra(TvChannelPickVideosActivity.EXTRA_CHANNEL_ID, channelId),
                    )
                }
            }
            .show()
    }
}
