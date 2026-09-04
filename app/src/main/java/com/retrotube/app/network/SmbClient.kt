package com.retrotube.app.network

import jcifs.CIFSContext
import jcifs.config.PropertyConfiguration
import jcifs.context.BaseContext
import jcifs.smb.NtlmPasswordAuthenticator
import jcifs.smb.SmbFile
import java.util.Properties

/**
 * Thin wrapper around jcifs-ng. Every call here does real network I/O and must
 * run off the main thread -- there's no async wrapper at this layer on purpose,
 * callers already have their own background-thread conventions.
 */
object SmbClient {

    fun contextFor(share: NetworkShare): CIFSContext {
        val props = Properties().apply {
            // Modern macOS/Samba speak SMB2/3; SMB1 is legacy and left out entirely.
            setProperty("jcifs.smb.client.minVersion", "SMB202")
            setProperty("jcifs.smb.client.maxVersion", "SMB311")
        }
        val baseContext = BaseContext(PropertyConfiguration(props))
        val auth = NtlmPasswordAuthenticator(share.domain, share.username, share.password)
        return baseContext.withCredentials(auth)
    }

    /** Connects and lists the share root. Throws on any failure (bad host,
     *  bad credentials, share doesn't exist) -- callers surface the message. */
    fun listRoot(share: NetworkShare): List<String> {
        val root = SmbFile(share.rootUrl, contextFor(share))
        return root.listFiles().map { it.name }
    }
}
