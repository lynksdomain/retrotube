package com.retrotube.app.tv

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.retrotube.app.databinding.ItemTvChannelRowBinding

/** The user's programmed channel list -- always sequential (no drag-to-reorder;
 *  a channel's position *is* its number, assigned strictly by add order). Tap
 *  a row to edit its sources; an "Edit" toolbar toggle reveals a red
 *  remove-circle per row (matching the iOS list-editing affordance) instead
 *  of a delete button that's always on screen. */
class TvChannelListAdapter(
    private val onClick: (TvChannelDefinition) -> Unit,
    private val onDelete: (TvChannelDefinition) -> Unit,
) : RecyclerView.Adapter<TvChannelListAdapter.ViewHolder>() {

    private var channels: List<TvChannelDefinition> = emptyList()
    private var editMode: Boolean = false

    fun submitList(newChannels: List<TvChannelDefinition>) {
        channels = newChannels
        notifyDataSetChanged()
    }

    fun setEditMode(enabled: Boolean) {
        editMode = enabled
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder =
        ViewHolder(ItemTvChannelRowBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val channel = channels[position]
        holder.binding.rowName.text = holder.itemView.resources.getString(com.retrotube.app.R.string.tv_channel_number, position + 1)
        val sourceCount = holder.itemView.resources.getString(
            com.retrotube.app.R.string.tv_channel_source_count,
            channel.sources.size,
        )
        holder.binding.rowSourceCount.text = if (channel.description != null) {
            "${channel.description} · $sourceCount"
        } else {
            sourceCount
        }
        holder.binding.rowRoot.setOnClickListener { onClick(channel) }
        // CH 01 (whichever channel currently holds the first position) can't be
        // deleted -- see TvChannelConfigRepository.deleteChannel.
        holder.binding.rowDeleteButton.visibility =
            if (editMode && position != 0) android.view.View.VISIBLE else android.view.View.GONE
        holder.binding.rowDeleteButton.setOnClickListener { onDelete(channel) }
    }

    override fun getItemCount(): Int = channels.size

    class ViewHolder(val binding: ItemTvChannelRowBinding) : RecyclerView.ViewHolder(binding.root)
}
