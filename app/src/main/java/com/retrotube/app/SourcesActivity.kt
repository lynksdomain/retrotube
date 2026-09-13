package com.retrotube.app

import android.content.DialogInterface
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.documentfile.provider.DocumentFile
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.retrotube.app.bucket.BucketRepository
import com.retrotube.app.bucket.ContentKind
import com.retrotube.app.databinding.ActivitySourcesBinding
import com.retrotube.app.databinding.DialogAddNetworkShareBinding
import com.retrotube.app.library.FolderVideoScanner
import com.retrotube.app.library.LibraryItem
import com.retrotube.app.library.LibraryListAdapter
import com.retrotube.app.library.LibraryRepository
import com.retrotube.app.library.TaggedFolderRepository
import com.retrotube.app.metadata.FolderMetadataRepository
import com.retrotube.app.metadata.VideoMetadataRepository
import com.retrotube.app.metadata.tmdb.MatchingEngine
import com.retrotube.app.metadata.tmdb.VideoRef
import com.retrotube.app.network.NetworkShare
import com.retrotube.app.network.NetworkShareRepository
import com.retrotube.app.network.SmbBrowser
import com.retrotube.app.network.SmbClient
import com.retrotube.app.network.SmbUri
import com.retrotube.app.settings.SettingsRepository
import java.util.concurrent.Executors
import com.retrotube.app.util.applyTopBarInset

/**
 * The Sources tab: raw plumbing only -- local folders (SAF) and SMB shares,
 * browsable and taggable, but with none of the rich metadata that lives on
 * the Library tab instead. No Continue Watching here (that's about content,
 * not sources) and no Collections (removed entirely -- TV Mode channels
 * reference sources directly).
 */
class SourcesActivity : AppCompatActivity() {

    /** Which root list the segmented control is showing -- only meaningful at
     *  the library root (see [isAtRoot]); the toolbar's own icons react to
     *  this too (WiFi Import only makes sense for local folders). */
    private enum class SourceScope { LOCAL, NETWORK }

    private lateinit var binding: ActivitySourcesBinding
    private lateinit var libraryRepository: LibraryRepository
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var networkShareRepository: NetworkShareRepository
    private lateinit var adapter: LibraryListAdapter
    private val smbExecutor = Executors.newSingleThreadExecutor()
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var scope = SourceScope.LOCAL

    /** Empty = showing the top-level list of added roots (local folders + shares). */
    private val folderStack = mutableListOf<DocumentFile>()

    private var openSmbShareId: String? = null
    private var openSmbPath: String = ""
    private var openSmbShareName: String = ""

    private val isAtRoot: Boolean get() = folderStack.isEmpty() && openSmbShareId == null

    private val addFolder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            libraryRepository.addFolder(uri)
            refreshList()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySourcesBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.root.applyTopBarInset()

        libraryRepository = LibraryRepository(this)
        settingsRepository = SettingsRepository(this)
        networkShareRepository = NetworkShareRepository(this)

        adapter = LibraryListAdapter(
            context = this,
            onFolderClick = { folder -> folderStack.add(folder.document); refreshList() },
            onFolderMenuClick = { folder, anchor -> showFolderMenu(folder, anchor) },
            onVideoClick = { video -> launchPlayer(video) },
            onVideoMenuClick = { video, anchor -> showVideoMenu(video, anchor) },
            onSmbFolderClick = { folder ->
                openSmbShareId = folder.shareId
                openSmbPath = folder.relativePath
                openSmbShareName = networkShareRepository.get(folder.shareId)?.displayName.orEmpty()
                refreshList()
            },
            onSmbFolderMenuClick = { folder, anchor -> showSmbFolderMenu(folder, anchor) },
            onSmbVideoClick = { video ->
                val queue = adapter.currentItems().filterIsInstance<LibraryItem.SmbVideoItem>().map { it.uri }
                launchPlayerForUri(video.uri, queue, queue.indexOfFirst { it == video.uri })
            },
            onSmbVideoMenuClick = { video, anchor -> showSmbVideoMenu(video, anchor) },
        )
        val spanCount = com.retrotube.app.library.GridSpanCalculator.spanCount(this)
        val gridLayoutManager = GridLayoutManager(this, spanCount)
        gridLayoutManager.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int): Int =
                adapter.spanSizeFor(position, spanCount)
        }
        binding.libraryList.layoutManager = gridLayoutManager
        binding.libraryList.adapter = adapter

        binding.addSourceButton.setOnClickListener {
            when (scope) {
                SourceScope.LOCAL -> addFolder.launch(null)
                SourceScope.NETWORK -> showAddShareDialog()
            }
        }
        binding.wifiImportButton.setOnClickListener {
            startActivity(Intent(this, WifiImportActivity::class.java))
        }
        binding.settingsButton.setOnClickListener {
            startActivity(
                Intent(this, EffectSettingsActivity::class.java).apply {
                    putExtra(EffectSettingsActivity.EXTRA_MODE, EffectSettingsActivity.MODE_GLOBAL)
                },
            )
        }
        binding.backButton.setOnClickListener { navigateBack() }
        binding.localScopeButton.setOnClickListener { setScope(SourceScope.LOCAL) }
        binding.networkScopeButton.setOnClickListener { setScope(SourceScope.NETWORK) }

        MainTabBar.setup(this, binding.root, MainTabBar.Tab.SOURCES)

        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (openSmbShareId != null || folderStack.isNotEmpty()) {
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

    private fun setScope(newScope: SourceScope) {
        if (scope == newScope) return
        scope = newScope
        binding.localScopeButton.setBackgroundResource(
            if (scope == SourceScope.LOCAL) R.drawable.segmented_control_selected else 0,
        )
        binding.localScopeButton.setTextColor(
            resources.getColor(if (scope == SourceScope.LOCAL) R.color.retro_text_primary else R.color.retro_text_muted, theme),
        )
        binding.networkScopeButton.setBackgroundResource(
            if (scope == SourceScope.NETWORK) R.drawable.segmented_control_selected else 0,
        )
        binding.networkScopeButton.setTextColor(
            resources.getColor(if (scope == SourceScope.NETWORK) R.color.retro_text_primary else R.color.retro_text_muted, theme),
        )
        refreshList()
    }

    private fun navigateBack() {
        if (openSmbShareId != null) {
            if (openSmbPath.isEmpty()) {
                openSmbShareId = null
                openSmbShareName = ""
            } else {
                openSmbPath = openSmbPath.substringBeforeLast('/', "")
            }
            refreshList()
        } else if (folderStack.isNotEmpty()) {
            folderStack.removeAt(folderStack.size - 1)
            refreshList()
        }
    }

    private fun refreshList() {
        binding.sourceScopeToggle.visibility = if (isAtRoot) View.VISIBLE else View.GONE
        // WiFi Import only makes sense for local folders -- the toolbar reacts
        // to whichever scope segment is selected, same as the "+" button.
        binding.wifiImportButton.visibility = if (isAtRoot && scope == SourceScope.LOCAL) View.VISIBLE else View.GONE

        val smbShareId = openSmbShareId
        if (smbShareId != null) {
            binding.sourcesTitleText.visibility = View.GONE
            binding.breadcrumbRow.visibility = View.VISIBLE
            binding.breadcrumbText.text = if (openSmbPath.isEmpty()) {
                openSmbShareName
            } else {
                "$openSmbShareName/$openSmbPath"
            }
            binding.loadingSpinner.visibility = View.VISIBLE
            binding.emptyLibraryText.visibility = View.GONE
            loadSmbFolderAsync(smbShareId, openSmbPath)
            return
        }

        binding.loadingSpinner.visibility = View.GONE
        val items: List<LibraryItem> = if (folderStack.isEmpty()) {
            when (scope) {
                SourceScope.NETWORK -> networkShareRepository.getAll()
                    .sortedBy { it.displayName.lowercase() }
                    .map { LibraryItem.SmbFolderItem(it.id, "", it.displayName) }
                SourceScope.LOCAL -> libraryRepository.getRootDocuments()
            }
        } else {
            libraryRepository.listChildren(folderStack.last())
        }
        adapter.submitList(items)
        binding.emptyLibraryText.text = getString(R.string.sources_empty)
        binding.emptyLibraryText.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE

        val browsingSomewhere = folderStack.isNotEmpty()
        binding.sourcesTitleText.visibility = if (browsingSomewhere) View.GONE else View.VISIBLE
        binding.breadcrumbRow.visibility = if (browsingSomewhere) View.VISIBLE else View.GONE
        binding.breadcrumbText.text = folderStack.joinToString(" / ") { it.name ?: "?" }
    }

    /** SMB directory listings are real network round trips, unlike SAF's local IPC --
     *  this runs off the main thread, and drops a stale result if the user has already
     *  navigated elsewhere by the time it comes back. */
    private fun loadSmbFolderAsync(shareId: String, path: String) {
        val share = networkShareRepository.get(shareId)
        if (share == null) {
            openSmbShareId = null
            openSmbPath = ""
            openSmbShareName = ""
            refreshList()
            return
        }
        smbExecutor.execute {
            val result = runCatching { SmbBrowser.listChildren(share, path) }
            mainHandler.post {
                if (openSmbShareId != shareId || openSmbPath != path) return@post
                binding.loadingSpinner.visibility = View.GONE
                val items = result.getOrElse { emptyList() }
                adapter.submitList(items)
                binding.emptyLibraryText.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
                val error = result.exceptionOrNull()
                // Three distinct outcomes, not one generic "something went wrong" --
                // a real timeout and a genuinely-empty folder should never read the
                // same to the user (spec §9.2).
                binding.emptyLibraryText.text = when {
                    error is java.net.SocketTimeoutException -> getString(R.string.folder_timed_out)
                    error != null -> getString(R.string.folder_unreachable)
                    else -> getString(R.string.folder_empty_genuinely)
                }
                error?.let {
                    android.widget.Toast.makeText(
                        this,
                        getString(R.string.connection_failed, it.message ?: it.toString()),
                        android.widget.Toast.LENGTH_LONG,
                    ).show()
                }
            }
        }
    }

    private fun launchPlayer(video: LibraryItem.VideoItem) {
        val queue = adapter.currentItems().filterIsInstance<LibraryItem.VideoItem>().map { it.document.uri }
        launchPlayerForUri(video.document.uri, queue, queue.indexOfFirst { it == video.document.uri })
    }

    private fun launchPlayerForUri(uri: Uri, queue: List<Uri> = emptyList(), queueIndex: Int = -1) {
        val settings = settingsRepository.effectiveSettings(uri.toString())
        val intent = Intent(this, PlayerActivity::class.java).apply {
            data = uri
            putExtra(PlayerActivity.EXTRA_SETTINGS, settings.serialize())
            if (queue.size > 1 && queueIndex >= 0) {
                putStringArrayListExtra(PlayerActivity.EXTRA_QUEUE_URIS, ArrayList(queue.map { it.toString() }))
                putExtra(PlayerActivity.EXTRA_QUEUE_INDEX, queueIndex)
            }
        }
        startActivity(intent)
    }

    /** A local folder card's "⋯" -- at the library root this is the source
     *  itself (Edit its display title/poster, or Remove it entirely); browsing
     *  inside it, the same "⋯" instead means "Add to Library" (tag this
     *  subfolder into a bucket), since only root sources are removable. */
    private fun showFolderMenu(folder: LibraryItem.FolderItem, anchor: View) {
        if (folderStack.isEmpty()) {
            android.widget.PopupMenu(this, anchor).apply {
                menu.add(getString(R.string.edit))
                menu.add(getString(R.string.remove))
                setOnMenuItemClickListener { menuItem ->
                    when (menuItem.title) {
                        getString(R.string.edit) -> startActivity(
                            Intent(this@SourcesActivity, SourceEditInfoActivity::class.java)
                                .putExtra(SourceEditInfoActivity.EXTRA_FOLDER_KEY, folder.document.uri.toString())
                                .putExtra(SourceEditInfoActivity.EXTRA_FOLDER_NAME, folder.name),
                        )
                        else -> confirmRemoveFolder(folder)
                    }
                    true
                }
            }.show()
        } else {
            val folderKey = folder.document.uri.toString()
            android.widget.PopupMenu(this, anchor).apply {
                menu.add(getString(R.string.add_to_library_menu))
                setOnMenuItemClickListener {
                    promptAddToLibrary(folderKey, folder.name, rootOrShareId = folderKey)
                    true
                }
            }.show()
        }
    }

    /** Same idea as [showFolderMenu], for an SMB card -- an empty [relativePath]
     *  means this card is a share's own root (Edit the connection, or Remove
     *  it); otherwise it's a subfolder inside an already-connected share. */
    private fun showSmbFolderMenu(folder: LibraryItem.SmbFolderItem, anchor: View) {
        if (folder.relativePath.isEmpty()) {
            android.widget.PopupMenu(this, anchor).apply {
                menu.add(getString(R.string.edit))
                menu.add(getString(R.string.remove))
                setOnMenuItemClickListener { menuItem ->
                    when (menuItem.title) {
                        getString(R.string.edit) -> networkShareRepository.get(folder.shareId)?.let { showAddShareDialog(it) }
                        else -> confirmRemoveShare(folder.shareId, folder.name)
                    }
                    true
                }
            }.show()
        } else {
            val folderKey = SmbUri.build(folder.shareId, folder.relativePath).toString()
            android.widget.PopupMenu(this, anchor).apply {
                menu.add(getString(R.string.add_to_library_menu))
                setOnMenuItemClickListener {
                    promptAddToLibrary(folderKey, folder.name, rootOrShareId = folder.shareId)
                    true
                }
            }.show()
        }
    }

    private fun confirmRemoveShare(shareId: String, shareName: String) {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.remove_share_title, shareName))
            .setMessage(R.string.remove_share_message)
            .setPositiveButton(R.string.remove) { _, _ ->
                networkShareRepository.delete(shareId)
                refreshList()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showSmbVideoMenu(video: LibraryItem.SmbVideoItem, anchor: View) {
        val uriString = video.uri.toString()
        android.widget.PopupMenu(this, anchor).apply {
            menu.add(getString(R.string.effect_settings_for_video))
            menu.add(getString(R.string.edit_title_and_poster))
            setOnMenuItemClickListener { menuItem ->
                when (menuItem.title) {
                    getString(R.string.edit_title_and_poster) -> openMetadataEditor(uriString)
                    else -> startActivity(
                        Intent(this@SourcesActivity, EffectSettingsActivity::class.java).apply {
                            putExtra(EffectSettingsActivity.EXTRA_MODE, EffectSettingsActivity.MODE_OVERRIDE)
                            putExtra(EffectSettingsActivity.EXTRA_VIDEO_URI, uriString)
                        },
                    )
                }
                true
            }
        }.show()
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
        android.widget.PopupMenu(this, anchor).apply {
            menu.add(getString(R.string.effect_settings_for_video))
            menu.add(getString(R.string.edit_title_and_poster))
            setOnMenuItemClickListener { menuItem ->
                when (menuItem.title) {
                    getString(R.string.edit_title_and_poster) -> openMetadataEditor(video)
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
        openMetadataEditor(video.document.uri.toString())
    }

    private fun openMetadataEditor(uriString: String) {
        startActivity(
            Intent(this, VideoMetadataActivity::class.java).apply {
                putExtra(VideoMetadataActivity.EXTRA_VIDEO_URI, uriString)
            },
        )
    }

    // --- Add Network Share (merged in from the old standalone NetworkSharesActivity) ---

    /** [existing] is null when adding a brand-new share, or the share being
     *  edited from its root card's "⋯" menu -- same dialog either way, just
     *  pre-filled and writing back to the same id instead of minting a new one. */
    private fun showAddShareDialog(existing: NetworkShare? = null) {
        val dialogBinding = DialogAddNetworkShareBinding.inflate(LayoutInflater.from(this))
        if (existing != null) {
            dialogBinding.shareDisplayName.setText(existing.displayName)
            dialogBinding.shareHost.setText(existing.host)
            dialogBinding.shareFolder.setText(existing.shareName)
            dialogBinding.shareUsername.setText(existing.username)
            dialogBinding.sharePassword.setText(existing.password)
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle(if (existing != null) R.string.edit_network_share else R.string.add_network_share)
            .setView(dialogBinding.root)
            .setPositiveButton(if (existing != null) R.string.save_metadata else R.string.connect, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener {
                attemptConnection(dialogBinding, dialog, existing)
            }
        }
        dialog.show()
    }

    private fun attemptConnection(dialogBinding: DialogAddNetworkShareBinding, dialog: AlertDialog, existing: NetworkShare?) {
        val displayName = dialogBinding.shareDisplayName.text?.toString()?.trim().orEmpty()
        val host = dialogBinding.shareHost.text?.toString()?.trim().orEmpty()
        val shareName = dialogBinding.shareFolder.text?.toString()?.trim().orEmpty()
        val username = dialogBinding.shareUsername.text?.toString()?.trim().orEmpty()
        val password = dialogBinding.sharePassword.text?.toString().orEmpty()

        if (host.isEmpty() || shareName.isEmpty()) {
            showStatus(dialogBinding, getString(R.string.connection_failed, "host and shared folder are required"))
            return
        }

        val share = NetworkShare(
            id = existing?.id.orEmpty(),
            displayName = displayName.ifEmpty { host },
            host = host,
            shareName = shareName,
            username = username,
            password = password,
        )

        dialog.getButton(DialogInterface.BUTTON_POSITIVE).isEnabled = false
        showStatus(dialogBinding, getString(R.string.connecting))

        smbExecutor.execute {
            val result = runCatching { SmbClient.listRoot(share) }
            mainHandler.post {
                dialog.getButton(DialogInterface.BUTTON_POSITIVE).isEnabled = true
                result.onSuccess { entries ->
                    showStatus(dialogBinding, getString(R.string.connection_succeeded, entries.size))
                    if (existing != null) networkShareRepository.update(share) else networkShareRepository.add(share)
                    refreshList()
                    dialog.dismiss()
                }.onFailure { error ->
                    showStatus(dialogBinding, getString(R.string.connection_failed, error.message ?: error.toString()))
                }
            }
        }
    }

    private fun showStatus(dialogBinding: DialogAddNetworkShareBinding, text: String) {
        dialogBinding.connectionStatusText.visibility = View.VISIBLE
        dialogBinding.connectionStatusText.text = text
    }

    // --- "Add to Library" tagging (local + SMB folders alike) ---

    /** Matches the iOS "Add to Library" sheet: pick which shelf this folder's
     *  content lands on (any existing bucket, or a brand-new one created right
     *  here), then what the folder actually contains -- the shelf choice and
     *  the content-kind choice are independent of each other, unlike the old
     *  version of this screen which hardcoded TV/Movies as the shelf. */
    private fun promptAddToLibrary(folderKey: String, folderName: String, rootOrShareId: String) {
        val bucketRepository = BucketRepository(this)
        var buckets = bucketRepository.getAll()
        var selectedBucket = buckets.firstOrNull { it.id == BucketRepository.BUCKET_TV } ?: buckets.first()

        val dialogBinding = com.retrotube.app.databinding.DialogAddToLibraryBinding.inflate(layoutInflater)
        val dialog = com.google.android.material.bottomsheet.BottomSheetDialog(this)
        dialog.setContentView(dialogBinding.root)

        dialogBinding.addToTitleText.text = getString(R.string.add_to_this_shelf, folderName)
        fun updateShelfLabel() {
            dialogBinding.shelfValueText.text = selectedBucket.name
        }
        updateShelfLabel()

        dialogBinding.cancelButton.setOnClickListener { dialog.dismiss() }

        dialogBinding.shelfRow.setOnClickListener {
            android.widget.PopupMenu(this, dialogBinding.shelfRow).apply {
                buckets.forEach { bucket -> menu.add(bucket.name) }
                setOnMenuItemClickListener { menuItem ->
                    selectedBucket = buckets.first { it.name == menuItem.title }
                    updateShelfLabel()
                    true
                }
            }.show()
        }

        dialogBinding.newShelfRow.setOnClickListener {
            val input = android.widget.EditText(this)
            AlertDialog.Builder(this)
                .setTitle(R.string.new_shelf_prompt_title)
                .setView(input)
                .setPositiveButton(R.string.create) { _, _ ->
                    val name = input.text?.toString()?.trim().orEmpty()
                    if (name.isNotEmpty()) {
                        selectedBucket = bucketRepository.create(name)
                        buckets = bucketRepository.getAll()
                        updateShelfLabel()
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }

        bindAddToLibraryOption(
            dialogBinding.singleShowOption,
            R.drawable.ic_tv,
            R.string.add_to_library_single_show,
            R.string.add_to_library_single_show_desc,
        ) { dialog.dismiss(); tagSingleShow(folderKey, folderName, rootOrShareId, selectedBucket.id) }

        bindAddToLibraryOption(
            dialogBinding.categoryOfShowsOption,
            R.drawable.ic_video_library,
            R.string.add_to_library_category_of_shows,
            R.string.add_to_library_category_of_shows_desc,
        ) { dialog.dismiss(); tagCategoryOfShows(folderKey, folderName, rootOrShareId, selectedBucket.id) }

        bindAddToLibraryOption(
            dialogBinding.folderOfMoviesOption,
            R.drawable.ic_movie,
            R.string.add_to_library_movies,
            R.string.add_to_library_movies_desc,
        ) { dialog.dismiss(); tagFolderOfMovies(folderKey, folderName, rootOrShareId, selectedBucket.id) }

        dialogBinding.skipButton.setOnClickListener { dialog.dismiss() }

        dialog.setOnShowListener {
            val sheet = dialog.findViewById<android.widget.FrameLayout>(com.google.android.material.R.id.design_bottom_sheet)
            sheet?.let {
                it.setBackgroundResource(android.R.color.transparent)
                com.google.android.material.bottomsheet.BottomSheetBehavior.from(it).state =
                    com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
            }
        }
        dialog.show()
    }

    private fun bindAddToLibraryOption(
        binding: com.retrotube.app.databinding.ItemAddToLibraryOptionBinding,
        iconRes: Int,
        titleRes: Int,
        descriptionRes: Int,
        onClick: () -> Unit,
    ) {
        binding.optionIcon.setImageResource(iconRes)
        binding.optionTitle.setText(titleRes)
        binding.optionDescription.setText(descriptionRes)
        binding.optionRoot.setOnClickListener { onClick() }
    }

    private fun tagSingleShow(folderKey: String, folderName: String, rootOrShareId: String, bucketId: String) {
        TaggedFolderRepository(this).tag(folderKey, bucketId, ContentKind.SHOW, rootOrShareId)
        runMatchInBackground(folderName) { engine, _ ->
            val refs = FolderVideoScanner.listVideosRecursively(this, folderKey).map { VideoRef(it.uri.toString(), it.name) }
            engine.matchShowFolder(folderKey, refs, forced = false).found
        }
    }

    private fun tagCategoryOfShows(folderKey: String, folderName: String, rootOrShareId: String, bucketId: String) {
        val taggedFolderRepository = TaggedFolderRepository(this)
        runMatchInBackground(folderName) { engine, overlay ->
            val subfolders = FolderVideoScanner.listSubfolders(this, folderKey)
            if (subfolders.isEmpty()) return@runMatchInBackground false
            for (subfolder in subfolders) {
                taggedFolderRepository.tag(subfolder.folderKey, bucketId, ContentKind.SHOW, rootOrShareId)
            }
            var anyMatched = false
            subfolders.forEachIndexed { index, subfolder ->
                overlay.update(getString(R.string.scraping_progress_format, index + 1, subfolders.size))
                val refs = FolderVideoScanner.listVideosRecursively(this, subfolder.folderKey).map { VideoRef(it.uri.toString(), it.name) }
                if (engine.matchShowFolder(subfolder.folderKey, refs, forced = false).found) anyMatched = true
            }
            anyMatched
        }
    }

    private fun tagFolderOfMovies(folderKey: String, folderName: String, rootOrShareId: String, bucketId: String) {
        TaggedFolderRepository(this).tag(folderKey, bucketId, ContentKind.MOVIE, rootOrShareId)
        runMatchInBackground(folderName) { engine, overlay ->
            var anyMatched = false
            val videos = FolderVideoScanner.listVideosRecursively(this, folderKey)
            videos.forEachIndexed { index, video ->
                overlay.update(getString(R.string.scraping_progress_format, index + 1, videos.size))
                if (engine.matchMovie(VideoRef(video.uri.toString(), video.name), forced = false)) anyMatched = true
            }
            anyMatched
        }
    }

    private fun runMatchInBackground(label: String, block: (MatchingEngine, com.retrotube.app.util.ScrapingProgressOverlay) -> Boolean) {
        val overlay = com.retrotube.app.util.ScrapingProgressOverlay(this)
        overlay.show(getString(R.string.scraping_matching_single, label))
        val engine = MatchingEngine(VideoMetadataRepository(this), FolderMetadataRepository(this))
        val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        executor.execute {
            val matched = runCatching { block(engine, overlay) }.getOrDefault(false)
            handler.post {
                overlay.finish(
                    if (matched) getString(R.string.add_to_library_done, label) else getString(R.string.add_to_library_no_match, label),
                )
                refreshList()
            }
        }
    }
}
