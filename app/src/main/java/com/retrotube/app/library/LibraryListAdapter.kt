package com.retrotube.app.library

import android.content.Context
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.OvershootInterpolator
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.retrotube.app.R
import com.retrotube.app.databinding.ItemFolderBinding
import com.retrotube.app.databinding.ItemVideoBinding
import com.retrotube.app.progress.PlaybackProgressRepository

class LibraryListAdapter(
    private val context: Context,
    private val onFolderClick: (LibraryItem.FolderItem) -> Unit,
    private val onFolderRemoveClick: (LibraryItem.FolderItem) -> Unit,
    private val onVideoClick: (LibraryItem.VideoItem) -> Unit,
    private val onVideoMenuClick: (LibraryItem.VideoItem, View) -> Unit,
    private val onSeeAllClick: () -> Unit,
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
        is LibraryItem.SectionHeader -> VIEW_TYPE_HEADER
        is LibraryItem.SeeAllRow -> VIEW_TYPE_SEE_ALL
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            VIEW_TYPE_FOLDER -> FolderViewHolder(ItemFolderBinding.inflate(inflater, parent, false))
            VIEW_TYPE_VIDEO -> VideoViewHolder(ItemVideoBinding.inflate(inflater, parent, false))
            VIEW_TYPE_SEE_ALL -> HeaderViewHolder(
                inflater.inflate(R.layout.item_see_all, parent, false) as TextView,
            )
            else -> HeaderViewHolder(inflater.inflate(R.layout.item_section_header, parent, false) as TextView)
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
                holder.binding.root.applySpringPress()
                holder.binding.videoMenuButton.setOnClickListener {
                    onVideoMenuClick(item, holder.binding.videoMenuButton)
                }

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
            is LibraryItem.SectionHeader -> {
                holder as HeaderViewHolder
                holder.textView.text = item.title
                holder.textView.setOnClickListener(null)
            }
            is LibraryItem.SeeAllRow -> {
                holder as HeaderViewHolder
                holder.textView.text = context.getString(R.string.see_all_count, item.remainingCount)
                holder.textView.setOnClickListener { onSeeAllClick() }
            }
        }
    }

    override fun getItemCount(): Int = items.size

    /** Scales down slightly on press, springs back with overshoot on release --
     *  the "feels good, not just looks good" detail Apple TV/Music-style focus
     *  animations are built on, instead of the flat default Android ripple alone. */
    @Suppress("ClickableViewAccessibility")
    private fun View.applySpringPress() {
        setOnTouchListener { view, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    view.animate().scaleX(0.96f).scaleY(0.96f).setDuration(100).start()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    view.animate()
                        .scaleX(1f)
                        .scaleY(1f)
                        .setDuration(220)
                        .setInterpolator(OvershootInterpolator())
                        .start()
                }
            }
            false // don't consume -- the click listener still needs the event
        }
    }

    class FolderViewHolder(val binding: ItemFolderBinding) : RecyclerView.ViewHolder(binding.root)
    class VideoViewHolder(val binding: ItemVideoBinding) : RecyclerView.ViewHolder(binding.root)
    class HeaderViewHolder(val textView: TextView) : RecyclerView.ViewHolder(textView)

    companion object {
        private const val VIEW_TYPE_FOLDER = 0
        private const val VIEW_TYPE_VIDEO = 1
        private const val VIEW_TYPE_HEADER = 2
        private const val VIEW_TYPE_SEE_ALL = 3
    }
}
