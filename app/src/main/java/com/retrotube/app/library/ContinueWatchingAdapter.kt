package com.retrotube.app.library

import android.content.Context
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.retrotube.app.databinding.ItemContinueWatchingBinding
import com.retrotube.app.progress.PlaybackProgressRepository

/** Compact horizontal rail, separate from the main folder/video grid -- a quick
 *  way back into what you were watching, not another shelf to browse. */
class ContinueWatchingAdapter(
    private val context: Context,
    private val onClick: (LibraryItem.VideoItem) -> Unit,
) : RecyclerView.Adapter<ContinueWatchingAdapter.ViewHolder>() {

    private val progressRepository = PlaybackProgressRepository(context)
    private var items: List<LibraryItem.VideoItem> = emptyList()

    fun submitList(newItems: List<LibraryItem.VideoItem>) {
        items = newItems
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder =
        ViewHolder(ItemContinueWatchingBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        holder.binding.continueName.text = item.displayName
        ThumbnailLoader.load(context, item.document.uri, holder.binding.continueThumbnail)
        holder.binding.root.setOnClickListener { onClick(item) }

        val progress = progressRepository.getProgress(item.document.uri.toString())
        holder.binding.continueProgressFill.pivotX = 0f
        holder.binding.continueProgressFill.scaleX = progress?.fraction?.coerceIn(0f, 1f) ?: 0f
    }

    override fun getItemCount(): Int = items.size

    class ViewHolder(val binding: ItemContinueWatchingBinding) : RecyclerView.ViewHolder(binding.root)
}
