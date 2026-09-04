package com.retrotube.app

import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
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
import com.retrotube.app.collections.CollectionRepository
import com.retrotube.app.databinding.ActivityPlayerBinding
import com.retrotube.app.library.LibraryRepository
import com.retrotube.app.metadata.VideoMetadataRepository
import com.retrotube.app.network.NetworkShareRepository
import com.retrotube.app.network.SmbDataSource
import com.retrotube.app.network.SmbUri
import com.retrotube.app.progress.PlaybackProgressRepository
import com.retrotube.app.settings.VideoEffectSettings
import com.retrotube.app.shader.CrtGlEffect
import com.retrotube.app.shader.DownscaleGlEffect
import com.retrotube.app.shader.DownscaleTarget
import com.retrotube.app.shader.ShaderPreset
import com.retrotube.app.tv.TvChannel
import com.retrotube.app.tv.TvChannelRepository

@UnstableApi
class PlayerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_SETTINGS = "extra_settings"
        const val EXTRA_TV_MODE = "extra_tv_mode"
        private const val PROGRESS_SAVE_INTERVAL_MS = 5_000L
        private const val AMBIENT_IDLE_DELAY_MS = 20_000L
        private const val TV_CONTROLS_HIDE_DELAY_MS = 4_000L
        private const val TV_TRANSITION_FADE_MS = 250L
        private const val TV_STATIC_HOLD_MS = 500L
        private const val TV_STATIC_FADE_OUT_MS = 150L
    }

    private lateinit var binding: ActivityPlayerBinding
    private lateinit var progressRepository: PlaybackProgressRepository
    private var player: ExoPlayer? = null
    private var videoUri: Uri? = null
    private var effects: List<Effect> = emptyList()

    private var isTvMode = false
    private lateinit var tvChannelRepository: TvChannelRepository
    private var tvChannels: List<TvChannel> = emptyList()
    private var tvChannelIndex: Int = 0

    private val progressHandler = Handler(Looper.getMainLooper())
    private val progressSaver = object : Runnable {
        override fun run() {
            saveProgress()
            progressHandler.postDelayed(this, PROGRESS_SAVE_INTERVAL_MS)
        }
    }

    /** Dims to an ambient standby look after sitting paused for a while, rather than just
     *  a frozen frame -- "feels alive, not broken." Not used in TV mode, which has no
     *  pause and manages its own always-brief control overlay instead. */
    private val ambientHandler = Handler(Looper.getMainLooper())
    private val showAmbient = Runnable {
        // PlayerView re-raises its own controller above siblings whenever it toggles
        // visibility, so our overlay has to reassert itself on top each time too.
        binding.ambientOverlay.visibility = View.VISIBLE
        binding.ambientOverlay.bringToFront()
        binding.ambientOverlay.animate().alpha(1f).setDuration(1200).start()
    }

    /** TV mode has no exo controller, so it manages its own tap-to-reveal overlay:
     *  a tap shows the channel badge and the up/down/power column, and they fade
     *  back out on their own after a few seconds -- there's nothing to pause, so
     *  nothing to keep them open for. The power button lives inside tvModeControls,
     *  so it shows and hides along with the rest of that column. */
    private val tvControlsHandler = Handler(Looper.getMainLooper())
    private val hideTvControls = Runnable {
        binding.tvModeControls.animate().alpha(0f).setDuration(300)
            .withEndAction { binding.tvModeControls.visibility = View.GONE }.start()
    }

    /** Runs the static "no signal" hold between a channel-up/down tap and the next
     *  channel actually starting -- see [changeChannel]. */
    private val tvStaticHandler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Otherwise the device's normal screen timeout kicks in mid-playback, since
        // there's no touch input for the system to know a video is actively playing.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        binding = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        progressRepository = PlaybackProgressRepository(this)
        isTvMode = intent.getBooleanExtra(EXTRA_TV_MODE, false)
        if (isTvMode) {
            setUpTvMode()
        }
    }

    override fun onStart() {
        super.onStart()
        if (isTvMode) {
            initializeTvPlayer()
        } else {
            initializePlayer()
        }
    }

    override fun onStop() {
        super.onStop()
        saveProgress()
        if (isTvMode) {
            saveTvPosition()
        }
        releasePlayer()
    }

    private fun setUpTvMode() {
        binding.playerView.useController = false
        tvChannelRepository = TvChannelRepository(
            this,
            LibraryRepository(this),
            CollectionRepository(this),
            progressRepository,
            VideoMetadataRepository(this),
        )
        tvChannels = tvChannelRepository.getChannels()
        if (tvChannels.isEmpty()) {
            Toast.makeText(this, getString(R.string.tv_mode_no_content), Toast.LENGTH_LONG).show()
            finish()
            return
        }
        val lastChannelId = tvChannelRepository.getLastChannelId()
        tvChannelIndex = tvChannels.indexOfFirst { it.id == lastChannelId }.takeIf { it >= 0 } ?: 0

        binding.playerView.setOnClickListener { toggleTvControls() }
        binding.channelUpButton.setOnClickListener { changeChannel(1) }
        binding.channelDownButton.setOnClickListener { changeChannel(-1) }
        binding.exitTvModeButton.setOnClickListener { finish() }
    }

    private fun initializeTvPlayer() {
        val channel = tvChannels[tvChannelIndex]
        val videoIndex = tvChannelRepository.getCurrentIndex(channel)
        playTvVideo(channel, videoIndex)
        showTvControls()
    }

    private fun playTvVideo(channel: TvChannel, videoIndex: Int) {
        saveProgress()
        releasePlayer()
        val video = channel.videos[videoIndex]
        videoUri = video.uri
        tvChannelRepository.setCurrentIndex(channel.id, videoIndex)
        tvChannelRepository.setLastChannelId(channel.id)

        val exoPlayer = createPlayer(video.uri, VideoEffectSettings.TV_MODE, autoAdvanceOnEnd = true)
        player = exoPlayer
        progressHandler.postDelayed(progressSaver, PROGRESS_SAVE_INTERVAL_MS)

    }

    /** A manual channel flip gets a beat of tuner static before the next channel
     *  comes in -- covers the real, variable time a new source takes to start
     *  (worse over SMB) the same way a real tuner's snow does, rather than a
     *  flash of black or a frozen frame. The channel-number readout only exists
     *  on top of that static, the way a CRT's OSD only appears while tuning --
     *  it's not part of the tap-to-reveal transport controls. */
    private fun changeChannel(direction: Int) {
        if (tvChannels.isEmpty()) return
        tvChannelIndex = (tvChannelIndex + direction).mod(tvChannels.size)
        showTvControls()

        binding.tvStaticView.osdText = getString(R.string.tv_channel_osd, tvChannels[tvChannelIndex].number)
        binding.tvStaticView.visibility = View.VISIBLE
        binding.tvStaticView.alpha = 1f
        binding.tvStaticView.animate().cancel()
        binding.tvStaticView.bringToFront()
        binding.tvStaticView.start()

        tvStaticHandler.removeCallbacksAndMessages(null)
        tvStaticHandler.postDelayed({
            val channel = tvChannels[tvChannelIndex]
            val videoIndex = tvChannelRepository.getCurrentIndex(channel)
            playTvVideo(channel, videoIndex)
            binding.tvStaticView.animate().alpha(0f).setDuration(TV_STATIC_FADE_OUT_MS)
                .withEndAction {
                    binding.tvStaticView.stop()
                    binding.tvStaticView.visibility = View.GONE
                    binding.tvStaticView.osdText = null
                }.start()
        }, TV_STATIC_HOLD_MS)
    }

    /** Advancing to the next video within the same channel (episode ended) is not
     *  a channel change -- a quick fade reads as "next thing," not "retuning." */
    private fun advanceWithinChannel() {
        val channel = tvChannels.getOrNull(tvChannelIndex) ?: return
        val currentIndex = tvChannelRepository.getCurrentIndex(channel)
        val nextIndex = (currentIndex + 1) % channel.videos.size

        binding.ambientOverlay.visibility = View.VISIBLE
        binding.ambientOverlay.bringToFront()
        binding.ambientOverlay.alpha = 1f
        playTvVideo(channel, nextIndex)
        binding.ambientOverlay.animate().alpha(0f).setDuration(TV_TRANSITION_FADE_MS)
            .withEndAction { binding.ambientOverlay.visibility = View.GONE }.start()
    }

    private fun toggleTvControls() {
        if (binding.tvModeControls.visibility == View.VISIBLE) {
            tvControlsHandler.removeCallbacks(hideTvControls)
            hideTvControls.run()
        } else {
            showTvControls()
        }
    }

    private fun showTvControls() {
        tvControlsHandler.removeCallbacks(hideTvControls)
        binding.tvModeControls.animate().cancel()
        binding.tvModeControls.visibility = View.VISIBLE
        binding.tvModeControls.bringToFront()
        binding.tvModeControls.alpha = 1f
        tvControlsHandler.postDelayed(hideTvControls, TV_CONTROLS_HIDE_DELAY_MS)
    }

    private fun saveTvPosition() {
        val channel = tvChannels.getOrNull(tvChannelIndex) ?: return
        val uri = videoUri ?: return
        val videoIndex = channel.videos.indexOfFirst { it.uri == uri }
        if (videoIndex >= 0) {
            tvChannelRepository.setCurrentIndex(channel.id, videoIndex)
        }
    }

    private fun initializePlayer() {
        val uri = intent.data ?: return
        videoUri = uri
        val settings = VideoEffectSettings.deserialize(intent.getStringExtra(EXTRA_SETTINGS))
            ?: VideoEffectSettings.DEFAULT
        player = createPlayer(uri, settings, autoAdvanceOnEnd = false)
        progressHandler.postDelayed(progressSaver, PROGRESS_SAVE_INTERVAL_MS)
    }

    /** Shared by normal playback and TV mode: builds the player, wires the shader
     *  effects + compare button, resume-from-progress, ambient dimming (skipped in
     *  TV mode, which has no pause), and error handling. */
    private fun createPlayer(uri: Uri, settings: VideoEffectSettings, autoAdvanceOnEnd: Boolean): ExoPlayer {
        val exoPlayer = ExoPlayer.Builder(this).build()
        binding.playerView.player = exoPlayer
        binding.playerView.resizeMode = settings.aspectMode

        val hasEffects = settings.preset != ShaderPreset.NONE || settings.curvatureEnabled
        if (hasEffects) {
            val activeEffects = mutableListOf<Effect>()
            if (settings.downscale != DownscaleTarget.NATIVE) {
                // Reference is the physical screen height, so the shaded buffer's final
                // upscale back to the display is always a clean integer multiple.
                val referenceHeight = resources.displayMetrics.heightPixels
                activeEffects.add(DownscaleGlEffect(settings.downscale.targetHeight, referenceHeight))
            }
            activeEffects.add(CrtGlEffect(settings.preset, settings.curvatureEnabled))
            effects = activeEffects
            exoPlayer.setVideoEffects(activeEffects)

            if (!isTvMode) {
                // Lives inside retro_player_control_view.xml's exo_basic_controls, so it fades
                // in/out together with the rest of the transport controls automatically --
                // no manual visibility syncing needed.
                val compareButton = binding.playerView.findViewById<TextView>(R.id.compareButton)
                var showingRaw = false
                compareButton.setOnClickListener {
                    showingRaw = !showingRaw
                    exoPlayer.setVideoEffects(if (showingRaw) emptyList() else activeEffects)
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
        }

        val savedProgress = progressRepository.getProgress(uri.toString())
        var hasResumed = false

        exoPlayer.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY && savedProgress != null && !hasResumed) {
                    hasResumed = true
                    exoPlayer.seekTo(savedProgress.positionMs)
                }
                if (playbackState == Player.STATE_ENDED && autoAdvanceOnEnd) {
                    advanceWithinChannel()
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isTvMode) return
                if (isPlaying) {
                    ambientHandler.removeCallbacks(showAmbient)
                    binding.ambientOverlay.animate().cancel()
                    binding.ambientOverlay.alpha = 0f
                    binding.ambientOverlay.visibility = View.GONE
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
                if (autoAdvanceOnEnd) {
                    advanceWithinChannel()
                }
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
        return exoPlayer
    }

    private fun saveProgress() {
        // TV mode has no Continue Watching entry point (no scrub/resume UI at all),
        // so writing progress here would just quietly seed the rail with whatever
        // channel happened to be playing -- not something the user chose to watch.
        if (isTvMode) return
        val uri = videoUri ?: return
        val exoPlayer = player ?: return
        if (exoPlayer.duration <= 0) return
        progressRepository.saveProgress(uri.toString(), exoPlayer.currentPosition, exoPlayer.duration)
    }

    private fun releasePlayer() {
        progressHandler.removeCallbacks(progressSaver)
        ambientHandler.removeCallbacks(showAmbient)
        tvControlsHandler.removeCallbacksAndMessages(null)
        tvStaticHandler.removeCallbacksAndMessages(null)
        binding.tvStaticView.stop()
        player?.release()
        player = null
    }
}
