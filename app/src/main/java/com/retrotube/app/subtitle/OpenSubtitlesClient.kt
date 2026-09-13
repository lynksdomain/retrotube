package com.retrotube.app.subtitle

import com.retrotube.app.BuildConfig
import org.json.JSONObject
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class OpenSubtitlesResult(val fileId: Long, val releaseName: String, val language: String, val downloadCount: Int)

/**
 * Thin client for the OpenSubtitles REST API (api.opensubtitles.com/api/v1) --
 * same dependency-light `HttpURLConnection` + `org.json` approach as
 * [com.retrotube.app.metadata.tmdb.TmdbClient], and the same build-time-secret
 * pattern too: the key comes from BuildConfig (local.properties locally, an
 * env var in CI), matching TMDB_API_KEY and matching how the iOS app handles
 * both keys -- not a runtime, in-app-entered setting. User-initiated only
 * (spec §6.4): never called automatically, only from the Audio & Subtitles
 * sheet's "Search Online" action, and only once [isConfigured].
 */
class OpenSubtitlesClient {

    private val apiKey: String = BuildConfig.OPENSUBTITLES_API_KEY

    fun isConfigured(): Boolean = apiKey.isNotBlank()

    fun search(query: String): List<OpenSubtitlesResult> {
        if (!isConfigured()) {
            android.util.Log.w("OpenSubtitlesClient", "search skipped: not configured")
            return emptyList()
        }
        val encoded = URLEncoder.encode(query, "UTF-8")
        android.util.Log.w("OpenSubtitlesClient", "search query=\"$query\" path=/subtitles?query=$encoded&languages=en")
        val json = get("/subtitles?query=$encoded&languages=en")
        android.util.Log.w("OpenSubtitlesClient", "search raw response=$json")
        if (json == null) return emptyList()
        val data = json.optJSONArray("data") ?: return emptyList()
        val out = mutableListOf<OpenSubtitlesResult>()
        for (i in 0 until data.length()) {
            val item = data.optJSONObject(i) ?: continue
            val attributes = item.optJSONObject("attributes") ?: continue
            val files = attributes.optJSONArray("files") ?: continue
            val fileId = files.optJSONObject(0)?.optLong("file_id") ?: continue
            out += OpenSubtitlesResult(
                fileId = fileId,
                releaseName = attributes.optString("release", "Unknown release"),
                language = attributes.optString("language", ""),
                downloadCount = attributes.optInt("download_count", 0),
            )
        }
        android.util.Log.w("OpenSubtitlesClient", "search parsed ${out.size} results: $out")
        return out
    }

    /** Returns the downloaded subtitle file's raw text (SRT/VTT), or null on
     *  any failure -- OpenSubtitles' download endpoint is a two-step redirect
     *  (POST for a temporary link, then GET that link) rather than a direct file. */
    fun download(fileId: Long): String? {
        if (!isConfigured()) {
            android.util.Log.w("OpenSubtitlesClient", "download skipped: not configured")
            return null
        }
        val requestJson = JSONObject().put("file_id", fileId).toString()
        android.util.Log.w("OpenSubtitlesClient", "download POST file_id=$fileId body=$requestJson")
        val connection = URL("$BASE_URL/download").openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.connectTimeout = 8_000
            connection.readTimeout = 8_000
            connection.setRequestProperty("Api-Key", apiKey)
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("User-Agent", "RetroTube v1.1.0")
            (connection.outputStream as OutputStream).use { it.write(requestJson.toByteArray()) }
            val code = connection.responseCode
            android.util.Log.w("OpenSubtitlesClient", "download POST -> $code")
            if (code != HttpURLConnection.HTTP_OK) {
                val errorBody = runCatching { connection.errorStream?.bufferedReader()?.use { it.readText() } }.getOrNull()
                android.util.Log.w("OpenSubtitlesClient", "download error body=$errorBody")
                return null
            }
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            android.util.Log.w("OpenSubtitlesClient", "download response body=$body")
            val link = JSONObject(body).optString("link").ifEmpty {
                android.util.Log.w("OpenSubtitlesClient", "download response had no link")
                return null
            }
            downloadFile(link)
        } catch (e: Exception) {
            android.util.Log.w("OpenSubtitlesClient", "download threw", e)
            null
        } finally {
            connection.disconnect()
        }
    }

    private fun downloadFile(link: String): String? {
        android.util.Log.w("OpenSubtitlesClient", "downloadFile GET $link")
        val connection = URL(link).openConnection() as HttpURLConnection
        return try {
            connection.connectTimeout = 8_000
            connection.readTimeout = 8_000
            val code = connection.responseCode
            android.util.Log.w("OpenSubtitlesClient", "downloadFile GET -> $code")
            if (code != HttpURLConnection.HTTP_OK) {
                val errorBody = runCatching { connection.errorStream?.bufferedReader()?.use { it.readText() } }.getOrNull()
                android.util.Log.w("OpenSubtitlesClient", "downloadFile error body=$errorBody")
                return null
            }
            connection.inputStream.bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            android.util.Log.w("OpenSubtitlesClient", "downloadFile threw", e)
            null
        } finally {
            connection.disconnect()
        }
    }

    private fun get(path: String): JSONObject? {
        val connection = URL("$BASE_URL$path").openConnection() as HttpURLConnection
        return try {
            connection.connectTimeout = 8_000
            connection.readTimeout = 8_000
            connection.requestMethod = "GET"
            connection.setRequestProperty("Api-Key", apiKey)
            connection.setRequestProperty("User-Agent", "RetroTube v1.1.0")
            val code = connection.responseCode
            android.util.Log.w("OpenSubtitlesClient", "GET $BASE_URL$path -> $code")
            if (code != HttpURLConnection.HTTP_OK) {
                val errorBody = runCatching { connection.errorStream?.bufferedReader()?.use { it.readText() } }.getOrNull()
                android.util.Log.w("OpenSubtitlesClient", "error body=$errorBody")
                return null
            }
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            JSONObject(body)
        } catch (e: Exception) {
            android.util.Log.w("OpenSubtitlesClient", "GET $path threw", e)
            null
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        private const val BASE_URL = "https://api.opensubtitles.com/api/v1"
    }
}
