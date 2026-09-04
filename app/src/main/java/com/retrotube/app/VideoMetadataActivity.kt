package com.retrotube.app

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.SeekBar
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.documentfile.provider.DocumentFile
import com.retrotube.app.databinding.ActivityVideoMetadataBinding
import com.retrotube.app.library.ImageUtils
import com.retrotube.app.library.ThumbnailLoader
import com.retrotube.app.metadata.VideoMetadataRepository
import com.retrotube.app.network.NetworkShareRepository
import com.retrotube.app.network.SmbUri
import java.util.concurrent.Executors

/**
 * Lets a video's display title and poster art be overridden by hand -- a
 * frame grab is rarely the best poster, and a raw filename is rarely the
 * best title. Both overrides are optional; clearing them (or never setting
 * them) falls back to the automatic filename/frame-grab behavior.
 *
 * Frame scrubbing (the seek bar + candidate thumbnails) needs to actually
 * decode the video interactively, which only works for local SAF files right
 * now -- for an SMB video this screen instead offers picking an arbitrary
 * photo as the poster, which needs nothing scheme-specific at all.
 */
class VideoMetadataActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_VIDEO_URI = "video_uri"
        private const val TAG = "VideoMetadataActivity"
        private val CANDIDATE_FRACTIONS = listOf(0.1f, 0.3f, 0.5f, 0.7f)
    }

    private lateinit var binding: ActivityVideoMetadataBinding
    private lateinit var metadataRepository: VideoMetadataRepository
    private lateinit var videoUri: Uri

    private val retrieverExecutor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    /** Kept open for the activity's lifetime so scrubbing doesn't re-open the
     *  source file on every seek -- released in [onDestroy]. */
    private var retriever: MediaMetadataRetriever? = null
    private var durationMs: Long = 0L
    private var selectedBitmap: Bitmap? = null

    private val pickPhoto = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@registerForActivityResult
        val bitmap = ImageUtils.decodeSampledBitmap(this, uri) ?: return@registerForActivityResult
        selectedBitmap = bitmap
        binding.posterPreview.setImageBitmap(bitmap)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uriExtra = intent.getStringExtra(EXTRA_VIDEO_URI)
        if (uriExtra == null) {
            finish()
            return
        }
        videoUri = Uri.parse(uriExtra)
        val smbInfo = SmbUri.parse(videoUri)

        binding = ActivityVideoMetadataBinding.inflate(layoutInflater)
        setContentView(binding.root)

        metadataRepository = VideoMetadataRepository(this)

        val rawName = if (smbInfo != null) {
            smbInfo.second.substringAfterLast('/')
        } else {
            DocumentFile.fromSingleUri(this, videoUri)?.name ?: "Untitled"
        }
        binding.titleInput.setText(metadataRepository.getCustomTitle(uriExtra) ?: cleanupName(rawName))
        binding.tagsInput.setText(metadataRepository.getTags(uriExtra).joinToString(", "))

        val existingThumbnail = metadataRepository.getCustomThumbnail(uriExtra)
        when {
            existingThumbnail != null -> {
                selectedBitmap = existingThumbnail
                binding.posterPreview.setImageBitmap(existingThumbnail)
            }
            smbInfo != null -> {
                val (shareId, relativePath) = smbInfo
                ThumbnailLoader.loadSmb(this, NetworkShareRepository(this), uriExtra, shareId, relativePath, binding.posterPreview)
            }
            else -> ThumbnailLoader.load(this, videoUri, binding.posterPreview)
        }

        binding.backButton.setOnClickListener { finish() }
        binding.saveButton.setOnClickListener { save() }
        binding.resetButton.setOnClickListener { resetToAuto() }
        binding.choosePhotoButton.setOnClickListener { pickPhoto.launch("image/*") }

        if (smbInfo != null) {
            // Interactive scrubbing needs to decode the video live -- only wired up
            // for local SAF files so far (see ThumbnailLoader's own SMB workaround
            // for why that isn't a quick add). Picking a photo still works fine.
            binding.frameScrubSection.visibility = View.GONE
        } else {
            binding.frameSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) = Unit
                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) {
                    if (durationMs <= 0L) return
                    val fraction = (seekBar?.progress ?: 0) / binding.frameSeekBar.max.toFloat()
                    extractFrameAt((fraction * durationMs).toLong())
                }
            })
            loadDurationAndCandidates()
        }
    }

    private fun cleanupName(rawName: String): String =
        rawName
            .substringBeforeLast('.')
            .replace('_', ' ')
            .replace('.', ' ')
            .replace(Regex("\\s+"), " ")
            .trim()

    private fun loadDurationAndCandidates() {
        retrieverExecutor.execute {
            val r = MediaMetadataRetriever()
            try {
                r.setDataSource(this, videoUri)
                retriever = r
                durationMs = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull() ?: 0L

                val candidates = CANDIDATE_FRACTIONS.map { fraction ->
                    runCatching {
                        r.getFrameAtTime((fraction * durationMs * 1000).toLong(), MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    }.getOrNull()
                }
                mainHandler.post { bindCandidates(candidates) }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to read video metadata for $videoUri", e)
            }
        }
    }

    private fun bindCandidates(candidates: List<Bitmap?>) {
        val views = listOf(binding.candidate1, binding.candidate2, binding.candidate3, binding.candidate4)
        views.zip(candidates).forEach { (view, bitmap) ->
            if (bitmap != null) {
                view.setImageBitmap(bitmap)
                view.setOnClickListener {
                    selectedBitmap = bitmap
                    binding.posterPreview.setImageBitmap(bitmap)
                }
            }
        }
    }

    private fun extractFrameAt(timeMs: Long) {
        retrieverExecutor.execute {
            val bitmap = runCatching {
                retriever?.getFrameAtTime(timeMs * 1000, MediaMetadataRetriever.OPTION_CLOSEST)
            }.getOrNull() ?: return@execute
            mainHandler.post {
                selectedBitmap = bitmap
                binding.posterPreview.setImageBitmap(bitmap)
            }
        }
    }

    private fun save() {
        val uriString = videoUri.toString()
        val title = binding.titleInput.text?.toString()?.trim().orEmpty()
        if (title.isNotEmpty()) {
            metadataRepository.setCustomTitle(uriString, title)
        } else {
            metadataRepository.clearCustomTitle(uriString)
        }
        selectedBitmap?.let { metadataRepository.setCustomThumbnail(uriString, it) }
        val tags = binding.tagsInput.text?.toString().orEmpty().split(",")
        metadataRepository.setTags(uriString, tags)
        finish()
    }

    private fun resetToAuto() {
        val uriString = videoUri.toString()
        metadataRepository.clearCustomTitle(uriString)
        metadataRepository.clearCustomThumbnail(uriString)
        metadataRepository.setTags(uriString, emptyList())
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        retrieverExecutor.execute {
            retriever?.release()
        }
    }
}
