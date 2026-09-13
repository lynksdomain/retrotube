package com.retrotube.app

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.retrotube.app.databinding.ActivityTvChannelEditorBinding
import com.retrotube.app.tv.TvChannelConfigRepository
import com.retrotube.app.tv.TvChannelListAdapter
import com.retrotube.app.util.applyTopBarInset

/**
 * The user's own TV Mode channel lineup -- add or remove channels here (order
 * is strictly sequential by add order -- channels are always just "CH N,"
 * never named, and never manually reordered); tap one to edit what feeds it
 * (see [TvChannelDetailActivity]). CH 01 always exists (see
 * [TvChannelConfigRepository.getChannels]), so this screen never needs a
 * first-run setup step -- there's always at least one channel to edit or
 * build onto.
 */
class TvChannelEditorActivity : AppCompatActivity() {

    private lateinit var binding: ActivityTvChannelEditorBinding
    private lateinit var configRepository: TvChannelConfigRepository
    private lateinit var adapter: TvChannelListAdapter
    private var editMode: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTvChannelEditorBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.root.applyTopBarInset()
        configRepository = TvChannelConfigRepository(this)

        adapter = TvChannelListAdapter(
            onClick = { channel ->
                startActivity(
                    Intent(this, TvChannelDetailActivity::class.java)
                        .putExtra(TvChannelDetailActivity.EXTRA_CHANNEL_ID, channel.id),
                )
            },
            onDelete = { channel ->
                configRepository.deleteChannel(channel.id)
                refreshList()
            },
        )
        binding.channelList.layoutManager = LinearLayoutManager(this)
        binding.channelList.adapter = adapter

        binding.editButton.setOnClickListener { toggleEditMode() }
        binding.addChannelButton.setOnClickListener { addChannel() }
        binding.launchTvModeButton.setOnClickListener {
            startActivity(
                Intent(this, PlayerActivity::class.java).putExtra(PlayerActivity.EXTRA_TV_MODE, true),
            )
        }
        MainTabBar.setup(this, binding.root, MainTabBar.Tab.TV)
    }

    override fun onResume() {
        super.onResume()
        refreshList()
    }

    private fun refreshList() {
        adapter.submitList(configRepository.getChannels())
    }

    private fun toggleEditMode() {
        editMode = !editMode
        binding.editButton.setText(if (editMode) R.string.done else R.string.edit)
        adapter.setEditMode(editMode)
    }

    /** No name to ask for -- a new channel is just the next sequential number,
     *  so this goes straight to its (empty) source list. */
    private fun addChannel() {
        val channel = configRepository.addChannel()
        refreshList()
        startActivity(
            Intent(this, TvChannelDetailActivity::class.java)
                .putExtra(TvChannelDetailActivity.EXTRA_CHANNEL_ID, channel.id),
        )
    }
}
