package com.retrotube.app

import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.retrotube.app.databinding.ActivityMovieEditInfoBinding
import com.retrotube.app.databinding.ItemTmdbSearchResultBinding
import com.retrotube.app.library.ImageUtils
import com.retrotube.app.metadata.VideoMetadataRepository
import com.retrotube.app.metadata.tmdb.MatchingEngine
import com.retrotube.app.metadata.tmdb.TmdbClient
import com.retrotube.app.metadata.tmdb.TmdbSearchResult
import com.retrotube.app.metadata.tmdb.VideoRef
import java.util.concurrent.Executors
import com.retrotube.app.util.applyTopBarInset

/**
 * TMDB-aware "Edit Info" for one movie file -- distinct from the older,
 * pre-TMDB [VideoMetadataActivity] (manual title/poster override only, no
 * genre/rating/overview, no re-search). This one shows what TMDB matched,
 * lets the user re-search and pick a different result ("Find Match Online"),
 * and still allows a manual title override on top of whatever's matched.
 */
class MovieEditInfoActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_VIDEO_URI = "video_uri"
    }

    private lateinit var binding: ActivityMovieEditInfoBinding
    private lateinit var videoMetadataRepository: VideoMetadataRepository
    private lateinit var videoUri: String
    private val ioExecutor = Executors.newSingleThreadExecutor()
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var selectedPoster: Bitmap? = null

    private val pickPhoto = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@registerForActivityResult
        val bitmap = ImageUtils.decodeSampledBitmap(this, uri) ?: return@registerForActivityResult
        selectedPoster = bitmap
        binding.posterPreview.setImageBitmap(bitmap)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMovieEditInfoBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.root.applyTopBarInset()

        videoUri = intent.getStringExtra(EXTRA_VIDEO_URI) ?: run { finish(); return }
        videoMetadataRepository = VideoMetadataRepository(this)

        binding.cancelButton.setOnClickListener { finish() }
        binding.saveButton.setOnClickListener { save() }
        binding.choosePosterButton.setOnClickListener { pickPhoto.launch("image/*") }
        binding.findMatchRow.setOnClickListener {
            binding.searchSection.visibility = if (binding.searchSection.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }
        binding.searchInput.setOnEditorActionListener { _, _, _ ->
            runSearch(binding.searchInput.text?.toString().orEmpty())
            true
        }
        binding.searchGoButton.setOnClickListener {
            runSearch(binding.searchInput.text?.toString().orEmpty())
        }
        binding.searchResultsList.layoutManager = LinearLayoutManager(this)

        populateFields()
    }

    private fun populateFields() {
        binding.titleInput.setText(videoMetadataRepository.getCustomTitle(videoUri).orEmpty())
        val year = videoMetadataRepository.getYear(videoUri)
        binding.yearText.text = year?.toString().orEmpty()
        val rating = videoMetadataRepository.getRating(videoUri)
        binding.ratingText.text = rating?.let { "★ %.1f".format(it) }.orEmpty()
        binding.genresText.text = videoMetadataRepository.getGenres(videoUri).joinToString(", ")
        binding.overviewText.text = videoMetadataRepository.getOverview(videoUri).orEmpty()
        val poster = videoMetadataRepository.getCustomThumbnail(videoUri)
        if (poster != null) binding.posterPreview.setImageBitmap(poster)
    }

    private fun runSearch(query: String) {
        if (query.isBlank()) return
        binding.searchStatusText.visibility = View.VISIBLE
        binding.searchStatusText.text = getString(R.string.tmdb_searching)
        binding.searchResultsList.adapter = null
        ioExecutor.execute {
            val results = TmdbClient.searchMulti(query).filter { it.mediaType == "movie" }
            mainHandler.post {
                if (results.isEmpty()) {
                    binding.searchStatusText.visibility = View.VISIBLE
                    binding.searchStatusText.text = getString(R.string.tmdb_no_results)
                } else {
                    binding.searchStatusText.visibility = View.GONE
                }
                binding.searchResultsList.adapter = TmdbResultAdapter(results) { result -> applyMatch(result) }
            }
        }
    }

    private fun applyMatch(result: TmdbSearchResult) {
        val engine = MatchingEngine(videoMetadataRepository, com.retrotube.app.metadata.FolderMetadataRepository(this))
        val filename = Uri.parse(videoUri).lastPathSegment ?: videoUri
        ioExecutor.execute {
            engine.applyMovieMatch(VideoRef(videoUri, filename), result.id)
            mainHandler.post {
                populateFields()
                binding.searchSection.visibility = View.GONE
                binding.searchInput.setText("")
            }
        }
    }

    private fun save() {
        val title = binding.titleInput.text?.toString()?.trim().orEmpty()
        if (title.isNotEmpty()) {
            videoMetadataRepository.setCustomTitle(videoUri, title)
        }
        selectedPoster?.let { videoMetadataRepository.setCustomThumbnail(videoUri, it) }
        finish()
    }

    private class TmdbResultAdapter(
        private val results: List<TmdbSearchResult>,
        private val onPick: (TmdbSearchResult) -> Unit,
    ) : RecyclerView.Adapter<TmdbResultAdapter.ViewHolder>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val binding = ItemTmdbSearchResultBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return ViewHolder(binding)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val result = results[position]
            holder.title.text = result.title
            holder.year.text = result.year?.toString().orEmpty()
            holder.itemView.setOnClickListener { onPick(result) }
        }

        override fun getItemCount(): Int = results.size

        class ViewHolder(binding: ItemTmdbSearchResultBinding) : RecyclerView.ViewHolder(binding.root) {
            val title: TextView = binding.resultTitle
            val year: TextView = binding.resultYear
        }
    }
}
