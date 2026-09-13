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
import com.retrotube.app.metadata.FolderMetadataRepository
import com.retrotube.app.metadata.VideoMetadataRepository
import com.retrotube.app.network.NetworkShareRepository
import com.retrotube.app.network.SmbUri
import com.retrotube.app.progress.PlaybackProgressRepository
import com.retrotube.app.settings.SettingsRepository

/** Folders and videos share one poster grid -- a subfolder is a card that opens
 *  onto its own grid, same as any other browsable shelf, rather than a separate
 *  file-tree list sitting above the videos. This is the Sources tab's raw
 *  browsing surface: local folders and SMB shares, nothing else -- no
 *  Continue Watching rail (that lives on the Library tab now, alongside the
 *  buckets it belongs with) and no Collections (removed entirely; TV Mode
 *  channels reference sources directly instead). */
class LibraryListAdapter(
    private val context: Context,
    private val onFolderClick: (LibraryItem.FolderItem) -> Unit,
    private val onFolderMenuClick: (LibraryItem.FolderItem, View) -> Unit,
    private val onVideoClick: (LibraryItem.VideoItem) -> Unit,
    private val onVideoMenuClick: (LibraryItem.VideoItem, View) -> Unit,
    private val onSmbFolderClick: (LibraryItem.SmbFolderItem) -> Unit,
    private val onSmbFolderMenuClick: (LibraryItem.SmbFolderItem, View) -> Unit,
    private val onSmbVideoClick: (LibraryItem.SmbVideoItem) -> Unit,
    private val onSmbVideoMenuClick: (LibraryItem.SmbVideoItem, View) -> Unit,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private val progressRepository = PlaybackProgressRepository(context)
    private val settingsRepository = SettingsRepository(context)
    private val metadataRepository = VideoMetadataRepository(context)
    private val networkShareRepository = NetworkShareRepository(context)
    private val folderMetadataRepository = FolderMetadataRepository(context)

    private var items: List<LibraryItem> = emptyList()

    fun submitList(newItems: List<LibraryItem>) {
        items = newItems
        notifyDataSetChanged()
    }

    fun currentItems(): List<LibraryItem> = items

    /** Folders and videos are single grid cells; section headers span the whole row. */
    fun spanSizeFor(position: Int, spanCount: Int): Int =
        when (items[position]) {
            is LibraryItem.SectionHeader -> spanCount
            else -> 1
        }

    override fun getItemViewType(position: Int): Int = when (items[position]) {
        is LibraryItem.FolderItem, is LibraryItem.SmbFolderItem -> VIEW_TYPE_FOLDER
        is LibraryItem.VideoItem, is LibraryItem.SmbVideoItem -> VIEW_TYPE_VIDEO
        is LibraryItem.SectionHeader -> VIEW_TYPE_SECTION_HEADER
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            VIEW_TYPE_FOLDER -> FolderViewHolder(ItemFolderBinding.inflate(inflater, parent, false))
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
                bindFolderCard(
                    holder,
                    folderKey = item.document.uri.toString(),
                    rawName = item.name,
                    onClick = { onFolderClick(item) },
                    onMenuClick = { anchor -> onFolderMenuClick(item, anchor) },
                )
            }
            is LibraryItem.SmbFolderItem -> {
                holder as FolderViewHolder
                val folderKey = SmbUri.build(item.shareId, item.relativePath).toString()
                bindFolderCard(
                    holder,
                    folderKey = folderKey,
                    rawName = item.name,
                    onClick = { onSmbFolderClick(item) },
                    onMenuClick = { anchor -> onSmbFolderMenuClick(item, anchor) },
                )
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
            is LibraryItem.SectionHeader -> {
                holder as SectionHeaderViewHolder
                holder.textView.text = item.title
            }
        }
    }

    /** Shared bind logic for both SAF and SMB folder cards -- a folder can carry
     *  a user-set title/poster override (edited via the "⋯" menu) regardless of
     *  whether it's tagged into a bucket yet; falls back to the raw folder name
     *  and a plain folder-icon placeholder when nothing's been set. */
    private fun bindFolderCard(
        holder: FolderViewHolder,
        folderKey: String,
        rawName: String,
        onClick: () -> Unit,
        onMenuClick: (View) -> Unit,
    ) {
        holder.binding.folderName.text = folderMetadataRepository.getCustomTitle(folderKey) ?: rawName
        val customPoster = folderMetadataRepository.getPoster(folderKey)
        if (customPoster != null) {
            holder.binding.folderPoster.setImageBitmap(customPoster)
            holder.binding.folderPoster.visibility = View.VISIBLE
            holder.binding.folderPlaceholderIcon.visibility = View.GONE
        } else {
            holder.binding.folderPoster.visibility = View.GONE
            holder.binding.folderPlaceholderIcon.visibility = View.VISIBLE
        }
        holder.binding.root.setOnClickListener { onClick() }
        holder.binding.root.applySpringPress()
        holder.binding.folderMenuButton.setOnClickListener { onMenuClick(holder.binding.folderMenuButton) }
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
    class SectionHeaderViewHolder(val textView: TextView) : RecyclerView.ViewHolder(textView)

    companion object {
        private const val VIEW_TYPE_FOLDER = 0
        private const val VIEW_TYPE_VIDEO = 1
        private const val VIEW_TYPE_SECTION_HEADER = 2
    }
}
