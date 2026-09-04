package com.retrotube.app

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.retrotube.app.collections.CollectionVideoRowAdapter
import com.retrotube.app.databinding.ActivityTvPickVideosBinding
import com.retrotube.app.library.LibraryRepository
import com.retrotube.app.metadata.VideoMetadataRepository
import com.retrotube.app.tv.TvChannelConfigRepository
import java.util.concurrent.Executors

/** The whole local library as one flat, checkable list -- adding individual
 *  videos to a TV Mode channel, same interaction as building a collection. */
class TvChannelPickVideosActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_CHANNEL_ID = "channel_id"
    }

    private lateinit var binding: ActivityTvPickVideosBinding
    private lateinit var libraryRepository: LibraryRepository
    private lateinit var configRepository: TvChannelConfigRepository
    private lateinit var metadataRepository: VideoMetadataRepository
    private lateinit var adapter: CollectionVideoRowAdapter
    private lateinit var channelId: String

    private var allRows: List<CollectionVideoRowAdapter.Row> = emptyList()
    private val ioExecutor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent.getStringExtra(EXTRA_CHANNEL_ID)
        if (id == null) {
            finish()
            return
        }
        channelId = id

        binding = ActivityTvPickVideosBinding.inflate(layoutInflater)
        setContentView(binding.root)

        libraryRepository = LibraryRepository(this)
        configRepository = TvChannelConfigRepository(this)
        metadataRepository = VideoMetadataRepository(this)

        binding.backButton.setOnClickListener { finish() }

        adapter = CollectionVideoRowAdapter { entry, checked ->
            val uriString = entry.document.uri.toString()
            if (checked) {
                val title = metadataRepository.getCustomTitle(uriString) ?: cleanupName(entry.name)
                configRepository.addVideoSourceIfAbsent(channelId, uriString, title)
            } else {
                configRepository.removeVideoSource(channelId, uriString)
            }
        }
        binding.videoRowList.layoutManager = LinearLayoutManager(this)
        binding.videoRowList.adapter = adapter

        binding.searchField.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) = applyFilter(s?.toString().orEmpty())
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
        })

        loadVideos()
    }

    private fun loadVideos() {
        ioExecutor.execute {
            val channel = configRepository.getChannels().firstOrNull { it.id == channelId }
            val currentUris = channel?.sources.orEmpty()
                .filterIsInstance<com.retrotube.app.tv.TvChannelSource.Video>()
                .map { it.uri }
                .toSet()
            val rows = libraryRepository.getAllVideos().map { entry ->
                val uriString = entry.document.uri.toString()
                val title = metadataRepository.getCustomTitle(uriString) ?: cleanupName(entry.name)
                CollectionVideoRowAdapter.Row(entry, title, uriString in currentUris)
            }.sortedBy { it.title.lowercase() }

            mainHandler.post {
                allRows = rows
                binding.emptyStateText.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
                adapter.submitList(rows)
            }
        }
    }

    private fun applyFilter(query: String) {
        val trimmed = query.trim()
        val filtered = if (trimmed.isEmpty()) {
            allRows
        } else {
            allRows.filter {
                it.title.contains(trimmed, ignoreCase = true) || it.entry.pathLabel.contains(trimmed, ignoreCase = true)
            }
        }
        adapter.submitList(filtered)
    }

    private fun cleanupName(rawName: String): String =
        rawName
            .substringBeforeLast('.')
            .replace('_', ' ')
            .replace('.', ' ')
            .replace(Regex("\\s+"), " ")
            .trim()
}
