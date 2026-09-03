package com.retrotube.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.documentfile.provider.DocumentFile
import androidx.recyclerview.widget.LinearLayoutManager
import com.retrotube.app.databinding.ActivityLibraryBinding
import com.retrotube.app.library.LibraryItem
import com.retrotube.app.library.LibraryListAdapter
import com.retrotube.app.library.LibraryRepository
import com.retrotube.app.progress.PlaybackProgressRepository
import com.retrotube.app.settings.SettingsRepository

class LibraryActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLibraryBinding
    private lateinit var libraryRepository: LibraryRepository
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var progressRepository: PlaybackProgressRepository
    private lateinit var adapter: LibraryListAdapter

    /** Empty = showing the top-level list of added root folders. */
    private val folderStack = mutableListOf<DocumentFile>()

    private val addFolder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            libraryRepository.addFolder(uri)
            refreshList()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLibraryBinding.inflate(layoutInflater)
        setContentView(binding.root)

        libraryRepository = LibraryRepository(this)
        settingsRepository = SettingsRepository(this)
        progressRepository = PlaybackProgressRepository(this)

        adapter = LibraryListAdapter(
            context = this,
            onFolderClick = { folder -> folderStack.add(folder.document); refreshList() },
            onFolderRemoveClick = { folder -> confirmRemoveFolder(folder) },
            onVideoClick = { video -> launchPlayer(video) },
            onVideoMenuClick = { video -> openOverrideSettings(video) },
        )
        binding.libraryList.layoutManager = LinearLayoutManager(this)
        binding.libraryList.adapter = adapter

        binding.addFolderButton.setOnClickListener { addFolder.launch(null) }
        binding.settingsButton.setOnClickListener {
            startActivity(
                Intent(this, EffectSettingsActivity::class.java).apply {
                    putExtra(EffectSettingsActivity.EXTRA_MODE, EffectSettingsActivity.MODE_GLOBAL)
                },
            )
        }
        binding.backButton.setOnClickListener { navigateBack() }

        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (folderStack.isNotEmpty()) {
                        navigateBack()
                    } else {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                    }
                }
            },
        )
    }

    override fun onResume() {
        super.onResume()
        refreshList()
    }

    private fun navigateBack() {
        if (folderStack.isNotEmpty()) {
            folderStack.removeAt(folderStack.size - 1)
            refreshList()
        }
    }

    private fun refreshList() {
        val items: List<LibraryItem> = if (folderStack.isEmpty()) {
            val continueWatching = libraryRepository.resolveVideoItems(
                progressRepository.getAllProgress().map { it.first },
            )
            val continueWatchingSection = if (continueWatching.isNotEmpty()) {
                listOf(LibraryItem.SectionHeader(getString(R.string.continue_watching))) + continueWatching
            } else {
                emptyList()
            }
            continueWatchingSection + libraryRepository.getRootDocuments()
        } else {
            libraryRepository.listChildren(folderStack.last())
        }
        adapter.submitList(items, isRootLevel = folderStack.isEmpty())
        binding.emptyLibraryText.visibility = if (items.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE

        binding.breadcrumbRow.visibility = if (folderStack.isEmpty()) android.view.View.GONE else android.view.View.VISIBLE
        binding.breadcrumbText.text = folderStack.joinToString(" / ") { it.name ?: "?" }
    }

    private fun launchPlayer(video: LibraryItem.VideoItem) {
        val settings = settingsRepository.effectiveSettings(video.document.uri.toString())
        val intent = Intent(this, PlayerActivity::class.java).apply {
            data = video.document.uri
            putExtra(PlayerActivity.EXTRA_SETTINGS, settings.serialize())
        }
        startActivity(intent)
    }

    private fun confirmRemoveFolder(folder: LibraryItem.FolderItem) {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.remove_folder_title, folder.name))
            .setMessage(R.string.remove_folder_message)
            .setPositiveButton(R.string.remove) { _, _ ->
                libraryRepository.removeRoot(folder.document.uri)
                refreshList()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun openOverrideSettings(video: LibraryItem.VideoItem) {
        startActivity(
            Intent(this, EffectSettingsActivity::class.java).apply {
                putExtra(EffectSettingsActivity.EXTRA_MODE, EffectSettingsActivity.MODE_OVERRIDE)
                putExtra(EffectSettingsActivity.EXTRA_VIDEO_URI, video.document.uri.toString())
            },
        )
    }
}
