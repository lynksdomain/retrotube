package com.retrotube.app.library

import android.content.Context
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.retrotube.app.databinding.ItemFolderBinding
import com.retrotube.app.databinding.ItemVideoBinding
import com.retrotube.app.progress.PlaybackProgressRepository

class LibraryListAdapter(
    private val context: Context,
    private val onFolderClick: (LibraryItem.FolderItem) -> Unit,
    private val onFolderRemoveClick: (LibraryItem.FolderItem) -> Unit,
    private val onVideoClick: (LibraryItem.VideoItem) -> Unit,
    private val onVideoMenuClick: (LibraryItem.VideoItem) -> Unit,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private val progressRepository = PlaybackProgressRepository(context)

    private var items: List<LibraryItem> = emptyList()
    private var isRootLevel: Boolean = false

    fun submitList(newItems: List<LibraryItem>, isRootLevel: Boolean) {
        items = newItems
        this.isRootLevel = isRootLevel
        notifyDataSetChanged()
    }

    override fun getItemViewType(position: Int): Int = when (items[position]) {
        is LibraryItem.FolderItem -> VIEW_TYPE_FOLDER
        is LibraryItem.VideoItem -> VIEW_TYPE_VIDEO
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == VIEW_TYPE_FOLDER) {
            FolderViewHolder(ItemFolderBinding.inflate(inflater, parent, false))
        } else {
            VideoViewHolder(ItemVideoBinding.inflate(inflater, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = items[position]) {
            is LibraryItem.FolderItem -> {
                holder as FolderViewHolder
                holder.binding.folderName.text = item.name
                holder.binding.root.setOnClickListener { onFolderClick(item) }
                if (isRootLevel) {
                    holder.binding.removeFolderButton.visibility = android.view.View.VISIBLE
                    holder.binding.removeFolderButton.setOnClickListener { onFolderRemoveClick(item) }
                } else {
                    holder.binding.removeFolderButton.visibility = android.view.View.GONE
                    holder.binding.removeFolderButton.setOnClickListener(null)
                }
            }
            is LibraryItem.VideoItem -> {
                holder as VideoViewHolder
                holder.binding.videoName.text = item.name
                ThumbnailLoader.load(context, item.document.uri, holder.binding.videoThumbnail)
                holder.binding.root.setOnClickListener { onVideoClick(item) }
                holder.binding.videoMenuButton.setOnClickListener { onVideoMenuClick(item) }

                val progress = progressRepository.getProgress(item.document.uri.toString())
                if (progress != null) {
                    holder.binding.progressBarTrack.visibility = android.view.View.VISIBLE
                    holder.binding.progressBarFill.visibility = android.view.View.VISIBLE
                    holder.binding.progressBarFill.pivotX = 0f
                    holder.binding.progressBarFill.scaleX = progress.fraction.coerceIn(0f, 1f)
                } else {
                    holder.binding.progressBarTrack.visibility = android.view.View.GONE
                    holder.binding.progressBarFill.visibility = android.view.View.GONE
                }
            }
        }
    }

    override fun getItemCount(): Int = items.size

    class FolderViewHolder(val binding: ItemFolderBinding) : RecyclerView.ViewHolder(binding.root)
    class VideoViewHolder(val binding: ItemVideoBinding) : RecyclerView.ViewHolder(binding.root)

    companion object {
        private const val VIEW_TYPE_FOLDER = 0
        private const val VIEW_TYPE_VIDEO = 1
    }
}
