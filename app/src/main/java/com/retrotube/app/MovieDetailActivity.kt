package com.retrotube.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.updatePadding
import com.retrotube.app.databinding.ActivityMovieDetailBinding
import com.retrotube.app.metadata.VideoMetadataRepository
import com.retrotube.app.settings.SettingsRepository

class MovieDetailActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_VIDEO_URI = "video_uri"
    }

    private lateinit var binding: ActivityMovieDetailBinding
    private lateinit var videoMetadataRepository: VideoMetadataRepository
    private lateinit var videoUri: Uri

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMovieDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val uriString = intent.getStringExtra(EXTRA_VIDEO_URI) ?: run { finish(); return }
        videoUri = Uri.parse(uriString)
        videoMetadataRepository = VideoMetadataRepository(this)

        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        @Suppress("DEPRECATION")
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(binding.heroContainer) { _, insets ->
            val statusBarInset = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.statusBars()).top
            binding.backButton.updatePadding(top = 16 + statusBarInset)
            insets
        }

        binding.backButton.setOnClickListener { finish() }
        binding.playButton.setOnClickListener { launchPlayer() }
        refresh(uriString)
    }

    private fun refresh(uriString: String) {
        binding.movieTitle.text = videoMetadataRepository.getCustomTitle(uriString).orEmpty()
        binding.movieOverview.text = videoMetadataRepository.getOverview(uriString).orEmpty()
        binding.movieTagline.text = videoMetadataRepository.getTagline(uriString).orEmpty()
        binding.backdropImage.setImageBitmap(
            videoMetadataRepository.getBackdrop(uriString) ?: videoMetadataRepository.getCustomThumbnail(uriString),
        )

        val year = videoMetadataRepository.getYear(uriString)
        val runtime = videoMetadataRepository.getRuntimeMinutes(uriString)
        val genres = videoMetadataRepository.getGenres(uriString)
        val rating = videoMetadataRepository.getRating(uriString)
        binding.movieMeta.text = listOfNotNull(
            year?.toString(),
            runtime?.let { getString(R.string.movie_runtime_minutes, it) },
            genres.takeIf { it.isNotEmpty() }?.joinToString(" · "),
            rating?.let { "★ %.1f".format(it) },
        ).joinToString("  ")
    }

    private fun launchPlayer() {
        val settings = SettingsRepository(this).effectiveSettings(videoUri.toString())
        startActivity(
            Intent(this, PlayerActivity::class.java).apply {
                data = videoUri
                putExtra(PlayerActivity.EXTRA_SETTINGS, settings.serialize())
            },
        )
    }
}
