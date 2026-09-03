package com.retrotube.app.network

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.util.UUID

data class NetworkShare(
    val id: String,
    val displayName: String,
    val host: String,
    val shareName: String,
    val username: String,
    val password: String,
    /** Windows domain/workgroup -- blank for a plain macOS/Samba share, which is
     *  the common case this was actually built and tested against. */
    val domain: String = "",
) {
    /** smb://host/share/ -- the root every browse/playback call starts from. */
    val rootUrl: String get() = "smb://$host/$shareName/"
}

/**
 * Saved SMB connections. Credentials are the whole reason this isn't just more
 * SharedPreferences: an SMB password sitting in cleartext on disk is a real
 * finding waiting to happen, so this is backed by EncryptedSharedPreferences
 * (AES-256-GCM, key held in the Android Keystore) instead.
 */
class NetworkShareRepository(context: Context) {

    private val prefs = run {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "retrotube_network_shares",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    fun getAll(): List<NetworkShare> = idsInOrder().mapNotNull { get(it) }

    fun get(id: String): NetworkShare? {
        val host = prefs.getString(key(id, "host"), null) ?: return null
        return NetworkShare(
            id = id,
            displayName = prefs.getString(key(id, "name"), null) ?: host,
            host = host,
            shareName = prefs.getString(key(id, "share"), null) ?: return null,
            username = prefs.getString(key(id, "user"), null).orEmpty(),
            password = prefs.getString(key(id, "pass"), null).orEmpty(),
            domain = prefs.getString(key(id, "domain"), null).orEmpty(),
        )
    }

    fun add(share: NetworkShare): String {
        val id = share.id.ifBlank { UUID.randomUUID().toString() }
        val ids = idsInOrder() + id
        prefs.edit()
            .putString(idsKey(), ids.joinToString(","))
            .putString(key(id, "name"), share.displayName)
            .putString(key(id, "host"), share.host)
            .putString(key(id, "share"), share.shareName)
            .putString(key(id, "user"), share.username)
            .putString(key(id, "pass"), share.password)
            .putString(key(id, "domain"), share.domain)
            .apply()
        return id
    }

    fun delete(id: String) {
        val ids = idsInOrder().filterNot { it == id }
        prefs.edit()
            .putString(idsKey(), ids.joinToString(","))
            .remove(key(id, "name"))
            .remove(key(id, "host"))
            .remove(key(id, "share"))
            .remove(key(id, "user"))
            .remove(key(id, "pass"))
            .remove(key(id, "domain"))
            .apply()
    }

    private fun idsInOrder(): List<String> =
        prefs.getString(idsKey(), null)?.split(",")?.filter { it.isNotBlank() } ?: emptyList()

    private fun idsKey() = "share_ids"
    private fun key(id: String, field: String) = "share_${id}_$field"
}
