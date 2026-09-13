package com.retrotube.app.wifiimport

import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import kotlin.random.Random

/**
 * A minimal local HTTP server for WiFi Import (spec §7): serves a one-page
 * upload form gated by a random PIN, and writes whatever's posted into
 * [importDir]. Hand-rolled on plain `ServerSocket`/multipart parsing rather
 * than pulling in NanoHTTPD, matching this app's existing dependency-light
 * approach elsewhere (its own SMB client usage, its own GL shaders). Not
 * hardened against anything beyond casual same-network use -- there's no
 * TLS, and the PIN is the only gate -- acceptable for "a phone on the same
 * WiFi sending a few files," not a general-purpose file server.
 */
class WifiImportServer(private val importDir: File, private val onFileReceived: (String) -> Unit) {

    val pin: String = Random.nextInt(1000, 10000).toString()
    var port: Int = 0
        private set

    private var serverSocket: ServerSocket? = null
    private val executor = Executors.newCachedThreadPool()
    @Volatile private var running = false

    fun start() {
        importDir.mkdirs()
        val socket = ServerSocket(0)
        serverSocket = socket
        port = socket.localPort
        running = true
        executor.execute {
            while (running) {
                val client = try {
                    socket.accept()
                } catch (e: Exception) {
                    break
                }
                executor.execute { handleClient(client) }
            }
        }
    }

    fun stop() {
        running = false
        runCatching { serverSocket?.close() }
        executor.shutdownNow()
    }

    private fun handleClient(socket: Socket) {
        socket.use {
            val input = it.getInputStream()
            val reader = BufferedReader(InputStreamReader(input))
            val requestLine = reader.readLine() ?: return
            val headers = mutableMapOf<String, String>()
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) break
                val parts = line.split(":", limit = 2)
                if (parts.size == 2) headers[parts[0].trim().lowercase()] = parts[1].trim()
            }

            val requestedPin = requestLine.substringAfter("pin=", "").substringBefore(" ").substringBefore("&")
            val output = it.getOutputStream()

            when {
                requestLine.startsWith("GET") -> {
                    writeResponse(output, "text/html", pageHtml())
                }
                requestLine.startsWith("POST") && requestedPin == pin -> {
                    val contentLength = headers["content-length"]?.toIntOrNull() ?: 0
                    val contentType = headers["content-type"].orEmpty()
                    val boundary = contentType.substringAfter("boundary=", "").ifEmpty { null }
                    val bodyBytes = ByteArray(contentLength)
                    var read = 0
                    while (read < contentLength) {
                        val n = input.read(bodyBytes, read, contentLength - read)
                        if (n < 0) break
                        read += n
                    }
                    if (boundary != null) {
                        saveUploadedFiles(bodyBytes, boundary)
                    }
                    writeResponse(output, "text/html", "<html><body>Upload received. You can close this page.</body></html>")
                }
                else -> {
                    writeResponse(output, "text/plain", "Forbidden", code = "403 Forbidden")
                }
            }
        }
    }

    /** Hand-rolled multipart/form-data split -- good enough for "a handful of
     *  files from a phone's share sheet," not a general MIME parser. */
    private fun saveUploadedFiles(body: ByteArray, boundary: String) {
        val boundaryBytes = "--$boundary".toByteArray()
        val parts = splitOn(body, boundaryBytes)
        for (part in parts) {
            val headerEnd = indexOf(part, "\r\n\r\n".toByteArray())
            if (headerEnd < 0) continue
            val headerText = String(part, 0, headerEnd, Charsets.ISO_8859_1)
            val filename = Regex("filename=\"([^\"]*)\"").find(headerText)?.groupValues?.get(1)
            if (filename.isNullOrBlank()) continue
            val contentStart = headerEnd + 4
            var contentEnd = part.size
            // Trailing CRLF before the next boundary marker.
            if (contentEnd >= 2 && part[contentEnd - 1] == '\n'.code.toByte() && part[contentEnd - 2] == '\r'.code.toByte()) {
                contentEnd -= 2
            }
            if (contentStart >= contentEnd) continue
            val fileBytes = part.copyOfRange(contentStart, contentEnd)
            val safeName = filename.replace(Regex("[/\\\\]"), "_")
            File(importDir, safeName).writeBytes(fileBytes)
            onFileReceived(safeName)
        }
    }

    private fun splitOn(data: ByteArray, delimiter: ByteArray): List<ByteArray> {
        val out = mutableListOf<ByteArray>()
        var start = 0
        while (true) {
            val index = indexOf(data, delimiter, start)
            if (index < 0) break
            if (start > 0) {
                val chunk = data.copyOfRange(start, index)
                if (chunk.isNotEmpty()) out += chunk
            }
            start = index + delimiter.size
        }
        return out
    }

    private fun indexOf(data: ByteArray, pattern: ByteArray, from: Int = 0): Int {
        outer@ for (i in from..(data.size - pattern.size)) {
            for (j in pattern.indices) {
                if (data[i + j] != pattern[j]) continue@outer
            }
            return i
        }
        return -1
    }

    private fun writeResponse(output: java.io.OutputStream, contentType: String, body: String, code: String = "200 OK") {
        val bytes = body.toByteArray()
        val header = "HTTP/1.1 $code\r\nContent-Type: $contentType\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
        output.write(header.toByteArray())
        output.write(bytes)
        output.flush()
    }

    private fun pageHtml(): String = """
        <html><head><title>RetroTube WiFi Import</title></head>
        <body style="font-family:sans-serif;background:#0B0B14;color:#F2F2F5;padding:24px;">
        <h2>RetroTube WiFi Import</h2>
        <form method="POST" action="/?pin=$pin" enctype="multipart/form-data">
        <input type="file" name="files" multiple><br><br>
        <button type="submit">Upload</button>
        </form>
        </body></html>
    """.trimIndent()
}
