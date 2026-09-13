package com.retrotube.app

import android.content.Context
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Bundle
import android.text.format.Formatter
import androidx.appcompat.app.AppCompatActivity
import com.retrotube.app.databinding.ActivityWifiImportBinding
import com.retrotube.app.library.LibraryRepository
import com.retrotube.app.wifiimport.WifiImportServer
import java.io.File
import com.retrotube.app.util.applyTopBarInset

/** Local HTTP server + upload page for bulk file transfer onto the device
 *  over WiFi (spec §7) -- the received folder is registered as a normal
 *  library root the moment the server starts, so imported files show up in
 *  the Library tab without any extra step once they land. */
class WifiImportActivity : AppCompatActivity() {

    private lateinit var binding: ActivityWifiImportBinding
    private var server: WifiImportServer? = null
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val received = mutableListOf<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityWifiImportBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.root.applyTopBarInset()

        binding.backButton.setOnClickListener { finish() }
        binding.toggleServerButton.setOnClickListener {
            if (server == null) startServer() else stopServer()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopServer()
    }

    private fun startServer() {
        val importDir = File(filesDir, "wifi_import")
        val newServer = WifiImportServer(importDir) { filename ->
            mainHandler.post {
                received.add(0, filename)
                binding.receivedText.text = received.joinToString("\n") { getString(R.string.wifi_import_received, it) }
            }
        }
        server = newServer
        newServer.start()
        LibraryRepository(this).addInternalFolder(Uri.fromFile(importDir))

        val ip = localIpAddress()
        binding.urlText.text = if (ip != null) "http://$ip:${newServer.port}" else getString(R.string.wifi_import_not_on_wifi)
        binding.pinText.text = getString(R.string.wifi_import_pin_label, newServer.pin)
        binding.toggleServerButton.setText(R.string.wifi_import_stop)
    }

    private fun stopServer() {
        server?.stop()
        server = null
        binding.urlText.text = ""
        binding.pinText.text = ""
        binding.toggleServerButton.setText(R.string.wifi_import_start)
    }

    private fun localIpAddress(): String? {
        val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return null
        val ipInt = wifiManager.connectionInfo?.ipAddress ?: return null
        if (ipInt == 0) return null
        return Formatter.formatIpAddress(ipInt)
    }
}
