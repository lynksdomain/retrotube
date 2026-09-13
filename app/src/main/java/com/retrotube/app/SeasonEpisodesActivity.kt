package com.retrotube.app

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.retrotube.app.bucket.EpisodeRow
import com.retrotube.app.bucket.EpisodeRowAdapter
import com.retrotube.app.databinding.ActivitySeasonEpisodesBinding
import com.retrotube.app.library.FolderVideoScanner
import com.retrotube.app.metadata.VideoMetadataRepository
import com.retrotube.app.settings.SettingsRepository
import com.retrotube.app.util.applyTopBarInset

/** A row list, not a poster grid -- an episode is picked from a list of
 *  things (thumbnail, number, title, air date, overview), matching the iOS
 *  app's episode view rather than being browsed like a shelf of posters. */
class SeasonEpisodesActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_FOLDER_KEY = "folder_key"
        const val EXTRA_SEASON_NUMBER = "season_number"
    }

    private lateinit var binding: ActivitySeasonEpisodesBinding
    private lateinit var videoMetadataRepository: VideoMetadataRepository
    private lateinit var folderKey: String
    private var seasonNumber: Int = -1
    private val ioExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySeasonEpisodesBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.root.applyTopBarInset()

        folderKey = intent.getStringExtra(EXTRA_FOLDER_KEY) ?: run { finish(); return }
        seasonNumber = intent.getIntExtra(EXTRA_SEASON_NUMBER, -1)
        videoMetadataRepository = VideoMetadataRepository(this)

        binding.seasonTitleText.text = getString(R.string.season_number, seasonNumber)
        binding.backButton.setOnClickListener { finish() }
        binding.settingsButton.setOnClickListener {
            startActivity(
                Intent(this, EffectSettingsActivity::class.java).apply {
                    putExtra(EffectSettingsActivity.EXTRA_MODE, EffectSettingsActivity.MODE_GLOBAL)
                },
            )
        }
        binding.episodeGrid.layoutManager = LinearLayoutManager(this)
        refresh()
    }

    private fun refresh() {
        binding.loadingSpinner.visibility = View.VISIBLE
        binding.emptyStateText.visibility = View.GONE
        ioExecutor.execute {
            val videos = FolderVideoScanner.listVideosRecursively(this, folderKey)
            val episodes = videos
                .filter { videoMetadataRepository.getSeasonNumber(it.uri.toString()) == seasonNumber }
                .sortedBy { videoMetadataRepository.getEpisodeNumber(it.uri.toString()) ?: Int.MAX_VALUE }
            val queue = episodes.map { it.uri }
            val rows = episodes.mapIndexed { index, video ->
                val uriString = video.uri.toString()
                val episodeNumber = videoMetadataRepository.getEpisodeNumber(uriString)
                EpisodeRow(
                    id = uriString,
                    episodeLabel = episodeNumber?.let { getString(R.string.episode_number, it) }.orEmpty(),
                    title = videoMetadataRepository.getCustomTitle(uriString) ?: video.name,
                    airDate = videoMetadataRepository.getAirDate(uriString),
                    overview = videoMetadataRepository.getOverview(uriString),
                    thumbnail = videoMetadataRepository.getCustomThumbnail(uriString),
                    onClick = { launchPlayer(video.uri, queue, index) },
                )
            }
            mainHandler.post {
                binding.loadingSpinner.visibility = View.GONE
                binding.emptyStateText.visibility = if (episodes.isEmpty()) View.VISIBLE else View.GONE
                (binding.episodeGrid.adapter as? EpisodeRowAdapter ?: EpisodeRowAdapter().also { binding.episodeGrid.adapter = it })
                    .submitList(rows)
            }
        }
    }

    private fun launchPlayer(uri: android.net.Uri, queue: List<android.net.Uri>, index: Int) {
        val settings = SettingsRepository(this).effectiveSettings(uri.toString())
        startActivity(
            Intent(this, PlayerActivity::class.java).apply {
                data = uri
                putExtra(PlayerActivity.EXTRA_SETTINGS, settings.serialize())
                if (queue.size > 1) {
                    putStringArrayListExtra(PlayerActivity.EXTRA_QUEUE_URIS, ArrayList(queue.map { it.toString() }))
                    putExtra(PlayerActivity.EXTRA_QUEUE_INDEX, index)
                }
            },
        )
    }
}
