package com.retrotube.app

import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.EditText
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.retrotube.app.databinding.ActivityTvChannelEditorBinding
import com.retrotube.app.tv.TvChannelConfigRepository
import com.retrotube.app.tv.TvChannelListAdapter

/**
 * The user's own TV Mode channel lineup -- add, remove, rename, and reorder
 * channels here (order is the channel numbering); tap one to edit what feeds
 * it (see [TvChannelDetailActivity]). Reachable both from the setup wizard's
 * first run and afterward, any time the lineup needs adjusting.
 */
class TvChannelEditorActivity : AppCompatActivity() {

    private lateinit var binding: ActivityTvChannelEditorBinding
    private lateinit var configRepository: TvChannelConfigRepository
    private lateinit var adapter: TvChannelListAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTvChannelEditorBinding.inflate(layoutInflater)
        setContentView(binding.root)
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
        reorderTouchHelper.attachToRecyclerView(binding.channelList)

        binding.backButton.setOnClickListener { finish() }
        binding.addChannelButton.setOnClickListener { promptNewChannel() }
        binding.launchTvModeButton.setOnClickListener {
            startActivity(
                Intent(this, PlayerActivity::class.java).putExtra(PlayerActivity.EXTRA_TV_MODE, true),
            )
        }
    }

    override fun onResume() {
        super.onResume()
        refreshList()
    }

    private fun refreshList() {
        val channels = configRepository.getChannels()
        binding.emptyStateText.visibility = if (channels.isEmpty()) View.VISIBLE else View.GONE
        adapter.submitList(channels)
    }

    private fun promptNewChannel() {
        val input = EditText(this).apply { inputType = InputType.TYPE_CLASS_TEXT }
        AlertDialog.Builder(this)
            .setTitle(R.string.tv_add_channel)
            .setView(input)
            .setPositiveButton(R.string.tv_add_channel_confirm) { _, _ ->
                val name = input.text?.toString()?.trim().orEmpty()
                if (name.isNotEmpty()) {
                    val channel = configRepository.addChannel(name)
                    refreshList()
                    startActivity(
                        Intent(this, TvChannelDetailActivity::class.java)
                            .putExtra(TvChannelDetailActivity.EXTRA_CHANNEL_ID, channel.id),
                    )
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** Drag to reorder -- the list order is saved as the new channel order
     *  (and so the new channel numbering) once the drag ends. */
    private val reorderTouchHelper = ItemTouchHelper(
        object : ItemTouchHelper.SimpleCallback(ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0) {
            override fun isLongPressDragEnabled() = true

            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder,
            ): Boolean {
                adapter.moveItem(viewHolder.bindingAdapterPosition, target.bindingAdapterPosition)
                return true
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) = Unit

            override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
                super.clearView(recyclerView, viewHolder)
                configRepository.reorderChannels(adapter.currentItems().map { it.id })
            }
        },
    )
}
