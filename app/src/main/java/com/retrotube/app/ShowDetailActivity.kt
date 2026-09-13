package com.retrotube.app

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.updatePadding
import androidx.recyclerview.widget.GridLayoutManager
import com.retrotube.app.bucket.PosterTile
import com.retrotube.app.bucket.PosterTileAdapter
import com.retrotube.app.databinding.ActivityShowDetailBinding
import com.retrotube.app.library.FolderVideoScanner
import com.retrotube.app.metadata.FolderMetadataRepository
import com.retrotube.app.metadata.VideoMetadataRepository
import com.retrotube.app.metadata.tmdb.MatchingEngine
import com.retrotube.app.metadata.tmdb.VideoRef

/** Hero (backdrop/title/overview) + a season grid for one SHOW-tagged folder.
 *  Seasons are derived from whatever season numbers actually appear on the
 *  folder's videos ([VideoMetadataRepository.getSeasonNumber]) rather than a
 *  separate season store -- there's nothing else a season "is" here besides
 *  a grouping of already-matched episodes. */
class ShowDetailActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_FOLDER_KEY = "folder_key"
    }

    private lateinit var binding: ActivityShowDetailBinding
    private lateinit var folderMetadataRepository: FolderMetadataRepository
    private lateinit var videoMetadataRepository: VideoMetadataRepository
    private lateinit var folderKey: String
    private val ioExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityShowDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)

        folderKey = intent.getStringExtra(EXTRA_FOLDER_KEY) ?: run { finish(); return }
        folderMetadataRepository = FolderMetadataRepository(this)
        videoMetadataRepository = VideoMetadataRepository(this)

        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        @Suppress("DEPRECATION")
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(binding.heroContainer) { _, insets ->
            val statusBarInset = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.statusBars()).top
            binding.backButton.updatePadding(top = 16 + statusBarInset)
            binding.heroButtonRow.updatePadding(top = statusBarInset)
            insets
        }

        binding.backButton.setOnClickListener { finish() }
        binding.seasonGrid.layoutManager = GridLayoutManager(this, com.retrotube.app.library.GridSpanCalculator.spanCount(this))
        binding.settingsButton.setOnClickListener {
            startActivity(
                Intent(this, EffectSettingsActivity::class.java).apply {
                    putExtra(EffectSettingsActivity.EXTRA_MODE, EffectSettingsActivity.MODE_GLOBAL)
                },
            )
        }
        binding.forceRefreshButton.setOnClickListener { forceRefreshShow() }
    }

    /** Force Refresh at the show level -- re-matches this one folder from
     *  scratch (forced=true bypasses fill-blanks-only, see FillBlanksPolicy),
     *  the per-folder scope from the refresh-action table (spec §5.6),
     *  distinct from a bucket-wide or library-wide refresh. */
    private fun forceRefreshShow() {
        val showName = folderMetadataRepository.getCustomTitle(folderKey) ?: getString(R.string.refresh_started)
        val overlay = com.retrotube.app.util.ScrapingProgressOverlay(this)
        overlay.show(getString(R.string.scraping_matching_single, showName))
        val engine = MatchingEngine(videoMetadataRepository, folderMetadataRepository)
        ioExecutor.execute {
            FolderVideoScanner.invalidate(folderKey)
            val refs = FolderVideoScanner.listVideosRecursively(this, folderKey).map { VideoRef(it.uri.toString(), it.name) }
            engine.matchShowFolder(folderKey, refs, forced = true)
            mainHandler.post {
                overlay.finish(getString(R.string.refresh_done))
                refresh()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        binding.showTitle.text = folderMetadataRepository.getCustomTitle(folderKey).orEmpty()
        binding.showOverview.text = folderMetadataRepository.getOverview(folderKey).orEmpty()
        val genres = folderMetadataRepository.getGenres(folderKey)
        val rating = folderMetadataRepository.getRating(folderKey)
        binding.showMeta.text = listOfNotNull(
            genres.takeIf { it.isNotEmpty() }?.joinToString(" · "),
            rating?.let { "★ %.1f".format(it) },
        ).joinToString("  ")
        binding.backdropImage.setImageBitmap(
            folderMetadataRepository.getBackdrop(folderKey) ?: folderMetadataRepository.getPoster(folderKey),
        )

        binding.loadingSpinner.visibility = View.VISIBLE
        binding.emptyStateText.visibility = View.GONE
        ioExecutor.execute {
            val videos = FolderVideoScanner.listVideosRecursively(this, folderKey)
            val seasonNumbers = videos.mapNotNull { videoMetadataRepository.getSeasonNumber(it.uri.toString()) }
                .distinct().sorted()
            val tiles = seasonNumbers.map { season ->
                PosterTile(
                    id = season.toString(),
                    title = getString(R.string.season_number, season),
                    poster = folderMetadataRepository.getSeasonPoster(folderKey, season) ?: folderMetadataRepository.getPoster(folderKey),
                    onClick = {
                        startActivity(
                            Intent(this, SeasonEpisodesActivity::class.java)
                                .putExtra(SeasonEpisodesActivity.EXTRA_FOLDER_KEY, folderKey)
                                .putExtra(SeasonEpisodesActivity.EXTRA_SEASON_NUMBER, season),
                        )
                    },
                )
            }
            mainHandler.post {
                binding.loadingSpinner.visibility = View.GONE
                binding.emptyStateText.visibility = if (seasonNumbers.isEmpty()) View.VISIBLE else View.GONE
                (binding.seasonGrid.adapter as? PosterTileAdapter ?: PosterTileAdapter().also { binding.seasonGrid.adapter = it })
                    .submitList(tiles)
            }
        }
    }
}
