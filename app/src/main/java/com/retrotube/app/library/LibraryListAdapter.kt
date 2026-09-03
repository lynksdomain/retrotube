package com.retrotube.app.library

import android.content.Context
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.OvershootInterpolator
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.retrotube.app.databinding.ItemContinueWatchingRailBinding
import com.retrotube.app.databinding.ItemFolderBinding
import com.retrotube.app.databinding.ItemVideoBinding
import com.retrotube.app.progress.PlaybackProgressRepository
import com.retrotube.app.settings.SettingsRepository

/** Folders and videos share one poster grid -- a subfolder is a card that opens
 *  onto its own grid, same as any other browsable shelf, rather than a separate
 *  file-tree list sitting above the videos. The Continue Watching rail rides
 *  along as a full-width row inside that same grid, so it scrolls away with
 *  everything else instead of staying pinned above it. */
class LibraryListAdapter(
    private val context: Context,
    private val onFolderClick: (LibraryItem.FolderItem) -> Unit,
    private val onFolderRemoveClick: (LibraryItem.FolderItem) -> Unit,
    private val onVideoClick: (LibraryItem.VideoItem) -> Unit,
    private val onVideoMenuClick: (LibraryItem.VideoItem, View) -> Unit,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private val progressRepository = PlaybackProgressRepository(context)
    private val settingsRepository = SettingsRepository(context)

    private var items: List<LibraryItem> = emptyList()
    private var isRootLevel: Boolean = false

    fun submitList(newItems: List<LibraryItem>, isRootLevel: Boolean) {
        items = newItems
        this.isRootLevel = isRootLevel
        notifyDataSetChanged()
    }

    /** Folders and videos are single grid cells; the Continue Watching rail spans
     *  the whole row, same as any other full-width shelf header. */
    fun spanSizeFor(position: Int, spanCount: Int): Int =
        if (items[position] is LibraryItem.ContinueWatchingRail) spanCount else 1

    override fun getItemViewType(position: Int): Int = when (items[position]) {
        is LibraryItem.FolderItem -> VIEW_TYPE_FOLDER
        is LibraryItem.VideoItem -> VIEW_TYPE_VIDEO
        is LibraryItem.ContinueWatchingRail -> VIEW_TYPE_CONTINUE_WATCHING_RAIL
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            VIEW_TYPE_FOLDER -> FolderViewHolder(ItemFolderBinding.inflate(inflater, parent, false))
            VIEW_TYPE_CONTINUE_WATCHING_RAIL -> RailViewHolder(
                ItemContinueWatchingRailBinding.inflate(inflater, parent, false),
            )
            else -> VideoViewHolder(ItemVideoBinding.inflate(inflater, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = items[position]) {
            is LibraryItem.FolderItem -> {
                holder as FolderViewHolder
                holder.binding.folderName.text = item.name
                holder.binding.root.setOnClickListener { onFolderClick(item) }
                holder.binding.root.applySpringPress()
                if (isRootLevel) {
                    holder.binding.removeFolderButton.visibility = View.VISIBLE
                    holder.binding.removeFolderButton.setOnClickListener { onFolderRemoveClick(item) }
                } else {
                    holder.binding.removeFolderButton.visibility = View.GONE
                    holder.binding.removeFolderButton.setOnClickListener(null)
                }
            }
            is LibraryItem.VideoItem -> {
                holder as VideoViewHolder
                holder.binding.videoName.text = item.displayName
                holder.binding.presetBadge.text =
                    settingsRepository.effectiveSettings(item.document.uri.toString()).preset.label
                if (item.locationHint.isNotBlank()) {
                    holder.binding.videoLocationHint.visibility = View.VISIBLE
                    holder.binding.videoLocationHint.text = item.locationHint
                } else {
                    holder.binding.videoLocationHint.visibility = View.GONE
                }
                ThumbnailLoader.load(context, item.document.uri, holder.binding.videoThumbnail)
                holder.binding.root.setOnClickListener { onVideoClick(item) }
                holder.binding.root.applySpringPress()
                holder.binding.videoMenuButton.setOnClickListener {
                    onVideoMenuClick(item, holder.binding.videoMenuButton)
                }

                val progress = progressRepository.getProgress(item.document.uri.toString())
                if (progress != null) {
                    holder.binding.progressBarTrack.visibility = View.VISIBLE
                    holder.binding.progressBarFill.visibility = View.VISIBLE
                    holder.binding.progressBarFill.pivotX = 0f
                    holder.binding.progressBarFill.scaleX = progress.fraction.coerceIn(0f, 1f)
                } else {
                    holder.binding.progressBarTrack.visibility = View.GONE
                    holder.binding.progressBarFill.visibility = View.GONE
                }
            }
            is LibraryItem.ContinueWatchingRail -> {
                holder as RailViewHolder
                holder.railAdapter.submitList(item.videos)
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

    inner class RailViewHolder(
        private val binding: ItemContinueWatchingRailBinding,
    ) : RecyclerView.ViewHolder(binding.root) {
        val railAdapter = ContinueWatchingAdapter(context, onClick = onVideoClick)

        init {
            binding.continueWatchingList.layoutManager =
                LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false)
            binding.continueWatchingList.adapter = railAdapter
        }
    }

    companion object {
        private const val VIEW_TYPE_FOLDER = 0
        private const val VIEW_TYPE_VIDEO = 1
        private const val VIEW_TYPE_CONTINUE_WATCHING_RAIL = 2
    }
}
