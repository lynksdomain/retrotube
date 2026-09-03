package com.retrotube.app

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.PopupMenu
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.documentfile.provider.DocumentFile
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import com.retrotube.app.collections.CollectionRepository
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
    private lateinit var collectionRepository: CollectionRepository
    private lateinit var adapter: LibraryListAdapter

    /** Empty = showing the top-level list of added root folders. */
    private val folderStack = mutableListOf<DocumentFile>()

    /** Non-null = showing a collection's videos instead of a real folder's contents.
     *  Mutually exclusive with [folderStack] -- collections only open from the root. */
    private var openCollectionId: String? = null
    private var openCollectionName: String = ""

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

    /** Which collection "Change poster" was tapped for -- GetContent's callback carries
     *  only the picked image, so the target collection has to be remembered here. */
    private var pendingPosterCollectionId: String? = null
    private val pickCollectionPoster = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        val collectionId = pendingPosterCollectionId
        pendingPosterCollectionId = null
        if (uri != null && collectionId != null) {
            collectionRepository.setPosterFromUri(collectionId, uri)
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
        collectionRepository = CollectionRepository(this)

        adapter = LibraryListAdapter(
            context = this,
            onFolderClick = { folder -> folderStack.add(folder.document); refreshList() },
            onFolderRemoveClick = { folder -> confirmRemoveFolder(folder) },
            onVideoClick = { video -> launchPlayer(video) },
            onVideoMenuClick = { video, anchor -> showVideoMenu(video, anchor) },
            onCollectionClick = { collection ->
                openCollectionId = collection.id
                openCollectionName = collection.name
                refreshList()
            },
            onCollectionRemoveClick = { collection -> confirmRemoveCollection(collection) },
            onCollectionEditPosterClick = { collection ->
                pendingPosterCollectionId = collection.id
                pickCollectionPoster.launch("image/*")
            },
        )
        val gridLayoutManager = GridLayoutManager(this, POSTER_GRID_SPAN_COUNT)
        gridLayoutManager.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int): Int =
                adapter.spanSizeFor(position, POSTER_GRID_SPAN_COUNT)
        }
        binding.libraryList.layoutManager = gridLayoutManager
        binding.libraryList.adapter = adapter
        reorderTouchHelper.attachToRecyclerView(binding.libraryList)

        binding.addFolderButton.setOnClickListener { addFolder.launch(null) }
        binding.networkSharesButton.setOnClickListener {
            startActivity(Intent(this, NetworkSharesActivity::class.java))
        }
        binding.settingsButton.setOnClickListener {
            startActivity(
                Intent(this, EffectSettingsActivity::class.java).apply {
                    putExtra(EffectSettingsActivity.EXTRA_MODE, EffectSettingsActivity.MODE_GLOBAL)
                },
            )
        }
        binding.backButton.setOnClickListener { navigateBack() }
        binding.editCollectionContentsButton.setOnClickListener {
            val id = openCollectionId ?: return@setOnClickListener
            startActivity(
                Intent(this, CollectionEditActivity::class.java).apply {
                    putExtra(CollectionEditActivity.EXTRA_COLLECTION_ID, id)
                },
            )
        }

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
                    if (openCollectionId != null || folderStack.isNotEmpty()) {
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
        if (openCollectionId != null) {
            openCollectionId = null
            openCollectionName = ""
            refreshList()
        } else if (folderStack.isNotEmpty()) {
            folderStack.removeAt(folderStack.size - 1)
            refreshList()
        }
    }

    /** Continue Watching only shows at the library root -- once you've navigated into a
     *  folder (or a collection) it drops away rather than following you around, since
     *  it isn't scoped to what you're currently browsing. It travels as the grid's first
     *  row rather than a pinned section, so it scrolls away with everything else. */
    private fun refreshList() {
        val collectionId = openCollectionId
        val items: List<LibraryItem> = when {
            collectionId != null -> {
                continueWatchingUris = emptySet()
                val collection = collectionRepository.get(collectionId)
                if (collection == null) {
                    openCollectionId = null
                    emptyList()
                } else {
                    val query = searchQuery.trim()
                    val videos = libraryRepository.resolveVideoItems(collection.videoUris)
                    if (query.isEmpty()) videos else videos.filter { it.name.contains(query, ignoreCase = true) }
                }
            }
            folderStack.isEmpty() -> {
                val continueWatching = libraryRepository.resolveVideoItems(
                    progressRepository.getAllProgress().map { it.first },
                )
                continueWatchingUris = continueWatching.map { it.document.uri.toString() }.toSet()
                val rail = if (continueWatching.isEmpty()) {
                    emptyList()
                } else {
                    listOf(LibraryItem.ContinueWatchingRail(continueWatching))
                }

                val collections: List<LibraryItem> = collectionRepository.getAll()
                    .sortedBy { it.name.lowercase() }
                    .map { LibraryItem.CollectionItem(it.id, it.name, it.videoUris.size) }
                val filteredCollections = filterAndSort(collections)
                val collectionsSection: List<LibraryItem> = if (filteredCollections.isEmpty()) {
                    emptyList()
                } else {
                    listOf(LibraryItem.SectionHeader(getString(R.string.collections_section_title))) + filteredCollections
                }

                val filteredFolders = filterAndSort(libraryRepository.getRootDocuments())
                val librarySection: List<LibraryItem> = if (filteredFolders.isEmpty()) {
                    emptyList()
                } else {
                    listOf(LibraryItem.SectionHeader(getString(R.string.library_section_title))) + filteredFolders
                }

                rail + collectionsSection + librarySection
            }
            else -> {
                continueWatchingUris = emptySet()
                filterAndSort(libraryRepository.listChildren(folderStack.last()))
            }
        }
        adapter.submitList(items, isRootLevel = folderStack.isEmpty() && collectionId == null)
        binding.emptyLibraryText.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE

        val browsingSomewhere = collectionId != null || folderStack.isNotEmpty()
        binding.breadcrumbRow.visibility = if (browsingSomewhere) View.VISIBLE else View.GONE
        binding.breadcrumbText.text = when {
            collectionId != null -> openCollectionName
            else -> folderStack.joinToString(" / ") { it.name ?: "?" }
        }
        // Order inside a collection is manual (drag to reorder), so the name/date
        // sort toggle doesn't apply there.
        binding.sortButton.visibility = if (collectionId != null) View.GONE else View.VISIBLE
        binding.editCollectionContentsButton.visibility = if (collectionId != null) View.VISIBLE else View.GONE
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
                    is LibraryItem.CollectionItem -> item.name
                    is LibraryItem.ContinueWatchingRail -> ""
                    is LibraryItem.SectionHeader -> ""
                }
                name.contains(query, ignoreCase = true)
            }
        }
        if (sortMode == SortMode.NAME) return filtered

        return filtered.sortedByDescending { item ->
            when (item) {
                is LibraryItem.FolderItem -> item.document.lastModified()
                is LibraryItem.VideoItem -> item.document.lastModified()
                is LibraryItem.CollectionItem -> Long.MAX_VALUE
                is LibraryItem.ContinueWatchingRail -> Long.MAX_VALUE
                is LibraryItem.SectionHeader -> Long.MAX_VALUE
            }
        }
    }

    /** Active only while a collection is open -- drag to reorder its videos, since that
     *  order is the whole point of a manually-curated shelf. */
    private val reorderTouchHelper = ItemTouchHelper(
        object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN or ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT,
            0,
        ) {
            override fun isLongPressDragEnabled() = openCollectionId != null

            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder,
            ): Boolean {
                if (openCollectionId == null) return false
                adapter.moveItem(viewHolder.bindingAdapterPosition, target.bindingAdapterPosition)
                return true
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) = Unit

            override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
                super.clearView(recyclerView, viewHolder)
                val collectionId = openCollectionId ?: return
                val uris = adapter.currentItems()
                    .filterIsInstance<LibraryItem.VideoItem>()
                    .map { it.document.uri.toString() }
                collectionRepository.setVideoOrder(collectionId, uris)
            }
        },
    )

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

    private fun confirmRemoveCollection(collection: LibraryItem.CollectionItem) {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.remove_collection_title, collection.name))
            .setMessage(R.string.remove_collection_message)
            .setPositiveButton(R.string.remove) { _, _ ->
                collectionRepository.delete(collection.id)
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
            menu.add(getString(R.string.add_to_collection))
            if (uriString in continueWatchingUris) {
                menu.add(getString(R.string.remove_from_continue_watching))
            }
            setOnMenuItemClickListener { menuItem ->
                when (menuItem.title) {
                    getString(R.string.edit_title_and_poster) -> openMetadataEditor(video)
                    getString(R.string.add_to_collection) -> showAddToCollectionDialog(video)
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

    private fun showAddToCollectionDialog(video: LibraryItem.VideoItem) {
        val existing = collectionRepository.getAll()
        val labels = (existing.map { it.name } + getString(R.string.new_collection)).toTypedArray()

        AlertDialog.Builder(this)
            .setTitle(R.string.add_to_collection)
            .setItems(labels) { _, index ->
                if (index < existing.size) {
                    collectionRepository.addVideo(existing[index].id, video.document.uri.toString())
                    refreshList()
                } else {
                    promptNewCollection(video)
                }
            }
            .show()
    }

    private fun promptNewCollection(video: LibraryItem.VideoItem) {
        val input = EditText(this)
        AlertDialog.Builder(this)
            .setTitle(R.string.new_collection_prompt_title)
            .setView(input)
            .setPositiveButton(R.string.create) { _, _ ->
                val name = input.text?.toString()?.trim().orEmpty()
                if (name.isNotEmpty()) {
                    collectionRepository.create(name, video.document.uri.toString())
                    refreshList()
                }
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

    private fun openMetadataEditor(video: LibraryItem.VideoItem) {
        startActivity(
            Intent(this, VideoMetadataActivity::class.java).apply {
                putExtra(VideoMetadataActivity.EXTRA_VIDEO_URI, video.document.uri.toString())
            },
        )
    }
}
