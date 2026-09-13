package com.retrotube.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.PopupMenu
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.retrotube.app.bucket.Bucket
import com.retrotube.app.bucket.BucketRepository
import com.retrotube.app.bucket.ContentKind
import com.retrotube.app.databinding.ActivityLibraryBinding
import com.retrotube.app.databinding.DialogManageSourcesBinding
import com.retrotube.app.databinding.ItemBucketTileBinding
import com.retrotube.app.databinding.ItemManageSourceRowBinding
import com.retrotube.app.library.ContinueWatchingAdapter
import com.retrotube.app.library.FolderVideoScanner
import com.retrotube.app.library.LibraryRepository
import com.retrotube.app.library.TaggedFolder
import com.retrotube.app.library.TaggedFolderRepository
import com.retrotube.app.metadata.FolderMetadataRepository
import com.retrotube.app.metadata.VideoMetadataRepository
import com.retrotube.app.metadata.tmdb.MatchingEngine
import com.retrotube.app.metadata.tmdb.VideoRef
import com.retrotube.app.network.NetworkShareRepository
import com.retrotube.app.network.SmbUri
import com.retrotube.app.util.ScrapingProgressOverlay
import com.retrotube.app.progress.PlaybackProgressRepository
import java.util.concurrent.Executors
import com.retrotube.app.util.applyTopBarInset

/**
 * The Library tab: buckets referencing sources, showing rich TMDB metadata --
 * the default TV and Movies shelves (never deletable) plus any custom
 * buckets, with Continue Watching on top. This is the app's launcher/home
 * screen. Raw source management (adding/browsing local folders and SMB
 * shares) lives on the Sources tab instead; this screen never touches a
 * folder directly, only what's been tagged into a bucket.
 */
class LibraryActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLibraryBinding
    private lateinit var bucketRepository: BucketRepository
    private lateinit var taggedFolderRepository: TaggedFolderRepository
    private lateinit var libraryRepository: LibraryRepository
    private lateinit var progressRepository: PlaybackProgressRepository
    private lateinit var adapter: BucketTileAdapter
    private lateinit var continueWatchingAdapter: ContinueWatchingAdapter
    private val ioExecutor = Executors.newSingleThreadExecutor()
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLibraryBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.root.applyTopBarInset()

        bucketRepository = BucketRepository(this)
        taggedFolderRepository = TaggedFolderRepository(this)
        libraryRepository = LibraryRepository(this)
        progressRepository = PlaybackProgressRepository(this)

        adapter = BucketTileAdapter(
            onClick = { bucket ->
                startActivity(
                    Intent(this, BucketContentActivity::class.java)
                        .putExtra(BucketContentActivity.EXTRA_BUCKET_ID, bucket.id)
                        .putExtra(BucketContentActivity.EXTRA_BUCKET_NAME, bucket.name),
                )
            },
            onMenu = { bucket, anchor -> showBucketMenu(bucket, anchor) },
        )
        binding.bucketList.layoutManager = GridLayoutManager(this, com.retrotube.app.library.GridSpanCalculator.spanCount(this))
        binding.bucketList.adapter = adapter

        val railBinding = binding.continueWatchingRail
        continueWatchingAdapter = ContinueWatchingAdapter(this) { video ->
            val settings = com.retrotube.app.settings.SettingsRepository(this).effectiveSettings(video.document.uri.toString())
            startActivity(
                Intent(this, PlayerActivity::class.java).apply {
                    data = video.document.uri
                    putExtra(PlayerActivity.EXTRA_SETTINGS, settings.serialize())
                },
            )
        }
        railBinding.continueWatchingList.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        railBinding.continueWatchingList.adapter = continueWatchingAdapter

        binding.settingsButton.setOnClickListener {
            startActivity(
                Intent(this, EffectSettingsActivity::class.java).apply {
                    putExtra(EffectSettingsActivity.EXTRA_MODE, EffectSettingsActivity.MODE_GLOBAL)
                },
            )
        }
        binding.refreshLibraryButton.setOnClickListener { refreshAllBuckets() }
        binding.newBucketButton.setOnClickListener { promptNewBucket() }

        MainTabBar.setup(this, binding.root, MainTabBar.Tab.LIBRARY)
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val buckets = bucketRepository.getAll()
        binding.emptyStateText.visibility = if (buckets.isEmpty()) View.VISIBLE else View.GONE
        adapter.submitList(buckets)

        val continueWatching = libraryRepository.resolveVideoItems(progressRepository.getAllProgress().map { it.first })
        binding.continueWatchingContainer.visibility = if (continueWatching.isEmpty()) View.GONE else View.VISIBLE
        continueWatchingAdapter.submitList(continueWatching)
    }

    private fun showBucketMenu(bucket: Bucket, anchor: View) {
        PopupMenu(this, anchor).apply {
            menu.add(getString(R.string.manage_sources))
            if (bucket.isDeletable) menu.add(getString(R.string.remove))
            setOnMenuItemClickListener { menuItem ->
                when (menuItem.title) {
                    getString(R.string.manage_sources) -> showManageSourcesDialog(bucket)
                    getString(R.string.remove) -> {
                        bucketRepository.delete(bucket.id)
                        refresh()
                    }
                }
                true
            }
        }.show()
    }

    /** Lists every folder currently tagged into [bucket] (local or SMB alike),
     *  each removable on the spot -- untagging is instant and non-destructive
     *  (see [TaggedFolderRepository.untag]), never touching the folder's own
     *  files or its cached scrape results. */
    private fun showManageSourcesDialog(bucket: Bucket) {
        val dialogBinding = DialogManageSourcesBinding.inflate(layoutInflater)
        // A bottom sheet, not a full AlertDialog -- reads as a proper iOS-style
        // modal (rounded top corners, drag handle, slides up from the edge it
        // belongs to) instead of a floating box with its own chrome to fight.
        val dialog = com.google.android.material.bottomsheet.BottomSheetDialog(this)
        dialog.setContentView(dialogBinding.root)

        lateinit var rowAdapter: ManageSourcesRowAdapter
        fun reloadRows() {
            val tagged = taggedFolderRepository.getForBucket(bucket.id)
            dialogBinding.emptyStateText.visibility = if (tagged.isEmpty()) View.VISIBLE else View.GONE
            rowAdapter.submitList(tagged.map { it to friendlyLabelFor(it.folderKey) })
        }
        rowAdapter = ManageSourcesRowAdapter { folder ->
            taggedFolderRepository.untag(folder.folderKey)
            reloadRows()
            refresh()
        }
        dialogBinding.sourceRowList.layoutManager = LinearLayoutManager(this)
        dialogBinding.sourceRowList.adapter = rowAdapter
        reloadRows()

        dialogBinding.doneButton.setOnClickListener { dialog.dismiss() }
        dialog.setOnShowListener {
            val sheet = dialog.findViewById<android.widget.FrameLayout>(
                com.google.android.material.R.id.design_bottom_sheet,
            )
            sheet?.let {
                it.setBackgroundResource(android.R.color.transparent)
                com.google.android.material.bottomsheet.BottomSheetBehavior.from(it).state =
                    com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
            }
        }
        dialog.show()
    }

    /** A folder key is a full content:// or smb:// URI -- not something to show
     *  a user directly. Local folders show their own name; SMB folders show
     *  "share name/relative path", matching how a share's own folders read
     *  when browsing them on the Sources tab. */
    private fun friendlyLabelFor(folderKey: String): String {
        val uri = Uri.parse(folderKey)
        val smb = SmbUri.parse(uri)
        if (smb != null) {
            val (shareId, relativePath) = smb
            val shareName = NetworkShareRepository(this).get(shareId)?.displayName ?: shareId
            return if (relativePath.isEmpty()) shareName else "$shareName/$relativePath"
        }
        return runCatching { androidx.documentfile.provider.DocumentFile.fromTreeUri(this, uri)?.name }
            .getOrNull() ?: folderKey
    }

    private fun promptNewBucket() {
        val input = EditText(this)
        AlertDialog.Builder(this)
            .setTitle(R.string.bucket_new_prompt_title)
            .setView(input)
            .setPositiveButton(R.string.create) { _, _ ->
                val name = input.text?.toString()?.trim().orEmpty()
                if (name.isNotEmpty()) {
                    bucketRepository.create(name)
                    refresh()
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** Force-reload: re-matches every tagged folder across every bucket, fill-blanks-only
     *  unless already matched (see MatchingEngine/FillBlanksPolicy) -- the library-wide
     *  Refresh Library action (spec §5.6), not scoped to just one bucket. */
    private fun refreshAllBuckets() {
        val tagged = bucketRepository.getAll().flatMap { taggedFolderRepository.getForBucket(it.id) }
        if (tagged.isEmpty()) return
        val overlay = ScrapingProgressOverlay(this)
        overlay.show(getString(R.string.refresh_started))
        val engine = MatchingEngine(VideoMetadataRepository(this), FolderMetadataRepository(this))
        ioExecutor.execute {
            tagged.forEach { FolderVideoScanner.invalidate(it.folderKey) }
            data class WorkUnit(val kind: ContentKind, val folderKey: String, val label: String)
            val units = tagged.flatMap { folder ->
                when (folder.contentKind) {
                    ContentKind.SHOW -> listOf(WorkUnit(ContentKind.SHOW, folder.folderKey, folder.folderKey))
                    ContentKind.MOVIE -> FolderVideoScanner.listVideosRecursively(this, folder.folderKey)
                        .map { WorkUnit(ContentKind.MOVIE, it.uri.toString(), it.name) }
                }
            }
            units.forEachIndexed { index, unit ->
                overlay.update(getString(R.string.scraping_progress_format, index + 1, units.size))
                when (unit.kind) {
                    ContentKind.SHOW -> {
                        val refs = FolderVideoScanner.listVideosRecursively(this, unit.folderKey)
                            .map { VideoRef(it.uri.toString(), it.name) }
                        engine.matchShowFolder(unit.folderKey, refs, forced = false)
                    }
                    ContentKind.MOVIE -> engine.matchMovie(VideoRef(unit.folderKey, unit.label), forced = false)
                }
            }
            mainHandler.post {
                overlay.finish(getString(R.string.refresh_done))
                refresh()
            }
        }
    }
}

/** Buckets render as a poster-grid, same card language as everywhere else in
 *  the app (Bucket content, Show detail, Season episodes) -- a plain name+count
 *  row read as "very wrong" next to the iOS app's actual grid of shelf cards. A
 *  bucket has no poster of its own yet, so each card shows a type-appropriate
 *  placeholder icon (TV / Movies get their own; a custom bucket gets a generic
 *  one) centered on the card art. */
class BucketTileAdapter(
    private val onClick: (Bucket) -> Unit,
    private val onMenu: (Bucket, View) -> Unit,
) : RecyclerView.Adapter<BucketTileAdapter.ViewHolder>() {

    private var buckets: List<Bucket> = emptyList()

    fun submitList(newBuckets: List<Bucket>) {
        buckets = newBuckets
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): ViewHolder =
        ViewHolder(ItemBucketTileBinding.inflate(android.view.LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val bucket = buckets[position]
        holder.binding.bucketName.text = bucket.name
        holder.binding.bucketPlaceholderIcon.setImageResource(
            when (bucket.id) {
                BucketRepository.BUCKET_TV -> R.drawable.ic_tv
                BucketRepository.BUCKET_MOVIES -> R.drawable.ic_movie
                else -> R.drawable.ic_video_library
            },
        )
        holder.binding.root.setOnClickListener { onClick(bucket) }
        holder.binding.bucketMenuButton.setOnClickListener { onMenu(bucket, it) }
    }

    override fun getItemCount(): Int = buckets.size

    class ViewHolder(val binding: ItemBucketTileBinding) : RecyclerView.ViewHolder(binding.root)
}

/** One row per source tagged into a bucket, in the "Manage Sources" dialog --
 *  a friendly label plus a red remove-circle, same interaction as an iOS
 *  edit-mode delete row. */
class ManageSourcesRowAdapter(
    private val onRemove: (TaggedFolder) -> Unit,
) : RecyclerView.Adapter<ManageSourcesRowAdapter.ViewHolder>() {

    private var rows: List<Pair<TaggedFolder, String>> = emptyList()

    fun submitList(newRows: List<Pair<TaggedFolder, String>>) {
        rows = newRows
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): ViewHolder =
        ViewHolder(ItemManageSourceRowBinding.inflate(android.view.LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val (folder, label) = rows[position]
        holder.binding.rowLabel.text = label
        holder.binding.rowRemoveButton.setOnClickListener { onRemove(folder) }
    }

    override fun getItemCount(): Int = rows.size

    class ViewHolder(val binding: ItemManageSourceRowBinding) : RecyclerView.ViewHolder(binding.root)
}
