package com.retrotube.app

import android.content.DialogInterface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.retrotube.app.databinding.ActivityNetworkSharesBinding
import com.retrotube.app.databinding.DialogAddNetworkShareBinding
import com.retrotube.app.databinding.ItemNetworkShareRowBinding
import com.retrotube.app.network.NetworkShare
import com.retrotube.app.network.NetworkShareRepository
import com.retrotube.app.network.SmbClient
import java.util.concurrent.Executors

/**
 * Manage saved SMB connections. Adding one actually attempts to connect and
 * list the share root before saving it -- a share that was never reachable
 * in the first place isn't worth remembering, and this is the one place in
 * the app where "save" can mean "silently broken" if we don't verify first.
 */
class NetworkSharesActivity : AppCompatActivity() {

    private lateinit var binding: ActivityNetworkSharesBinding
    private lateinit var repository: NetworkShareRepository
    private val ioExecutor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityNetworkSharesBinding.inflate(layoutInflater)
        setContentView(binding.root)

        repository = NetworkShareRepository(this)

        binding.backButton.setOnClickListener { finish() }
        binding.addShareButton.setOnClickListener { showAddShareDialog() }

        refreshList()
    }

    private fun refreshList() {
        val shares = repository.getAll()
        binding.noSharesText.visibility = if (shares.isEmpty()) View.VISIBLE else View.GONE

        // Clear out any previously-inflated rows (everything after the "no shares" label).
        while (binding.sharesContainer.childCount > 1) {
            binding.sharesContainer.removeViewAt(1)
        }

        for (share in shares) {
            val rowBinding = ItemNetworkShareRowBinding.inflate(layoutInflater, binding.sharesContainer, false)
            rowBinding.shareRowName.text = share.displayName
            rowBinding.shareRowDetail.text = "${share.host} / ${share.shareName}"
            rowBinding.shareRowRemove.setOnClickListener { confirmRemove(share) }
            binding.sharesContainer.addView(rowBinding.root)
        }
    }

    private fun confirmRemove(share: NetworkShare) {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.remove_share_title, share.displayName))
            .setMessage(R.string.remove_share_message)
            .setPositiveButton(R.string.remove) { _, _ ->
                repository.delete(share.id)
                refreshList()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showAddShareDialog() {
        val dialogBinding = DialogAddNetworkShareBinding.inflate(LayoutInflater.from(this))

        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.add_network_share)
            .setView(dialogBinding.root)
            .setPositiveButton(R.string.connect, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create()

        dialog.setOnShowListener {
            // Overriding the button's own listener (instead of the builder's) is what
            // lets a failed connection attempt keep the dialog open for another try,
            // rather than the default "any button tap dismisses" behavior.
            dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener {
                attemptConnection(dialogBinding, dialog)
            }
        }
        dialog.show()
    }

    private fun attemptConnection(dialogBinding: DialogAddNetworkShareBinding, dialog: AlertDialog) {
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
            id = "",
            displayName = displayName.ifEmpty { host },
            host = host,
            shareName = shareName,
            username = username,
            password = password,
        )

        dialog.getButton(DialogInterface.BUTTON_POSITIVE).isEnabled = false
        showStatus(dialogBinding, getString(R.string.connecting))

        ioExecutor.execute {
            val result = runCatching { SmbClient.listRoot(share) }
            mainHandler.post {
                dialog.getButton(DialogInterface.BUTTON_POSITIVE).isEnabled = true
                result.onSuccess { entries ->
                    showStatus(dialogBinding, getString(R.string.connection_succeeded, entries.size))
                    repository.add(share)
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
}
