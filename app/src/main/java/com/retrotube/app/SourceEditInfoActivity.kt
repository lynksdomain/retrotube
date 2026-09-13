package com.retrotube.app

import android.graphics.Bitmap
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.retrotube.app.databinding.ActivitySourceEditInfoBinding
import com.retrotube.app.library.ImageUtils
import com.retrotube.app.metadata.FolderMetadataRepository
import com.retrotube.app.util.applyTopBarInset

/**
 * Title + poster override for a raw source folder (local or SMB) -- reuses
 * [FolderMetadataRepository], which already keys purely off a folder key and
 * doesn't care whether that folder happens to be tagged into a bucket yet.
 * Deliberately simpler than [MovieEditInfoActivity]: a source folder has no
 * TMDB identity of its own to search for, just a display override.
 */
class SourceEditInfoActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_FOLDER_KEY = "folder_key"
        const val EXTRA_FOLDER_NAME = "folder_name"
    }

    private lateinit var binding: ActivitySourceEditInfoBinding
    private lateinit var folderMetadataRepository: FolderMetadataRepository
    private lateinit var folderKey: String
    private var selectedPoster: Bitmap? = null

    private val pickPhoto = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@registerForActivityResult
        val bitmap = ImageUtils.decodeSampledBitmap(this, uri) ?: return@registerForActivityResult
        selectedPoster = bitmap
        binding.posterPreview.setImageBitmap(bitmap)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySourceEditInfoBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.root.applyTopBarInset()

        folderKey = intent.getStringExtra(EXTRA_FOLDER_KEY) ?: run { finish(); return }
        val rawName = intent.getStringExtra(EXTRA_FOLDER_NAME).orEmpty()
        folderMetadataRepository = FolderMetadataRepository(this)

        binding.titleInput.setText(folderMetadataRepository.getCustomTitle(folderKey) ?: rawName)
        folderMetadataRepository.getPoster(folderKey)?.let { binding.posterPreview.setImageBitmap(it) }

        binding.cancelButton.setOnClickListener { finish() }
        binding.saveButton.setOnClickListener { save() }
        binding.choosePosterButton.setOnClickListener { pickPhoto.launch("image/*") }
    }

    private fun save() {
        val title = binding.titleInput.text?.toString()?.trim().orEmpty()
        if (title.isNotEmpty()) {
            folderMetadataRepository.setCustomTitle(folderKey, title)
        }
        selectedPoster?.let { folderMetadataRepository.setPoster(folderKey, it) }
        finish()
    }
}
