package com.retrotube.app.tv

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.retrotube.app.databinding.ItemTvSourceRowBinding

/** One channel's sources, in the order they were added -- that order is the
 *  playback order, so this list is display + remove only, no reordering. */
class TvChannelSourceAdapter(
    private val onRemove: (Int) -> Unit,
) : RecyclerView.Adapter<TvChannelSourceAdapter.ViewHolder>() {

    private var sources: List<TvChannelSource> = emptyList()

    fun submitList(newSources: List<TvChannelSource>) {
        sources = newSources
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder =
        ViewHolder(ItemTvSourceRowBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val source = sources[position]
        holder.binding.rowLabel.text = labelFor(source)
        holder.binding.rowRemoveButton.setOnClickListener { onRemove(holder.bindingAdapterPosition) }
    }

    override fun getItemCount(): Int = sources.size

    private fun labelFor(source: TvChannelSource): String = when (source) {
        is TvChannelSource.LocalFolder -> "📁 ${source.displayName}"
        is TvChannelSource.SmbFolder -> "🌐 ${source.displayName}"
        is TvChannelSource.Collection -> "🎬 ${source.displayName}"
        is TvChannelSource.Video -> "▶ ${source.displayName}"
    }

    class ViewHolder(val binding: ItemTvSourceRowBinding) : RecyclerView.ViewHolder(binding.root)
}
