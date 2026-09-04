package com.retrotube.app.tv

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.retrotube.app.databinding.ItemTvChannelRowBinding

/** The user's programmed channel list -- drag to reorder (that order is the
 *  channel numbering), tap a row to edit its sources, ✕ to delete it outright. */
class TvChannelListAdapter(
    private val onClick: (TvChannelDefinition) -> Unit,
    private val onDelete: (TvChannelDefinition) -> Unit,
) : RecyclerView.Adapter<TvChannelListAdapter.ViewHolder>() {

    private var channels: List<TvChannelDefinition> = emptyList()

    fun submitList(newChannels: List<TvChannelDefinition>) {
        channels = newChannels
        notifyDataSetChanged()
    }

    fun currentItems(): List<TvChannelDefinition> = channels

    fun moveItem(from: Int, to: Int) {
        if (from !in channels.indices || to !in channels.indices) return
        channels = channels.toMutableList().apply { add(to, removeAt(from)) }
        notifyItemMoved(from, to)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder =
        ViewHolder(ItemTvChannelRowBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val channel = channels[position]
        holder.binding.rowName.text = channel.name
        holder.binding.rowSourceCount.text = holder.itemView.resources.getString(
            com.retrotube.app.R.string.tv_channel_source_count,
            channel.sources.size,
        )
        holder.binding.rowRoot.setOnClickListener { onClick(channel) }
        holder.binding.rowDeleteButton.setOnClickListener { onDelete(channel) }
    }

    override fun getItemCount(): Int = channels.size

    class ViewHolder(val binding: ItemTvChannelRowBinding) : RecyclerView.ViewHolder(binding.root)
}
