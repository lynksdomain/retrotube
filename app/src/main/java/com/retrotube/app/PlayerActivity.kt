package com.retrotube.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.C
import androidx.media3.common.Effect
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.retrotube.app.databinding.ActivityPlayerBinding
import com.retrotube.app.metadata.VideoMetadataRepository
import com.retrotube.app.network.NetworkShareRepository
import com.retrotube.app.progress.PlaybackProgressRepository
import com.retrotube.app.settings.SettingsRepository
import com.retrotube.app.settings.VideoEffectSettings
import com.retrotube.app.shader.CrtGlEffect
import com.retrotube.app.shader.DownscaleGlEffect
import com.retrotube.app.shader.DownscaleTarget
import com.retrotube.app.shader.ShaderPreset
import com.retrotube.app.tv.TvChannel
import com.retrotube.app.tv.TvChannelConfigRepository
import com.retrotube.app.tv.TvChannelDefinition
import com.retrotube.app.tv.TvChannelRepository
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@UnstableApi
class PlayerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_SETTINGS = "extra_settings"
        const val EXTRA_TV_MODE = "extra_tv_mode"
        const val EXTRA_QUEUE_URIS = "extra_queue_uris"
        const val EXTRA_QUEUE_INDEX = "extra_queue_index"
        private const val PROGRESS_SAVE_INTERVAL_MS = 5_000L
        private const val AMBIENT_IDLE_DELAY_MS = 20_000L
        private const val AMBIENT_FADE_MS = 300L
        private const val TV_CONTROLS_HIDE_DELAY_MS = 4_000L
        private const val TV_TRANSITION_FADE_MS = 250L
        private const val TV_STATIC_HOLD_MS = 500L
        private const val TV_STATIC_FADE_OUT_MS = 150L

        /** Tags whichever subtitle got attached to the MediaItem from outside the
         *  container (a sidecar file, or an OpenSubtitles download) -- lets the
         *  sheet find "the external one" to auto-select by default (sidecar) or
         *  right after a restart (a fresh online download), regardless of mime
         *  type or where Media3 happens to place it among the track groups. */
        private const val EXTERNAL_SUBTITLE_LABEL = "retrotube_external_subtitle"
        private const val TV_NETWORK_CHANNEL_TIMEOUT_MS = 12_000L
    }

    private lateinit var binding: ActivityPlayerBinding
    private lateinit var progressRepository: PlaybackProgressRepository
    private var player: ExoPlayer? = null
    private var videoUri: Uri? = null

    /** True between onStop() and the next onStart() -- checked by a delayed
     *  async callback (the sidecar check) before it's allowed to spin up a
     *  brand new player, since isFinishing/isDestroyed alone miss the much
     *  more common case of the user simply backgrounding the app rather than
     *  actually leaving the screen. */
    private var isStopped = false

    /** Set only when the user picks a result from the OpenSubtitles search sheet
     *  this session -- takes priority over an auto-detected sidecar file (see
     *  [resolveSubtitleConfiguration]). Not persisted: reopening the same video
     *  later re-runs sidecar auto-detection from scratch. */
    private var manualSubtitleUri: Uri? = null

    /** Set right before a sync/size-triggered restart (delay/speed can only take
     *  effect on the next player build, see [TimeAdjustingSubtitleParser])
     *  to whichever subtitle track was selected at the time -- restored once
     *  the new player reaches READY, so adjusting sync doesn't silently drop
     *  an explicitly-picked embedded track back to nothing selected. Left null
     *  for every other restart (a fresh video open, a new OpenSubtitles
     *  download), where [autoSelectExternalSubtitleIfPresent] decides instead. */
    private var pendingSubtitleSelectionIndex: Int? = null

    /** An auto-detected sidecar found for [sidecarCheckedForUri] -- populated by
     *  [startPlaybackAfterSidecarCheck], never resolved synchronously on the
     *  main thread. A local (SAF) sidecar check is a fast IPC call, but an SMB
     *  one is a real network round trip: jcifs's own context setup alone
     *  throws NetworkOnMainThreadException. */
    private var discoveredSidecar: com.retrotube.app.subtitle.SidecarSubtitle? = null
    private var sidecarCheckedForUri: Uri? = null
    private var effects: List<Effect> = emptyList()

    /** The list this video was opened from (a folder listing, a season's
     *  episodes, ...), for shoulder-button previous/next in normal playback --
     *  see [playAdjacentInQueue]. Empty when opened from somewhere that has no
     *  such list (e.g. a deep link). */
    private var queueUris: List<Uri> = emptyList()
    private var queueIndex: Int = -1

    private var isTvMode = false
    private lateinit var tvChannelRepository: TvChannelRepository
    private var tvChannels: List<TvChannel> = emptyList()
    private var tvChannelIndex: Int = 0

    /** Two executors mirroring ThumbnailLoader's decode/waiter split: [tvNetworkCrawlExecutor]
     *  runs the actual SMB crawl, [tvNetworkWaitExecutor] blocks on it with a timeout on a
     *  separate thread so a slow or dead share can't hang past [TV_NETWORK_CHANNEL_TIMEOUT_MS]
     *  without ever touching the main thread. */
    private val tvNetworkCrawlExecutor = Executors.newSingleThreadExecutor()
    private val tvNetworkWaitExecutor = Executors.newSingleThreadExecutor()

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
        binding.ambientOverlay.animate().alpha(1f).setDuration(AMBIENT_FADE_MS).start()
    }

    /** Cancels the dim (or the pending timer to show it) and, if still paused,
     *  re-arms the idle timer from scratch -- called both when playback resumes
     *  and whenever the user taps the screen while paused. Tapping only ever
     *  toggled PlayerView's own transport controls before; nothing told the
     *  ambient overlay a tap had happened at all, so it stayed dimmed until the
     *  video was actually pressed play again. */
    private fun wakeFromAmbient(exoPlayer: ExoPlayer) {
        ambientHandler.removeCallbacks(showAmbient)
        binding.ambientOverlay.animate().cancel()
        binding.ambientOverlay.alpha = 0f
        binding.ambientOverlay.visibility = View.GONE
        if (!exoPlayer.isPlaying) {
            ambientHandler.postDelayed(showAmbient, AMBIENT_IDLE_DELAY_MS)
        }
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
        // Locked to landscape only here -- both playback modes (normal + TV) live
        // in this one Activity, and nowhere else in the app should rotate/lock,
        // so this is scoped to onCreate/onDestroy rather than a manifest-wide setting.
        requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        binding = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        hideSystemBars()
        progressRepository = PlaybackProgressRepository(this)
        isTvMode = intent.getBooleanExtra(EXTRA_TV_MODE, false)
        if (isTvMode) {
            setUpTvMode()
        } else {
            queueUris = intent.getStringArrayListExtra(EXTRA_QUEUE_URIS)?.map { Uri.parse(it) }.orEmpty()
            queueIndex = intent.getIntExtra(EXTRA_QUEUE_INDEX, -1)
        }
    }

    /** True fullscreen: the system nav bar (gesture line included) and status bar
     *  both stay hidden during playback rather than sitting over the video, and
     *  swiping only reveals them momentarily rather than pinning them back on. */
    private fun hideSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, binding.root).let { controller ->
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    /** Gamepad shoulder buttons (e.g. a Retroid Nova's L1/R1) -- channel down/up
     *  in TV Mode, previous/next video in whatever list this one was opened from
     *  otherwise. onKeyUp (not down) so a held button doesn't repeat-fire. */
    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_BUTTON_R1 -> {
                if (isTvMode) changeChannel(1) else playAdjacentInQueue(1)
                return true
            }
            KeyEvent.KEYCODE_BUTTON_L1 -> {
                if (isTvMode) changeChannel(-1) else playAdjacentInQueue(-1)
                return true
            }
        }
        return super.onKeyUp(keyCode, event)
    }

    override fun onStart() {
        super.onStart()
        isStopped = false
        if (isTvMode) {
            initializeTvPlayer()
        } else {
            initializePlayer()
        }
    }

    override fun onStop() {
        super.onStop()
        isStopped = true
        saveProgress()
        if (isTvMode) {
            saveTvPosition()
        }
        releasePlayer()
    }

    override fun onDestroy() {
        super.onDestroy()
        requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        tvNetworkCrawlExecutor.shutdownNow()
        tvNetworkWaitExecutor.shutdownNow()
    }

    private fun setUpTvMode() {
        binding.playerView.useController = false
        val configRepository = TvChannelConfigRepository(this)

        tvChannelRepository = TvChannelRepository(
            this,
            progressRepository,
            VideoMetadataRepository(this),
        )

        val definitions = configRepository.getChannels()
        val (networkDefinitions, localDefinitions) = definitions.partition { tvChannelRepository.hasNetworkSource(it) }

        tvChannels = localDefinitions.mapNotNull { tvChannelRepository.resolveChannel(it) }
            .mapIndexed { index, channel -> channel.copy(number = index + 1) }
        if (tvChannels.isEmpty() && networkDefinitions.isEmpty()) {
            Toast.makeText(this, getString(R.string.tv_mode_no_content), Toast.LENGTH_LONG).show()
            finish()
            return
        }
        val lastChannelId = tvChannelRepository.getLastChannelId()
        tvChannelIndex = tvChannels.indexOfFirst { it.id == lastChannelId }.takeIf { it >= 0 } ?: 0
        networkDefinitions.forEach { loadNetworkChannelAsync(it) }

        binding.playerView.setOnClickListener { toggleTvControls() }
        binding.channelUpButton.setOnClickListener { changeChannel(1) }
        binding.channelDownButton.setOnClickListener { changeChannel(-1) }
        binding.exitTvModeButton.setOnClickListener { finish() }
        updateTvCurvatureButton()
        binding.tvCurvatureToggleButton.setOnClickListener { toggleTvCurvature() }
    }

    /** The one TV Mode setting the remote overlay exposes live -- everything else
     *  about TV Mode's look ([VideoEffectSettings.TV_MODE]) is fixed so every
     *  channel feels consistent, but curvature is cheap and purely cosmetic, so
     *  it's persisted independently ([SettingsRepository.getTvCurvatureEnabled])
     *  and can be flipped without interrupting the video already playing. */
    private fun toggleTvCurvature() {
        val settingsRepository = SettingsRepository(this)
        val enabled = !settingsRepository.getTvCurvatureEnabled()
        settingsRepository.setTvCurvatureEnabled(enabled)
        updateTvCurvatureButton()
        val currentPlayer = player ?: return
        val rebuiltEffects = effects.map { effect ->
            if (effect is CrtGlEffect) CrtGlEffect(ShaderPreset.ZFAST_CRT, enabled) else effect
        }
        effects = rebuiltEffects
        currentPlayer.setVideoEffects(rebuiltEffects)
    }

    private fun updateTvCurvatureButton() {
        val enabled = SettingsRepository(this).getTvCurvatureEnabled()
        binding.tvCurvatureToggleButton.setColorFilter(
            ContextCompat.getColor(this, if (enabled) R.color.retro_magenta else R.color.retro_teal),
        )
    }

    /** Resolves one channel definition with a network source off the main thread,
     *  bounded by [TV_NETWORK_CHANNEL_TIMEOUT_MS] -- a slow or unreachable share
     *  only costs this one channel rather than blocking TV Mode from launching.
     *  Appends the channel once (if ever) it comes back; nothing happens if it
     *  resolves to no videos or the crawl times out. */
    private fun loadNetworkChannelAsync(definition: TvChannelDefinition) {
        val repository = tvChannelRepository
        val future = tvNetworkCrawlExecutor.submit(Callable { repository.resolveChannel(definition) })
        tvNetworkWaitExecutor.execute {
            val channel = try {
                future.get(TV_NETWORK_CHANNEL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            } catch (e: Exception) {
                Log.w("PlayerActivity", "TV Mode channel '${definition.id}' unavailable", e)
                null
            }
            if (channel != null) {
                runOnUiThread { appendNetworkChannel(channel) }
            }
        }
    }

    private fun appendNetworkChannel(channel: TvChannel) {
        if (isFinishing || isDestroyed || !isTvMode) return
        tvChannels = tvChannels + channel.copy(number = tvChannels.size + 1)
    }

    private fun initializeTvPlayer() {
        val channel = tvChannels[tvChannelIndex]
        val videoIndex = tvChannelRepository.getCurrentIndex(channel)
        playTvVideo(channel, videoIndex)
        showTvControls()
    }

    private fun playTvVideo(channel: TvChannel, videoIndex: Int) {
        releasePlayer()
        val video = channel.videos[videoIndex]
        videoUri = video.uri
        tvChannelRepository.setCurrentIndex(channel.id, videoIndex)
        tvChannelRepository.setLastChannelId(channel.id)

        // Only nonzero if we're returning to the same video within this channel --
        // setCurrentIndex above already reset it to 0 if this is a new video.
        val resumePositionMs = tvChannelRepository.getSavedPositionMs(channel.id)
        val tvSettings = VideoEffectSettings.TV_MODE.copy(
            curvatureEnabled = SettingsRepository(this).getTvCurvatureEnabled(),
        )
        val exoPlayer = createPlayer(video.uri, tvSettings, autoAdvanceOnEnd = true, resumePositionMs)
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
        // Must happen before tvChannelIndex moves -- saveProgress() attributes the
        // still-playing old video's position to whatever channel tvChannelIndex
        // currently points at.
        saveProgress()
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
        saveProgress()
    }

    private fun initializePlayer() {
        val uri = intent.data ?: return
        videoUri = uri
        val settings = VideoEffectSettings.deserialize(intent.getStringExtra(EXTRA_SETTINGS))
            ?: VideoEffectSettings.DEFAULT
        val resumePositionMs = progressRepository.getProgress(uri.toString())?.positionMs
        startPlaybackAfterSidecarCheck(uri, settings, autoAdvanceOnEnd = false, resumePositionMs)
    }

    /** Shoulder-button previous/next within [queueUris] -- a no-op past either end
     *  rather than wrapping, since this is "skip to the next file," not a channel. */
    private fun playAdjacentInQueue(direction: Int) {
        val newIndex = queueIndex + direction
        if (newIndex !in queueUris.indices) return
        saveProgress()
        releasePlayer()

        val uri = queueUris[newIndex]
        queueIndex = newIndex
        videoUri = uri
        // A different video entirely -- neither a subtitle picked for the
        // previous file nor its sidecar check result should carry over.
        manualSubtitleUri = null
        discoveredSidecar = null
        sidecarCheckedForUri = null
        val settings = SettingsRepository(this).effectiveSettings(uri.toString())
        val resumePositionMs = progressRepository.getProgress(uri.toString())?.positionMs
        startPlaybackAfterSidecarCheck(uri, settings, autoAdvanceOnEnd = false, resumePositionMs)
    }

    /** Shared by normal playback and TV mode: builds the player, wires the shader
     *  effects + compare button, resume-from-progress, ambient dimming (skipped in
     *  TV mode, which has no pause), and error handling. [resumePositionMs] comes
     *  from Continue Watching for normal playback, or from the TV channel's own
     *  saved position for TV mode -- the two never read each other's store. */
    private fun createPlayer(uri: Uri, settings: VideoEffectSettings, autoAdvanceOnEnd: Boolean, resumePositionMs: Long?): ExoPlayer {
        // The custom subtitle parser factory is what adds VobSub support on top of
        // everything Media3 already handles natively (including PGS -- confirmed
        // against the actual media3-extractor jar, not assumed), wrapped again with
        // this video's own delay/speed sync settings.
        val subtitleDelaySeconds = com.retrotube.app.metadata.VideoMetadataRepository(this).getSubtitleDelaySeconds(uri.toString())
        val subtitleSpeed = com.retrotube.app.metadata.VideoMetadataRepository(this).getSubtitleSpeedMultiplier(uri.toString())
        val subtitleParserFactory = com.retrotube.app.subtitle.AdjustableSubtitleParserFactory(
            com.retrotube.app.subtitle.vobsub.VobSubParserFactory(),
            subtitleDelaySeconds,
            subtitleSpeed,
        )
        // A single dispatching DataSource.Factory (smb:// vs. everything else) lets
        // this one DefaultMediaSourceFactory -- and its subtitle-config merging --
        // handle a local video and an SMB video identically. See DispatchingDataSource's
        // doc for why a hand-built ProgressiveMediaSource for the SMB case (the old
        // approach) never actually surfaced an attached sidecar as a selectable track.
        val mediaSourceFactory = androidx.media3.exoplayer.source.DefaultMediaSourceFactory(
            com.retrotube.app.network.DispatchingDataSource.Factory(this, NetworkShareRepository(this)),
        ).setSubtitleParserFactory(subtitleParserFactory)
        val exoPlayer = ExoPlayer.Builder(this).setMediaSourceFactory(mediaSourceFactory).build()
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

        var hasResumed = false
        // Skipped entirely in TV mode -- buildMediaItem never attaches a subtitle
        // configuration there, so there's nothing to auto-select.
        var hasAutoSelectedExternalSubtitle = isTvMode

        exoPlayer.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY && resumePositionMs != null && resumePositionMs > 0 && !hasResumed) {
                    hasResumed = true
                    exoPlayer.seekTo(resumePositionMs)
                }
                if (playbackState == Player.STATE_READY && !hasAutoSelectedExternalSubtitle) {
                    hasAutoSelectedExternalSubtitle = true
                    val pendingIndex = pendingSubtitleSelectionIndex
                    pendingSubtitleSelectionIndex = null
                    val restored = pendingIndex?.let { currentSubtitleTrackOptions(exoPlayer).getOrNull(it) }
                    if (restored != null) {
                        selectSubtitleTrack(restored, exoPlayer)
                    } else {
                        autoSelectExternalSubtitleIfPresent(exoPlayer)
                    }
                }
                if (playbackState == Player.STATE_ENDED && autoAdvanceOnEnd) {
                    advanceWithinChannel()
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isTvMode) return
                if (isPlaying) {
                    wakeFromAmbient(exoPlayer)
                } else {
                    ambientHandler.postDelayed(showAmbient, AMBIENT_IDLE_DELAY_MS)
                }
            }

            override fun onRenderedFirstFrame() {
                if (!isTvMode) binding.loadingSpinner.visibility = View.GONE
            }

            override fun onPlayerError(error: PlaybackException) {
                if (!isTvMode) binding.loadingSpinner.visibility = View.GONE
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

        exoPlayer.setMediaItem(buildMediaItem(uri))
        exoPlayer.prepare()
        exoPlayer.playWhenReady = true

        if (!isTvMode) {
            // First-class visible prev/next, not just the gamepad shoulder-button path
            // (KEYCODE_BUTTON_L1/R1 above still calls the same playAdjacentInQueue).
            updateQueueNavigationButtons()
            binding.playerView.subtitleView?.setFractionalTextSize(
                com.retrotube.app.settings.SettingsRepository(this).getSubtitleSizeFraction(),
            )
            val subtitlesButton = binding.playerView.findViewById<TextView>(R.id.subtitlesButton)
            subtitlesButton.setOnClickListener { showSubtitlesSheet(uri) }

            // useController's own tap-to-reveal fires this on every tap, playing or
            // paused -- onIsPlayingChanged alone only ever saw a tap indirectly, via
            // pressing Play, so a tap while already paused never woke the dim overlay.
            binding.playerView.setControllerVisibilityListener(
                androidx.media3.ui.PlayerView.ControllerVisibilityListener { visibility ->
                    if (visibility == View.VISIBLE) wakeFromAmbient(exoPlayer)
                },
            )
        }
        return exoPlayer
    }

    /** Sidecar (§6.1's highest-priority auto tier) if one's found next to the file,
     *  otherwise whatever the user picked from OpenSubtitles this session
     *  ([manualSubtitleUri]) -- embedded text tracks (including PGS/VobSub,
     *  decoded via [com.retrotube.app.subtitle.vobsub.VobSubParserFactory]) need
     *  no extra wiring here at all, Media3 demuxes them and they show up as
     *  ordinary track groups in [ExoPlayer.getCurrentTracks] once prepared --
     *  see [currentSubtitleTrackOptions], which is what actually lists every
     *  selectable track (embedded or external) in the custom subtitles sheet. */
    private fun buildMediaItem(uri: Uri): MediaItem {
        val builder = MediaItem.Builder().setUri(uri)
        if (!isTvMode) {
            resolveSubtitleConfiguration(uri)?.let { builder.setSubtitleConfigurations(listOf(it)) }
        }
        return builder.build()
    }

    private fun resolveSubtitleConfiguration(uri: Uri): MediaItem.SubtitleConfiguration? {
        manualSubtitleUri?.let { override ->
            return MediaItem.SubtitleConfiguration.Builder(override)
                .setMimeType(MimeTypes.APPLICATION_SUBRIP)
                .setLanguage("und")
                .setLabel(EXTERNAL_SUBTITLE_LABEL)
                .build()
        }
        // discoveredSidecar is resolved *before* the player is ever created for
        // this uri (see startPlaybackAfterSidecarCheck) -- never here, and never
        // by restarting mid-playback once found. An earlier version of this did
        // check+restart reactively right after playback began, which briefly
        // desynced audio/video on every load, and could leak an un-released,
        // still-playing ExoPlayer if the user had already left the screen by
        // the time the background check completed and the restart fired anyway.
        val sidecar = discoveredSidecar.takeIf { sidecarCheckedForUri == uri } ?: return null
        return MediaItem.SubtitleConfiguration.Builder(sidecar.uri)
            .setMimeType(sidecar.mimeType)
            .setLanguage("und")
            .setLabel(EXTERNAL_SUBTITLE_LABEL)
            .build()
    }

    /** Resolves whether a sidecar exists for [uri] -- off the main thread,
     *  since an SMB check is real network I/O -- *before* the player is ever
     *  built, so it can be attached on the very first prepare() instead of
     *  restarting playback afterward to bolt it on. Skips the check (and
     *  plays immediately) once [uri] has already been resolved this session,
     *  or when the user already has an explicit OpenSubtitles pick in effect. */
    private fun startPlaybackAfterSidecarCheck(
        uri: Uri,
        settings: VideoEffectSettings,
        autoAdvanceOnEnd: Boolean,
        resumePositionMs: Long?,
    ) {
        if (!isTvMode) binding.loadingSpinner.visibility = View.VISIBLE
        if (isTvMode || manualSubtitleUri != null || sidecarCheckedForUri == uri) {
            player = createPlayer(uri, settings, autoAdvanceOnEnd, resumePositionMs)
            progressHandler.postDelayed(progressSaver, PROGRESS_SAVE_INTERVAL_MS)
            return
        }
        val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
        val handler = Handler(Looper.getMainLooper())
        executor.execute {
            val sidecar = com.retrotube.app.subtitle.SidecarLoader.findSidecar(this, uri)
            handler.post {
                sidecarCheckedForUri = uri
                discoveredSidecar = sidecar
                // The user may have already backed out (this screen is stopping/
                // gone) or skipped to a different video in the queue by the time
                // this network round trip comes back -- either way, starting a
                // brand new player now would just leak one nothing ever releases.
                if (isStopped || isFinishing || isDestroyed || videoUri != uri) return@post
                player = createPlayer(uri, settings, autoAdvanceOnEnd, resumePositionMs)
                progressHandler.postDelayed(progressSaver, PROGRESS_SAVE_INTERVAL_MS)
            }
        }
    }

    /** One selectable row in the sheet: any real text track the player already
     *  knows about -- both embedded (demuxed from the container) and an
     *  externally-attached one (sidecar file, or a previously-downloaded
     *  OpenSubtitles pick) show up here the same way, since Media3 treats an
     *  attached [MediaItem.SubtitleConfiguration] as just another track group
     *  once the player's prepared. Picking one is a live [Player] parameter
     *  change -- no restart needed, unlike a *new* OpenSubtitles download. */
    private data class SubtitleTrackOption(val group: androidx.media3.common.Tracks.Group, val trackIndex: Int, val label: String)

    private fun currentSubtitleTrackOptions(target: ExoPlayer? = player): List<SubtitleTrackOption> {
        val currentPlayer = target ?: return emptyList()
        val options = mutableListOf<SubtitleTrackOption>()
        for (group in currentPlayer.currentTracks.groups) {
            if (group.type != C.TRACK_TYPE_TEXT) continue
            for (i in 0 until group.length) {
                if (!group.isTrackSupported(i)) continue
                options += SubtitleTrackOption(group, i, labelForSubtitleTrack(group.getTrackFormat(i), options.size))
            }
        }
        return options
    }

    private fun labelForSubtitleTrack(format: Format, index: Int): String {
        if (format.label == EXTERNAL_SUBTITLE_LABEL) {
            return if (manualSubtitleUri != null) getString(R.string.subtitles_downloaded_found) else getString(R.string.subtitles_sidecar_found)
        }
        val languageLabel = format.language?.takeIf { it != "und" }?.uppercase()
        val formatLabel = when (format.sampleMimeType) {
            MimeTypes.APPLICATION_PGS -> "PGS"
            MimeTypes.APPLICATION_VOBSUB -> "VobSub"
            MimeTypes.TEXT_SSA -> "ASS/SSA"
            MimeTypes.APPLICATION_SUBRIP -> "SRT"
            MimeTypes.TEXT_VTT -> "VTT"
            MimeTypes.APPLICATION_TTML -> "TTML"
            else -> null
        }
        val combined = listOfNotNull(languageLabel, formatLabel).joinToString(" · ")
        return combined.ifEmpty { getString(R.string.subtitles_track_generic, index + 1) }
    }

    /** Where the currently-selected text track sits in [currentSubtitleTrackOptions]'s
     *  flat list, if any -- captured right before a sync/size-triggered restart so
     *  the same track (embedded or external) can be re-selected on the new player
     *  instance instead of silently landing on "nothing selected." */
    private fun currentlySelectedSubtitleOptionIndex(): Int? =
        currentSubtitleTrackOptions().indexOfFirst { it.group.isTrackSelected(it.trackIndex) }.takeIf { it >= 0 }

    /** Live parameter change -- hides subtitles immediately with no restart,
     *  regardless of whether the currently-selected track was embedded, a
     *  sidecar, or a downloaded pick. */
    private fun turnOffSubtitles() {
        player?.let { p ->
            p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                .build()
        }
    }

    /** Live parameter change too -- every option in the sheet (embedded, sidecar,
     *  or an already-downloaded online pick) is already a track the prepared
     *  player knows about, so switching between them never needs a restart. */
    private fun selectSubtitleTrack(option: SubtitleTrackOption, target: ExoPlayer? = player) {
        target?.let { p ->
            p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                .setOverrideForType(TrackSelectionOverride(option.group.mediaTrackGroup, option.trackIndex))
                .build()
        }
    }

    /** Selects whichever track carries [EXTERNAL_SUBTITLE_LABEL], if any -- called
     *  once per player instance, right after it reaches STATE_READY. Covers both
     *  a sidecar auto-attaching on a fresh video open (the "highest-priority auto
     *  tier") and a just-downloaded OpenSubtitles pick showing up immediately
     *  after the restart it required, instead of silently sitting unselected. */
    private fun autoSelectExternalSubtitleIfPresent(exoPlayer: ExoPlayer) {
        for (group in exoPlayer.currentTracks.groups) {
            if (group.type != C.TRACK_TYPE_TEXT) continue
            for (i in 0 until group.length) {
                if (group.getTrackFormat(i).label == EXTERNAL_SUBTITLE_LABEL) {
                    exoPlayer.trackSelectionParameters = exoPlayer.trackSelectionParameters.buildUpon()
                        .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                        .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, i))
                        .build()
                    return
                }
            }
        }
    }

    private fun showSubtitlesSheet(uri: Uri) {
        val trackOptions = currentSubtitleTrackOptions()
        val labels = mutableListOf(getString(R.string.subtitles_none))
        labels += trackOptions.map { it.label }
        labels += getString(R.string.subtitles_search_online)
        labels += getString(R.string.subtitles_sync_and_size)

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.subtitles_sheet_title)
            .setItems(labels.toTypedArray()) { _, index ->
                when {
                    index == 0 -> turnOffSubtitles()
                    index - 1 < trackOptions.size -> selectSubtitleTrack(trackOptions[index - 1])
                    index == trackOptions.size + 1 -> {
                        if (!com.retrotube.app.subtitle.OpenSubtitlesClient().isConfigured()) {
                            Toast.makeText(this, R.string.opensubtitles_not_configured, Toast.LENGTH_LONG).show()
                        } else {
                            searchOpenSubtitles(uri)
                        }
                    }
                    else -> showSyncAndSizeDialog(uri)
                }
            }
            .show()
    }

    /** Delay/speed only take effect on the next player build (see
     *  [com.retrotube.app.subtitle.TimeAdjustingSubtitleParser]'s doc on why --
     *  batch-parsed text subtitles are timed once up front), so this restarts
     *  playback the same way picking a different subtitle source does. Size is
     *  applied live to the existing SubtitleView, no restart needed for it. */
    private fun showSyncAndSizeDialog(uri: Uri) {
        val videoMetadataRepository = com.retrotube.app.metadata.VideoMetadataRepository(this)
        val settingsRepository = com.retrotube.app.settings.SettingsRepository(this)
        val uriString = uri.toString()

        val layout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(48, 24, 48, 0)
        }

        // Delay/speed only mean anything for a batch-parsed text track (SRT/VTT/SSA/ASS)
        // -- see TimeAdjustingSubtitleParser's doc. If a bitmap track (PGS/VobSub) is
        // the one currently selected, hide them instead of offering controls that would
        // silently do nothing.
        val bitmapTrackSelected = selectedSubtitleIsBitmapFormat()
        var delayField: android.widget.EditText? = null
        var speedField: android.widget.EditText? = null
        if (bitmapTrackSelected) {
            layout.addView(
                android.widget.TextView(this).apply {
                    text = getString(R.string.subtitles_sync_unavailable_bitmap)
                    setPadding(0, 0, 0, 24)
                },
            )
        } else {
            delayField = android.widget.EditText(this).apply {
                hint = getString(R.string.subtitles_delay_label)
                inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_SIGNED or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
                setText(videoMetadataRepository.getSubtitleDelaySeconds(uriString).toString())
            }
            speedField = android.widget.EditText(this).apply {
                hint = getString(R.string.subtitles_speed_label)
                inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
                setText(videoMetadataRepository.getSubtitleSpeedMultiplier(uriString).toString())
            }
            layout.addView(delayField)
            layout.addView(speedField)
        }

        val sizeField = android.widget.EditText(this).apply {
            hint = getString(R.string.subtitles_size_label)
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
            setText((settingsRepository.getSubtitleSizeFraction() * 100).toString())
        }
        layout.addView(sizeField)

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.subtitles_sync_and_size)
            .setView(layout)
            .setPositiveButton(R.string.ok) { _, _ ->
                val sizePercent = sizeField.text?.toString()?.toFloatOrNull()?.takeIf { it > 0f } ?: (settingsRepository.getSubtitleSizeFraction() * 100)
                settingsRepository.setSubtitleSizeFraction(sizePercent / 100f)
                binding.playerView.subtitleView?.setFractionalTextSize(sizePercent / 100f)
                if (!bitmapTrackSelected) {
                    val delay = delayField?.text?.toString()?.toFloatOrNull() ?: 0f
                    val speed = speedField?.text?.toString()?.toFloatOrNull()?.takeIf { it > 0f } ?: 1f
                    videoMetadataRepository.setSubtitleDelaySeconds(uriString, delay)
                    videoMetadataRepository.setSubtitleSpeedMultiplier(uriString, speed)
                    // Only restart (to re-parse with the new delay/speed) if a
                    // track is actually selected right now -- restarting while
                    // subtitles are off would otherwise fall through to the
                    // fresh-player default and silently re-enable a sidecar.
                    val selectedIndex = currentlySelectedSubtitleOptionIndex()
                    if (selectedIndex != null) {
                        pendingSubtitleSelectionIndex = selectedIndex
                        restartCurrentVideo(uri)
                    }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** True if the currently-selected text track (if any) is a bitmap format
     *  (PGS/VobSub) rather than a batch-parsed text one. */
    private fun selectedSubtitleIsBitmapFormat(): Boolean {
        val currentPlayer = player ?: return false
        for (group in currentPlayer.currentTracks.groups) {
            if (group.type != androidx.media3.common.C.TRACK_TYPE_TEXT) continue
            for (i in 0 until group.length) {
                if (group.isTrackSelected(i)) {
                    val mime = group.getTrackFormat(i).sampleMimeType
                    return mime == androidx.media3.common.MimeTypes.APPLICATION_PGS ||
                        mime == androidx.media3.common.MimeTypes.APPLICATION_VOBSUB
                }
            }
        }
        return false
    }

    private fun searchOpenSubtitles(uri: Uri) {
        val client = com.retrotube.app.subtitle.OpenSubtitlesClient()
        val query = openSubtitlesQueryFor(uri)
        val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
        val handler = Handler(Looper.getMainLooper())
        Toast.makeText(this, R.string.opensubtitles_searching, Toast.LENGTH_SHORT).show()
        executor.execute {
            val results = runCatching { client.search(query) }.getOrDefault(emptyList())
            handler.post {
                if (results.isEmpty()) {
                    Toast.makeText(this, R.string.opensubtitles_no_results, Toast.LENGTH_LONG).show()
                    return@post
                }
                val labels = results.map {
                    "${it.releaseName}\n${it.language.uppercase()} · ${it.downloadCount} downloads"
                }.toTypedArray()
                androidx.appcompat.app.AlertDialog.Builder(this)
                    .setTitle(R.string.subtitles_search_online)
                    .setItems(labels) { _, index -> downloadOpenSubtitle(uri, client, results[index]) }
                    .show()
            }
        }
    }

    private fun downloadOpenSubtitle(uri: Uri, client: com.retrotube.app.subtitle.OpenSubtitlesClient, result: com.retrotube.app.subtitle.OpenSubtitlesResult) {
        val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
        val handler = Handler(Looper.getMainLooper())
        executor.execute {
            val text = runCatching { client.download(result.fileId) }.getOrNull()
            if (text == null) {
                handler.post { Toast.makeText(this, R.string.opensubtitles_download_failed, Toast.LENGTH_LONG).show() }
                return@execute
            }
            val file = java.io.File(cacheDir, "subtitle_${result.fileId}.srt")
            runCatching { file.writeText(text) }
            handler.post {
                manualSubtitleUri = androidx.core.content.FileProvider.getUriForFile(
                    this, "$packageName.fileprovider", file,
                )
                restartCurrentVideo(uri)
            }
        }
    }

    private fun videoDisplayNameFor(uri: Uri): String =
        com.retrotube.app.metadata.VideoMetadataRepository(this).getCustomTitle(uri.toString())
            ?: uri.lastPathSegment?.substringBeforeLast('.') ?: uri.toString()

    /** A query fit for an OpenSubtitles search -- [videoDisplayNameFor] returns the
     *  raw filename (release-group tags, resolution, codec, etc. and all) when the
     *  video was never TMDB-matched, and searching OpenSubtitles with that noise
     *  is exactly what surfaces unrelated results. Runs the same filename-parsing
     *  chain the metadata matcher uses to recover just the title (+ year, if the
     *  parser found one), the same clean query a real search would use. */
    private fun openSubtitlesQueryFor(uri: Uri): String {
        val metadata = com.retrotube.app.metadata.VideoMetadataRepository(this)
        val customTitle = metadata.getCustomTitle(uri.toString())
        if (customTitle != null) {
            val year = metadata.getYear(uri.toString())
            return if (year != null) "$customTitle $year" else customTitle
        }
        val rawFilename = uri.lastPathSegment?.substringAfterLast('/') ?: uri.toString()
        return com.retrotube.app.metadata.tmdb.FilenameParser.parse(rawFilename, forMovie = true).title
    }

    /** Re-attaches the current video from wherever it last was so a subtitle pick
     *  takes effect without losing playback position -- same resume mechanism as
     *  a normal cold start, just triggered mid-session instead of from onStart. */
    private fun restartCurrentVideo(uri: Uri) {
        if (!isTvMode) binding.loadingSpinner.visibility = View.VISIBLE
        val positionMs = player?.currentPosition ?: 0L
        val settings = com.retrotube.app.settings.SettingsRepository(this).effectiveSettings(uri.toString())
        player = createPlayer(uri, settings, autoAdvanceOnEnd = false, positionMs)
    }

    /** Wires exo_prev/exo_next to [playAdjacentInQueue] and disables (not hides) either
     *  one past the end of [queueUris] -- re-run on every createPlayer call since the
     *  bounds change as [queueIndex] moves. */
    private fun updateQueueNavigationButtons() {
        val prevButton = binding.playerView.findViewById<android.widget.ImageButton>(androidx.media3.ui.R.id.exo_prev)
        val nextButton = binding.playerView.findViewById<android.widget.ImageButton>(androidx.media3.ui.R.id.exo_next)
        prevButton.isEnabled = queueIndex - 1 in queueUris.indices
        nextButton.isEnabled = queueIndex + 1 in queueUris.indices
        prevButton.alpha = if (prevButton.isEnabled) 1f else 0.35f
        nextButton.alpha = if (nextButton.isEnabled) 1f else 0.35f
        prevButton.setOnClickListener { playAdjacentInQueue(-1) }
        nextButton.setOnClickListener { playAdjacentInQueue(1) }
    }

    /** In TV mode this saves into the current channel's own position store (see
     *  [TvChannelRepository]) instead of Continue Watching -- TV mode has no
     *  resume/scrub UI of its own, so writing into Continue Watching here would
     *  just quietly seed that rail with whatever channel happened to be playing,
     *  not something the user chose to watch. The channel-position store exists
     *  so flipping back to a channel still resumes it instead of restarting. */
    private fun saveProgress() {
        val exoPlayer = player ?: return
        if (exoPlayer.duration <= 0) return
        if (isTvMode) {
            tvChannels.getOrNull(tvChannelIndex)?.let {
                tvChannelRepository.setSavedPositionMs(it.id, exoPlayer.currentPosition)
            }
        } else {
            val uri = videoUri ?: return
            progressRepository.saveProgress(uri.toString(), exoPlayer.currentPosition, exoPlayer.duration)
        }
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
