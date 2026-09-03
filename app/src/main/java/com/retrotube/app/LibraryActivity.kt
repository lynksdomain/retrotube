package com.retrotube.app

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.PopupMenu
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.documentfile.provider.DocumentFile
import androidx.recyclerview.widget.GridLayoutManager
import com.retrotube.app.databinding.ActivityLibraryBinding
import com.retrotube.app.library.LibraryItem
import com.retrotube.app.library.LibraryListAdapter
import com.retrotube.app.library.LibraryRepository
import com.retrotube.app.progress.PlaybackProgressRepository
import com.retrotube.app.settings.SettingsRepository

class LibraryActivity : AppCompatActivity() {

    companion object {
        private const val KEY_HAS_SEEN_WELCOME = "has_seen_welcome"
        private const val POSTER_GRID_SPAN_COUNT = 3
    }

    private lateinit var binding: ActivityLibraryBinding
    private lateinit var libraryRepository: LibraryRepository
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var progressRepository: PlaybackProgressRepository
    private lateinit var adapter: LibraryListAdapter

    /** Empty = showing the top-level list of added root folders. */
    private val folderStack = mutableListOf<DocumentFile>()

    /** URIs currently shown in the Continue Watching rail, so the "⋮" menu knows whether
     *  to offer "Remove from Continue Watching" or just go straight to settings. */
    private var continueWatchingUris: Set<String> = emptySet()

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
        )
        val gridLayoutManager = GridLayoutManager(this, POSTER_GRID_SPAN_COUNT)
        gridLayoutManager.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int): Int =
                adapter.spanSizeFor(position, POSTER_GRID_SPAN_COUNT)
        }
        binding.libraryList.layoutManager = gridLayoutManager
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

        binding.searchToggleButton.setOnClickListener { toggleSearch() }

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

    /** Search is hidden until asked for -- opening it focuses the field and raises the
     *  keyboard; closing it clears the query so the grid returns to its full contents
     *  rather than leaving an invisible filter applied. */
    private fun toggleSearch() {
        val opening = binding.searchRow.visibility != View.VISIBLE
        if (opening) {
            binding.searchRow.visibility = View.VISIBLE
            binding.searchField.requestFocus()
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(binding.searchField, InputMethodManager.SHOW_IMPLICIT)
        } else {
            binding.searchField.text?.clear()
            binding.searchField.clearFocus()
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(binding.searchField.windowToken, 0)
            binding.searchRow.visibility = View.GONE
        }
    }

    private fun navigateBack() {
        if (folderStack.isNotEmpty()) {
            folderStack.removeAt(folderStack.size - 1)
            refreshList()
        }
    }

    /** Continue Watching only shows at the library root -- once you've navigated into a
     *  folder it drops away rather than following you around, since it isn't scoped to
     *  what you're currently browsing. It travels as the grid's first row rather than a
     *  pinned section, so it scrolls away with everything else. */
    private fun refreshList() {
        val items: List<LibraryItem> = if (folderStack.isEmpty()) {
            val continueWatching = libraryRepository.resolveVideoItems(
                progressRepository.getAllProgress().map { it.first },
            )
            continueWatchingUris = continueWatching.map { it.document.uri.toString() }.toSet()
            val rail = if (continueWatching.isEmpty()) {
                emptyList()
            } else {
                listOf(LibraryItem.ContinueWatchingRail(continueWatching))
            }

            rail + filterAndSort(libraryRepository.getRootDocuments())
        } else {
            continueWatchingUris = emptySet()
            filterAndSort(libraryRepository.listChildren(folderStack.last()))
        }
        adapter.submitList(items, isRootLevel = folderStack.isEmpty())
        binding.emptyLibraryText.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE

        binding.breadcrumbRow.visibility = if (folderStack.isEmpty()) View.GONE else View.VISIBLE
        binding.breadcrumbText.text = folderStack.joinToString(" / ") { it.name ?: "?" }
    }

    private fun filterAndSort(items: List<LibraryItem>): List<LibraryItem> {
        val query = searchQuery.trim()
        val filtered = if (query.isEmpty()) {
            items
        } else {
            items.filter { item ->
                val name = when (item) {
                    is LibraryItem.FolderItem -> item.name
                    is LibraryItem.VideoItem -> item.name
                    is LibraryItem.ContinueWatchingRail -> ""
                }
                name.contains(query, ignoreCase = true)
            }
        }
        if (sortMode == SortMode.NAME) return filtered

        return filtered.sortedByDescending { item ->
            when (item) {
                is LibraryItem.FolderItem -> item.document.lastModified()
                is LibraryItem.VideoItem -> item.document.lastModified()
                is LibraryItem.ContinueWatchingRail -> Long.MAX_VALUE
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

        PopupMenu(this, anchor).apply {
            menu.add(getString(R.string.effect_settings_for_video))
            menu.add(getString(R.string.edit_title_and_poster))
            if (uriString in continueWatchingUris) {
                menu.add(getString(R.string.remove_from_continue_watching))
            }
            setOnMenuItemClickListener { menuItem ->
                when (menuItem.title) {
                    getString(R.string.edit_title_and_poster) -> openMetadataEditor(video)
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

    private fun openMetadataEditor(video: LibraryItem.VideoItem) {
        startActivity(
            Intent(this, VideoMetadataActivity::class.java).apply {
                putExtra(VideoMetadataActivity.EXTRA_VIDEO_URI, video.document.uri.toString())
            },
        )
    }
}
