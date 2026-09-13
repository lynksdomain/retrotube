package com.retrotube.app.tv

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.retrotube.app.databinding.ItemTvSourceRowBinding

/** One channel's sources, in the order they were added -- that order is the
 *  playback order, so this list is display + remove only, no reordering. An
 *  "Edit" toolbar toggle reveals a red remove-circle per row (matching the
 *  iOS list-editing affordance) instead of a delete button always on screen. */
class TvChannelSourceAdapter(
    private val onRemove: (Int) -> Unit,
) : RecyclerView.Adapter<TvChannelSourceAdapter.ViewHolder>() {

    private var sources: List<TvChannelSource> = emptyList()
    private var editMode: Boolean = false

    fun submitList(newSources: List<TvChannelSource>) {
        sources = newSources
        notifyDataSetChanged()
    }

    fun setEditMode(enabled: Boolean) {
        editMode = enabled
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder =
        ViewHolder(ItemTvSourceRowBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val source = sources[position]
        holder.binding.rowLabel.text = labelFor(source)
        holder.binding.rowRemoveButton.visibility = if (editMode) android.view.View.VISIBLE else android.view.View.GONE
        holder.binding.rowRemoveButton.setOnClickListener { onRemove(holder.bindingAdapterPosition) }
    }

    override fun getItemCount(): Int = sources.size

    private fun labelFor(source: TvChannelSource): String = when (source) {
        is TvChannelSource.LocalFolder -> "📁 ${source.displayName}"
        is TvChannelSource.SmbFolder -> "🌐 ${source.displayName}"
        is TvChannelSource.Video -> "▶ ${source.displayName}"
    }

    class ViewHolder(val binding: ItemTvSourceRowBinding) : RecyclerView.ViewHolder(binding.root)
}
