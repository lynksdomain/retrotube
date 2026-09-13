package com.retrotube.app

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import com.retrotube.app.bucket.ContentKind
import com.retrotube.app.bucket.PosterTile
import com.retrotube.app.bucket.PosterTileAdapter
import com.retrotube.app.databinding.ActivityBucketContentBinding
import com.retrotube.app.library.FolderVideoScanner
import com.retrotube.app.library.TaggedFolderRepository
import com.retrotube.app.metadata.FolderMetadataRepository
import com.retrotube.app.metadata.VideoMetadataRepository
import com.retrotube.app.metadata.tmdb.MatchingEngine
import com.retrotube.app.metadata.tmdb.VideoRef
import java.util.concurrent.Executors
import com.retrotube.app.util.ScrapingProgressOverlay
import com.retrotube.app.util.applyTopBarInset

/**
 * Flat grid of whatever's tagged into one bucket (see [TaggedFolderRepository])
 * -- a SHOW-tagged folder shows as one show tile (opens [ShowDetailActivity]);
 * a MOVIE-tagged folder is flattened into its individual movie files (each
 * opens [MovieDetailActivity]), since Movies is a flat shelf, not a folder tree.
 */
class BucketContentActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_BUCKET_ID = "bucket_id"
        const val EXTRA_BUCKET_NAME = "bucket_name"
    }

    private lateinit var binding: ActivityBucketContentBinding
    private lateinit var taggedFolderRepository: TaggedFolderRepository
    private lateinit var folderMetadataRepository: FolderMetadataRepository
    private lateinit var videoMetadataRepository: VideoMetadataRepository
    private lateinit var bucketId: String
    private val ioExecutor = Executors.newSingleThreadExecutor()
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityBucketContentBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.root.applyTopBarInset()

        bucketId = intent.getStringExtra(EXTRA_BUCKET_ID) ?: run { finish(); return }
        binding.bucketNameText.text = intent.getStringExtra(EXTRA_BUCKET_NAME).orEmpty()

        taggedFolderRepository = TaggedFolderRepository(this)
        folderMetadataRepository = FolderMetadataRepository(this)
        videoMetadataRepository = VideoMetadataRepository(this)

        binding.contentGrid.layoutManager = GridLayoutManager(this, com.retrotube.app.library.GridSpanCalculator.spanCount(this))
        binding.backButton.setOnClickListener { finish() }
        binding.refreshLibraryButton.setOnClickListener { refreshBucket() }
        binding.settingsButton.setOnClickListener {
            startActivity(
                android.content.Intent(this, EffectSettingsActivity::class.java).apply {
                    putExtra(EffectSettingsActivity.EXTRA_MODE, EffectSettingsActivity.MODE_GLOBAL)
                },
            )
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    /** Building the tile list is real I/O the moment any tagged folder is on an
     *  SMB share (folder scans, not just reading already-cached poster/title
     *  metadata) -- so this whole pass runs off the main thread now, unlike
     *  when only local (SAF) folders were possible here. */
    private fun refresh() {
        val tagged = taggedFolderRepository.getForBucket(bucketId)
        binding.loadingSpinner.visibility = View.VISIBLE
        binding.emptyStateText.visibility = View.GONE
        ioExecutor.execute {
            val tiles = mutableListOf<PosterTile>()
            for (folder in tagged) {
                when (folder.contentKind) {
                    ContentKind.SHOW -> {
                        val title = folderMetadataRepository.getCustomTitle(folder.folderKey) ?: continue
                        tiles += PosterTile(
                            id = folder.folderKey,
                            title = title,
                            poster = folderMetadataRepository.getPoster(folder.folderKey),
                            onClick = {
                                startActivity(
                                    android.content.Intent(this, ShowDetailActivity::class.java)
                                        .putExtra(ShowDetailActivity.EXTRA_FOLDER_KEY, folder.folderKey),
                                )
                            },
                        )
                    }
                    ContentKind.MOVIE -> {
                        for (video in FolderVideoScanner.listVideosRecursively(this, folder.folderKey)) {
                            val uriString = video.uri.toString()
                            if (videoMetadataRepository.isExcludedFromLibrary(uriString)) continue
                            val title = videoMetadataRepository.getCustomTitle(uriString) ?: video.name
                            tiles += PosterTile(
                                id = uriString,
                                title = title,
                                poster = videoMetadataRepository.getCustomThumbnail(uriString),
                                onClick = {
                                    startActivity(
                                        android.content.Intent(this, MovieDetailActivity::class.java)
                                            .putExtra(MovieDetailActivity.EXTRA_VIDEO_URI, uriString),
                                    )
                                },
                                onMenuClick = { anchor -> showMovieTileMenu(anchor, uriString, video.name) },
                            )
                        }
                    }
                }
            }
            mainHandler.post {
                binding.loadingSpinner.visibility = View.GONE
                binding.emptyStateText.visibility = if (tiles.isEmpty()) View.VISIBLE else View.GONE
                (binding.contentGrid.adapter as? PosterTileAdapter ?: PosterTileAdapter().also { binding.contentGrid.adapter = it })
                    .submitList(tiles)
            }
        }
    }

    /** Per-movie-tile "⋯" menu (spec-matched to the iOS Movies grid): edit the
     *  matched info, re-match just this one file, or hide it from the Library
     *  without touching its tagged folder or the file itself. */
    private fun showMovieTileMenu(anchor: android.view.View, videoUri: String, filename: String) {
        android.widget.PopupMenu(this, anchor).apply {
            menu.add(getString(R.string.edit_info))
            menu.add(getString(R.string.force_refresh))
            menu.add(getString(R.string.remove_from_library))
            setOnMenuItemClickListener { menuItem ->
                when (menuItem.title) {
                    getString(R.string.edit_info) -> {
                        startActivity(
                            android.content.Intent(this@BucketContentActivity, MovieEditInfoActivity::class.java)
                                .putExtra(MovieEditInfoActivity.EXTRA_VIDEO_URI, videoUri),
                        )
                    }
                    getString(R.string.force_refresh) -> forceRefreshMovie(videoUri, filename)
                    getString(R.string.remove_from_library) -> {
                        videoMetadataRepository.setExcludedFromLibrary(videoUri, true)
                        Toast.makeText(this@BucketContentActivity, R.string.removed_from_library, Toast.LENGTH_SHORT).show()
                        refresh()
                    }
                }
                true
            }
        }.show()
    }

    private fun forceRefreshMovie(videoUri: String, filename: String) {
        val overlay = ScrapingProgressOverlay(this)
        overlay.show(getString(R.string.scraping_matching_single, filename))
        val engine = MatchingEngine(videoMetadataRepository, folderMetadataRepository)
        ioExecutor.execute {
            engine.matchMovie(VideoRef(videoUri, filename), forced = true)
            mainHandler.post {
                overlay.finish(getString(R.string.refresh_done))
                refresh()
            }
        }
    }

    /** Re-matches every folder currently tagged into this bucket -- the bucket-scoped
     *  slice of the spec's library-wide Refresh Library action (§5.6); fill-blanks-only
     *  still applies per folder unless it's a Force Refresh. Local and SMB folders are
     *  matched exactly the same way here. Progress is reported per work unit (one show
     *  folder, or one movie file) rather than a single toast that would disappear long
     *  before a bucket full of shows finished matching. */
    private fun refreshBucket() {
        val tagged = taggedFolderRepository.getForBucket(bucketId)
        if (tagged.isEmpty()) return
        val overlay = ScrapingProgressOverlay(this)
        overlay.show(getString(R.string.refresh_started))
        val engine = MatchingEngine(videoMetadataRepository, folderMetadataRepository)
        ioExecutor.execute {
            // A Refresh action is exactly the case where a stale scan would be
            // wrong -- the whole point is picking up files that changed since
            // the cache was populated.
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
