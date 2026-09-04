package com.retrotube.app

import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import com.retrotube.app.databinding.ActivityPlayerBinding
import com.retrotube.app.network.NetworkShareRepository
import com.retrotube.app.network.SmbDataSource
import com.retrotube.app.network.SmbUri
import com.retrotube.app.progress.PlaybackProgressRepository
import com.retrotube.app.settings.VideoEffectSettings
import com.retrotube.app.shader.CrtGlEffect
import com.retrotube.app.shader.DownscaleGlEffect
import com.retrotube.app.shader.DownscaleTarget
import com.retrotube.app.shader.ShaderPreset

@UnstableApi
class PlayerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_SETTINGS = "extra_settings"
        private const val PROGRESS_SAVE_INTERVAL_MS = 5_000L
        private const val AMBIENT_IDLE_DELAY_MS = 20_000L
    }

    private lateinit var binding: ActivityPlayerBinding
    private lateinit var progressRepository: PlaybackProgressRepository
    private var player: ExoPlayer? = null
    private var videoUri: Uri? = null

    private val progressHandler = Handler(Looper.getMainLooper())
    private val progressSaver = object : Runnable {
        override fun run() {
            saveProgress()
            progressHandler.postDelayed(this, PROGRESS_SAVE_INTERVAL_MS)
        }
    }

    /** Dims to an ambient standby look after sitting paused for a while, rather than just
     *  a frozen frame -- "feels alive, not broken." */
    private val ambientHandler = Handler(Looper.getMainLooper())
    private val showAmbient = Runnable {
        // PlayerView re-raises its own controller above siblings whenever it toggles
        // visibility, so our overlay has to reassert itself on top each time too.
        binding.ambientOverlay.visibility = android.view.View.VISIBLE
        binding.ambientOverlay.bringToFront()
        binding.ambientOverlay.animate().alpha(1f).setDuration(1200).start()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Otherwise the device's normal screen timeout kicks in mid-playback, since
        // there's no touch input for the system to know a video is actively playing.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        binding = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        progressRepository = PlaybackProgressRepository(this)
    }

    override fun onStart() {
        super.onStart()
        initializePlayer()
    }

    override fun onStop() {
        super.onStop()
        saveProgress()
        releasePlayer()
    }

    private fun initializePlayer() {
        val uri = intent.data ?: return
        videoUri = uri
        val settings = VideoEffectSettings.deserialize(intent.getStringExtra(EXTRA_SETTINGS))
            ?: VideoEffectSettings.DEFAULT

        val exoPlayer = ExoPlayer.Builder(this).build()
        binding.playerView.player = exoPlayer
        binding.playerView.resizeMode = settings.aspectMode

        val hasEffects = settings.preset != ShaderPreset.NONE || settings.curvatureEnabled
        if (hasEffects) {
            val effects = mutableListOf<Effect>()
            if (settings.downscale != DownscaleTarget.NATIVE) {
                // Reference is the physical screen height, so the shaded buffer's final
                // upscale back to the display is always a clean integer multiple.
                val referenceHeight = resources.displayMetrics.heightPixels
                effects.add(DownscaleGlEffect(settings.downscale.targetHeight, referenceHeight))
            }
            effects.add(CrtGlEffect(settings.preset, settings.curvatureEnabled))
            exoPlayer.setVideoEffects(effects)

            // Lives inside retro_player_control_view.xml's exo_basic_controls, so it fades
            // in/out together with the rest of the transport controls automatically --
            // no manual visibility syncing needed.
            val compareButton = binding.playerView.findViewById<TextView>(R.id.compareButton)
            var showingRaw = false
            compareButton.setOnClickListener {
                showingRaw = !showingRaw
                exoPlayer.setVideoEffects(if (showingRaw) emptyList() else effects)
                compareButton.text = if (showingRaw) {
                    getString(R.string.compare_button_active)
                } else {
                    getString(R.string.compare_button)
                }
                compareButton.setBackgroundColor(
                    if (showingRaw) {
                        ContextCompat.getColor(this, R.color.retro_magenta)
                    } else {
                        0x40FFFFFF
                    },
                )
            }
        }

        val savedProgress = progressRepository.getProgress(uri.toString())
        var hasResumed = false

        exoPlayer.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY && savedProgress != null && !hasResumed) {
                    hasResumed = true
                    exoPlayer.seekTo(savedProgress.positionMs)
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) {
                    ambientHandler.removeCallbacks(showAmbient)
                    binding.ambientOverlay.animate().cancel()
                    binding.ambientOverlay.alpha = 0f
                    binding.ambientOverlay.visibility = android.view.View.GONE
                } else {
                    ambientHandler.postDelayed(showAmbient, AMBIENT_IDLE_DELAY_MS)
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                Log.e("PlayerActivity", "Playback failed for $uri", error)
                Toast.makeText(
                    this@PlayerActivity,
                    getString(R.string.playback_failed, error.cause?.message ?: error.message),
                    Toast.LENGTH_LONG,
                ).show()
            }
        })

        if (SmbUri.parse(uri) != null) {
            val mediaSource = ProgressiveMediaSource.Factory(SmbDataSource.Factory(NetworkShareRepository(this)))
                .createMediaSource(MediaItem.fromUri(uri))
            exoPlayer.setMediaSource(mediaSource)
        } else {
            exoPlayer.setMediaItem(MediaItem.fromUri(uri))
        }
        exoPlayer.prepare()
        exoPlayer.playWhenReady = true

        player = exoPlayer
        progressHandler.postDelayed(progressSaver, PROGRESS_SAVE_INTERVAL_MS)
    }

    private fun saveProgress() {
        val uri = videoUri ?: return
        val exoPlayer = player ?: return
        if (exoPlayer.duration <= 0) return
        progressRepository.saveProgress(uri.toString(), exoPlayer.currentPosition, exoPlayer.duration)
    }

    private fun releasePlayer() {
        progressHandler.removeCallbacks(progressSaver)
        ambientHandler.removeCallbacks(showAmbient)
        player?.release()
        player = null
    }
}
