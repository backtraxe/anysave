package io.github.backtraxe.anysave.reader

import android.content.Context
import io.github.backtraxe.anysave.log.AppLog
import io.github.backtraxe.anysave.BuildConfig
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Fetches, verifies and persists a remote reader pack.
 *
 * The URL is configured by the user (Settings → Reader updates). Release builds
 * require HTTPS; debug builds also allow http so the flow can be exercised
 * against a local mock server.
 */
class ReaderPackRepository(context: Context) {

    private val appContext = context.applicationContext
    private val prefs =
        appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    data class UpdateResult(
        val ok: Boolean,
        val message: String,
        val appliedCount: Int = 0,
    )

    fun savedUrl(): String = prefs.getString(KEY_URL, "").orEmpty()

    fun saveUrl(url: String) {
        prefs.edit().putString(KEY_URL, url.trim()).apply()
    }

    /** Removes the applied pack and its saved URL. */
    fun clear(): UpdateResult {
        ReaderOverrides.clear(appContext)
        prefs.edit().remove(KEY_URL).apply()
        return UpdateResult(true, "pack removed", appliedCount = 0)
    }

    /** Blocking network call; run it on a background thread. */
    fun refresh(url: String): UpdateResult {
        val trimmed = url.trim()
        if (trimmed.isBlank()) return UpdateResult(false, "empty URL")
        val scheme = try {
            URL(trimmed).protocol?.lowercase()
        } catch (t: Throwable) {
            null
        }
        val allowed = scheme == "https" || (BuildConfig.DEBUG && scheme == "http")
        if (scheme == null || !allowed) {
            return UpdateResult(false, "URL must be https")
        }

        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(trimmed).openConnection() as HttpURLConnection).apply {
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                requestMethod = "GET"
                setRequestProperty("User-Agent", USER_AGENT)
                setRequestProperty("Accept", "application/json")
            }
            val status = connection.responseCode
            if (status !in 200..299) {
                UpdateResult(false, "HTTP $status")
            } else {
                val bytes = connection.inputStream.use { readCapped(it) }
                    ?: return UpdateResult(false, "pack too large")
                val packJson = String(bytes, Charsets.UTF_8)
                val applied = ReaderOverrides.apply(appContext, packJson)
                saveUrl(trimmed)
                UpdateResult(true, "$applied reader(s) updated", applied)
            }
        } catch (t: Throwable) {
            AppLog.w(TAG, "reader pack refresh failed: ${t.message}")
            UpdateResult(false, t.message ?: t.javaClass.simpleName)
        } finally {
            connection?.disconnect()
        }
    }

    private fun readCapped(input: InputStream): ByteArray? {
        val out = java.io.ByteArrayOutputStream(16 * 1024)
        val buffer = ByteArray(16 * 1024)
        var total = 0
        while (true) {
            val read = input.read(buffer)
            if (read == -1) break
            total += read
            if (total > ReaderPackParser.MAX_PACK_BYTES) return null
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }

    private companion object {
        private const val TAG = "AnySave"
        private const val PREFS = "anysave_reader_pack"
        private const val KEY_URL = "pack_url"
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val READ_TIMEOUT_MS = 20_000
        private const val USER_AGENT = "AnySave-reader-pack/1"
    }
}
