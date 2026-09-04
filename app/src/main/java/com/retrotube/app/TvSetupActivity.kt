package com.retrotube.app

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.retrotube.app.collections.CollectionRepository
import com.retrotube.app.databinding.ActivityTvSetupBinding
import com.retrotube.app.library.LibraryRepository
import com.retrotube.app.network.NetworkShareRepository
import com.retrotube.app.tv.TvAutoSeeder
import com.retrotube.app.tv.TvChannelConfigRepository

/**
 * Runs once, the very first time TV Mode is opened: pick a starting point for
 * the user's own channel setup, since no fixed auto-derivation logic can fit
 * every library. "Auto" just pre-fills the same editable channel list "Build
 * my own" starts empty with -- both land on [TvChannelEditorActivity] next,
 * and neither is a one-time-only choice; the editor stays reachable to keep
 * curating channels afterward.
 */
class TvSetupActivity : AppCompatActivity() {

    private lateinit var binding: ActivityTvSetupBinding
    private lateinit var configRepository: TvChannelConfigRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTvSetupBinding.inflate(layoutInflater)
        setContentView(binding.root)
        configRepository = TvChannelConfigRepository(this)

        binding.setupAutoButton.setOnClickListener {
            val seeded = TvAutoSeeder.buildSeedChannels(
                LibraryRepository(this),
                CollectionRepository(this),
                NetworkShareRepository(this),
            )
            configRepository.saveChannels(seeded)
            openEditor()
        }
        binding.setupManualButton.setOnClickListener {
            configRepository.saveChannels(emptyList())
            openEditor()
        }
    }

    private fun openEditor() {
        startActivity(Intent(this, TvChannelEditorActivity::class.java))
        finish()
    }
}
