package com.retrotube.app

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.PopupMenu
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

    companion object {
        private const val KEY_HAS_SEEN_WELCOME = "has_seen_welcome"
        private const val CONTINUE_WATCHING_CAP = 5
    }

    private lateinit var binding: ActivityLibraryBinding
    private lateinit var libraryRepository: LibraryRepository
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var progressRepository: PlaybackProgressRepository
    private lateinit var adapter: LibraryListAdapter

    /** Empty = showing the top-level list of added root folders. */
    private val folderStack = mutableListOf<DocumentFile>()

    /** URIs currently shown in the Continue Watching row, so the "⋮" menu knows whether
     *  to offer "Remove from Continue Watching" or just go straight to settings. */
    private var continueWatchingUris: Set<String> = emptySet()
    private var showAllContinueWatching = false

    private enum class SortMode { NAME, DATE }
    private var searchQuery: String = ""
    private var sortMode: SortMode = SortMode.NAME

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
            onVideoMenuClick = { video, anchor -> showVideoMenu(video, anchor) },
            onSeeAllClick = { showAllContinueWatching = true; refreshList() },
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

        binding.searchField.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                searchQuery = s?.toString().orEmpty()
                refreshList()
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
        })
        binding.sortButton.setOnClickListener {
            sortMode = if (sortMode == SortMode.NAME) SortMode.DATE else SortMode.NAME
            binding.sortButton.setText(if (sortMode == SortMode.NAME) R.string.sort_name else R.string.sort_date)
            refreshList()
        }

        maybeShowWelcomeDialog()

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

    /** Shown once, first launch only -- a real tooltip-pointer overlay system would be a
     *  much bigger lift for the same "tell a first-time user what to do" value. */
    private fun maybeShowWelcomeDialog() {
        val prefs = getSharedPreferences("retrotube_onboarding", MODE_PRIVATE)
        if (prefs.getBoolean(KEY_HAS_SEEN_WELCOME, false)) return

        AlertDialog.Builder(this)
            .setTitle(R.string.welcome_title)
            .setMessage(R.string.welcome_message)
            .setPositiveButton(R.string.welcome_got_it) { _, _ ->
                prefs.edit().putBoolean(KEY_HAS_SEEN_WELCOME, true).apply()
            }
            .setCancelable(false)
            .show()
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
            continueWatchingUris = continueWatching.map { it.document.uri.toString() }.toSet()
            val continueWatchingSection = if (continueWatching.isNotEmpty()) {
                val visible = if (showAllContinueWatching) continueWatching else continueWatching.take(CONTINUE_WATCHING_CAP)
                val remaining = continueWatching.size - visible.size
                buildList {
                    add(LibraryItem.SectionHeader(getString(R.string.continue_watching)))
                    addAll(visible)
                    if (remaining > 0) add(LibraryItem.SeeAllRow(remaining))
                }
            } else {
                emptyList()
            }
            continueWatchingSection + filterAndSort(libraryRepository.getRootDocuments())
        } else {
            continueWatchingUris = emptySet()
            filterAndSort(libraryRepository.listChildren(folderStack.last()))
        }
        adapter.submitList(items, isRootLevel = folderStack.isEmpty())
        binding.emptyLibraryText.visibility = if (items.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE

        binding.breadcrumbRow.visibility = if (folderStack.isEmpty()) android.view.View.GONE else android.view.View.VISIBLE
        binding.breadcrumbText.text = folderStack.joinToString(" / ") { it.name ?: "?" }
    }

    /** Applies the search box + sort toggle to a folder/video listing. Continue Watching
     *  is deliberately excluded -- it's already curated by recency, filtering/resorting it
     *  the same way wouldn't make sense. */
    private fun filterAndSort(items: List<LibraryItem>): List<LibraryItem> {
        val query = searchQuery.trim()
        val filtered = if (query.isEmpty()) {
            items
        } else {
            items.filter { item ->
                val name = when (item) {
                    is LibraryItem.FolderItem -> item.name
                    is LibraryItem.VideoItem -> item.name
                    else -> ""
                }
                name.contains(query, ignoreCase = true)
            }
        }
        if (sortMode == SortMode.NAME) return filtered

        return filtered.sortedByDescending { item ->
            when (item) {
                is LibraryItem.FolderItem -> item.document.lastModified()
                is LibraryItem.VideoItem -> item.document.lastModified()
                else -> 0L
            }
        }
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

    private fun showVideoMenu(video: LibraryItem.VideoItem, anchor: View) {
        val uriString = video.document.uri.toString()
        if (uriString !in continueWatchingUris) {
            openOverrideSettings(video)
            return
        }

        PopupMenu(this, anchor).apply {
            menu.add(getString(R.string.effect_settings_for_video))
            menu.add(getString(R.string.remove_from_continue_watching))
            setOnMenuItemClickListener { menuItem ->
                when (menuItem.title) {
                    getString(R.string.remove_from_continue_watching) -> {
                        progressRepository.hideFromContinueWatching(uriString)
                        refreshList()
                    }
                    else -> openOverrideSettings(video)
                }
                true
            }
        }.show()
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
