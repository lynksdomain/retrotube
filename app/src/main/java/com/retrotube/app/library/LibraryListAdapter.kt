package com.retrotube.app.library

import android.content.Context
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.OvershootInterpolator
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.retrotube.app.R
import com.retrotube.app.collections.CollectionRepository
import com.retrotube.app.databinding.ItemCollectionBinding
import com.retrotube.app.databinding.ItemContinueWatchingRailBinding
import com.retrotube.app.databinding.ItemFolderBinding
import com.retrotube.app.databinding.ItemVideoBinding
import com.retrotube.app.metadata.VideoMetadataRepository
import com.retrotube.app.network.NetworkShareRepository
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
    private val onCollectionClick: (LibraryItem.CollectionItem) -> Unit,
    private val onCollectionRemoveClick: (LibraryItem.CollectionItem) -> Unit,
    private val onCollectionEditPosterClick: (LibraryItem.CollectionItem) -> Unit,
    private val onSmbFolderClick: (LibraryItem.SmbFolderItem) -> Unit,
    private val onSmbShareRemoveClick: (LibraryItem.SmbFolderItem) -> Unit,
    private val onSmbVideoClick: (LibraryItem.SmbVideoItem) -> Unit,
    private val onSmbVideoMenuClick: (LibraryItem.SmbVideoItem, View) -> Unit,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private val progressRepository = PlaybackProgressRepository(context)
    private val settingsRepository = SettingsRepository(context)
    private val metadataRepository = VideoMetadataRepository(context)
    private val collectionRepository = CollectionRepository(context)
    private val networkShareRepository = NetworkShareRepository(context)

    private var items: List<LibraryItem> = emptyList()
    private var isRootLevel: Boolean = false

    fun submitList(newItems: List<LibraryItem>, isRootLevel: Boolean) {
        items = newItems
        this.isRootLevel = isRootLevel
        notifyDataSetChanged()
    }

    /** Read-only snapshot of what's currently shown, in display order -- used to
     *  persist a collection's order after a drag reorder. */
    fun currentItems(): List<LibraryItem> = items

    /** Moves an item from [from] to [to] with an animated shift rather than a full
     *  rebind, for drag-to-reorder inside a collection. */
    fun moveItem(from: Int, to: Int) {
        if (from == to) return
        val mutable = items.toMutableList()
        val moved = mutable.removeAt(from)
        mutable.add(to, moved)
        items = mutable
        notifyItemMoved(from, to)
    }

    /** Folders, videos and collections are single grid cells; the Continue Watching
     *  rail and section headers span the whole row. */
    fun spanSizeFor(position: Int, spanCount: Int): Int =
        when (items[position]) {
            is LibraryItem.ContinueWatchingRail, is LibraryItem.SectionHeader -> spanCount
            else -> 1
        }

    override fun getItemViewType(position: Int): Int = when (items[position]) {
        is LibraryItem.FolderItem, is LibraryItem.SmbFolderItem -> VIEW_TYPE_FOLDER
        is LibraryItem.VideoItem, is LibraryItem.SmbVideoItem -> VIEW_TYPE_VIDEO
        is LibraryItem.ContinueWatchingRail -> VIEW_TYPE_CONTINUE_WATCHING_RAIL
        is LibraryItem.CollectionItem -> VIEW_TYPE_COLLECTION
        is LibraryItem.SectionHeader -> VIEW_TYPE_SECTION_HEADER
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            VIEW_TYPE_FOLDER -> FolderViewHolder(ItemFolderBinding.inflate(inflater, parent, false))
            VIEW_TYPE_COLLECTION -> CollectionViewHolder(ItemCollectionBinding.inflate(inflater, parent, false))
            VIEW_TYPE_CONTINUE_WATCHING_RAIL -> RailViewHolder(
                ItemContinueWatchingRailBinding.inflate(inflater, parent, false),
            )
            VIEW_TYPE_SECTION_HEADER -> SectionHeaderViewHolder(
                inflater.inflate(R.layout.item_section_header, parent, false) as TextView,
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
            is LibraryItem.SmbFolderItem -> {
                holder as FolderViewHolder
                holder.binding.folderName.text = item.name
                holder.binding.root.setOnClickListener { onSmbFolderClick(item) }
                holder.binding.root.applySpringPress()
                // Only the share's own root card (reached from the library root) is
                // removable -- a subfolder inside a share is just something you browse.
                if (item.relativePath.isEmpty()) {
                    holder.binding.removeFolderButton.visibility = View.VISIBLE
                    holder.binding.removeFolderButton.setOnClickListener { onSmbShareRemoveClick(item) }
                } else {
                    holder.binding.removeFolderButton.visibility = View.GONE
                    holder.binding.removeFolderButton.setOnClickListener(null)
                }
            }
            is LibraryItem.VideoItem -> {
                holder as VideoViewHolder
                bindVideoCard(
                    holder,
                    uriString = item.document.uri.toString(),
                    displayName = item.displayName,
                    locationHint = item.locationHint,
                    loadThumbnail = { imageView -> ThumbnailLoader.load(context, item.document.uri, imageView) },
                    onClick = { onVideoClick(item) },
                    onMenuClick = { anchor -> onVideoMenuClick(item, anchor) },
                )
            }
            is LibraryItem.SmbVideoItem -> {
                holder as VideoViewHolder
                val uriString = item.uri.toString()
                bindVideoCard(
                    holder,
                    uriString = uriString,
                    displayName = item.displayName,
                    locationHint = item.locationHint,
                    loadThumbnail = { imageView ->
                        ThumbnailLoader.loadSmb(context, networkShareRepository, uriString, item.shareId, item.relativePath, imageView)
                    },
                    onClick = { onSmbVideoClick(item) },
                    onMenuClick = { anchor -> onSmbVideoMenuClick(item, anchor) },
                )
            }
            is LibraryItem.ContinueWatchingRail -> {
                holder as RailViewHolder
                holder.railAdapter.submitList(item.videos)
            }
            is LibraryItem.CollectionItem -> {
                holder as CollectionViewHolder
                holder.binding.collectionName.text = item.name
                holder.binding.collectionVideoCount.text =
                    context.getString(R.string.collection_video_count, item.videoCount)
                holder.binding.root.setOnClickListener { onCollectionClick(item) }
                holder.binding.root.applySpringPress()
                holder.binding.removeCollectionButton.setOnClickListener { onCollectionRemoveClick(item) }
                holder.binding.editCollectionPosterButton.setOnClickListener { onCollectionEditPosterClick(item) }

                val poster = collectionRepository.getPoster(item.id)
                if (poster != null) {
                    holder.binding.collectionPosterImage.visibility = View.VISIBLE
                    holder.binding.collectionPosterImage.setImageBitmap(poster)
                } else {
                    holder.binding.collectionPosterImage.visibility = View.GONE
                }
            }
            is LibraryItem.SectionHeader -> {
                holder as SectionHeaderViewHolder
                holder.textView.text = item.title
            }
        }
    }

    /** Shared bind logic for both SAF and SMB video cards -- everything about the poster
     *  (title, badge, resume progress, custom overrides) is keyed off the URI string, so
     *  the only real difference between the two is how (or whether) a thumbnail loads. */
    private fun bindVideoCard(
        holder: VideoViewHolder,
        uriString: String,
        displayName: String,
        locationHint: String,
        loadThumbnail: ((android.widget.ImageView) -> Unit)?,
        onClick: () -> Unit,
        onMenuClick: (View) -> Unit,
    ) {
        holder.binding.videoName.text = metadataRepository.getCustomTitle(uriString) ?: displayName
        holder.binding.presetBadge.text = settingsRepository.effectiveSettings(uriString).preset.label
        if (locationHint.isNotBlank()) {
            holder.binding.videoLocationHint.visibility = View.VISIBLE
            holder.binding.videoLocationHint.text = locationHint
        } else {
            holder.binding.videoLocationHint.visibility = View.GONE
        }

        val customThumbnail = metadataRepository.getCustomThumbnail(uriString)
        when {
            customThumbnail != null -> holder.binding.videoThumbnail.setImageBitmap(customThumbnail)
            loadThumbnail != null -> loadThumbnail(holder.binding.videoThumbnail)
            else -> holder.binding.videoThumbnail.setImageDrawable(null)
        }

        holder.binding.root.setOnClickListener { onClick() }
        holder.binding.root.applySpringPress()
        holder.binding.videoMenuButton.setOnClickListener { onMenuClick(holder.binding.videoMenuButton) }

        val progress = progressRepository.getProgress(uriString)
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
    class CollectionViewHolder(val binding: ItemCollectionBinding) : RecyclerView.ViewHolder(binding.root)
    class SectionHeaderViewHolder(val textView: TextView) : RecyclerView.ViewHolder(textView)

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
        private const val VIEW_TYPE_COLLECTION = 3
        private const val VIEW_TYPE_SECTION_HEADER = 4
    }
}
