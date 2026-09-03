package com.retrotube.app

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.retrotube.app.collections.CollectionRepository
import com.retrotube.app.collections.CollectionVideoRowAdapter
import com.retrotube.app.databinding.ActivityCollectionEditBinding
import com.retrotube.app.library.LibraryRepository
import com.retrotube.app.metadata.VideoMetadataRepository
import java.util.concurrent.Executors

/**
 * The whole library as one flat, checkable list for building up a collection --
 * streamlined against the alternative of hunting through folders one video at a
 * time from each video's own menu.
 */
class CollectionEditActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_COLLECTION_ID = "collection_id"
    }

    private lateinit var binding: ActivityCollectionEditBinding
    private lateinit var libraryRepository: LibraryRepository
    private lateinit var collectionRepository: CollectionRepository
    private lateinit var metadataRepository: VideoMetadataRepository
    private lateinit var adapter: CollectionVideoRowAdapter
    private lateinit var collectionId: String

    private var allRows: List<CollectionVideoRowAdapter.Row> = emptyList()
    private val ioExecutor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent.getStringExtra(EXTRA_COLLECTION_ID)
        if (id == null) {
            finish()
            return
        }
        collectionId = id

        binding = ActivityCollectionEditBinding.inflate(layoutInflater)
        setContentView(binding.root)

        libraryRepository = LibraryRepository(this)
        collectionRepository = CollectionRepository(this)
        metadataRepository = VideoMetadataRepository(this)

        val collectionName = collectionRepository.get(collectionId)?.name.orEmpty()
        binding.screenTitle.text = getString(R.string.manage_collection_title, collectionName)
        binding.backButton.setOnClickListener { finish() }

        adapter = CollectionVideoRowAdapter { entry, checked ->
            val uriString = entry.document.uri.toString()
            if (checked) {
                collectionRepository.addVideo(collectionId, uriString)
            } else {
                collectionRepository.removeVideo(collectionId, uriString)
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

    /** SAF directory listing is a real IPC call and this walks the whole library
     *  tree, so it runs off the main thread rather than blocking the UI open. */
    private fun loadVideos() {
        ioExecutor.execute {
            val currentUris = collectionRepository.get(collectionId)?.videoUris.orEmpty().toSet()
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
