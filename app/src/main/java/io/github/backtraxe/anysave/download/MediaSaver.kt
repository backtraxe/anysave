package io.github.backtraxe.anysave.download

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.webkit.CookieManager
import io.github.backtraxe.anysave.model.MediaItem
import io.github.backtraxe.anysave.model.PostMedia
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Downloads one media URL into the system gallery.
 *
 * The URL is the one the page itself received (CDN signed, no-watermark variant),
 * so this is a plain GET with the headers the page's CDN expects — no API replay,
 * no signature computation. Session cookies, when present for that host, are read
 * from the WebView's own [CookieManager] in-process (never exported to disk).
 *
 * A reader may provide a [MediaItem.fallbackUrl] (e.g. the site's display copy).
 * If the primary URL fails, the fallback is tried before the item is marked failed.
 * After the bytes arrive the container is sniffed, so the MediaStore name + MIME
 * type are honest even when the URL lied about the extension.
 *
 * Placement:
 * - images -> `Pictures/AnySave`, video -> `Movies/AnySave`
 * - API 29+: MediaStore with RELATIVE_PATH + IS_PENDING
 * - API 26-28: MediaStore with an explicit DATA path (needs WRITE_EXTERNAL_STORAGE)
 */
class MediaSaver(context: Context) {

    private val resolver = context.applicationContext.contentResolver

    /** Result of one successful save. */
    data class SavedFile(val uri: Uri, val bytes: Long)

    /** @return the inserted file, or null when every URL attempt failed. */
    fun save(post: PostMedia, item: MediaItem): SavedFile? {
        val candidates = listOf(item.url, item.fallbackUrl)
            .filter { it.isNotBlank() }
            .distinct()
        if (candidates.isEmpty()) return null

        for ((attempt, mediaUrl) in candidates.withIndex()) {
            val target = MediaNaming.targetFor(post, item, mediaUrl)
            val result = saveOne(post, item, target, mediaUrl)
            if (result != null) return result
            if (attempt < candidates.lastIndex) {
                Log.w(TAG, "primary media url failed, trying fallback for ${target.displayName}")
            }
        }
        return null
    }

    private fun saveOne(
        post: PostMedia,
        item: MediaItem,
        target: MediaNaming.Target,
        mediaUrl: String,
    ): SavedFile? {
        val collection = if (target.isVideo) {
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        } else {
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, target.displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, target.mimeType)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, target.relativeDir)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            } else {
                val publicDir = Environment.getExternalStoragePublicDirectory(
                    if (target.isVideo) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_PICTURES,
                )
                val dir = File(publicDir, MediaNaming.ALBUM)
                if (!dir.exists() && !dir.mkdirs()) {
                    Log.w(TAG, "cannot create ${dir.absolutePath}")
                    return null
                }
                put(MediaStore.MediaColumns.DATA, File(dir, target.displayName).absolutePath)
            }
        }

        val uri = try {
            resolver.insert(collection, values)
        } catch (t: Throwable) {
            Log.w(TAG, "media insert failed for ${target.displayName}", t)
            null
        } ?: return null

        return try {
            val downloaded = downloadInto(mediaUrl, uri, post.site.referer)
            if (downloaded.bytes <= 0L) throw IllegalStateException("empty response body")
            val finalTarget = correctContainer(uri, target, item, downloaded.header)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val published = ContentValues().apply {
                    put(MediaStore.MediaColumns.IS_PENDING, 0)
                }
                resolver.update(uri, published, null, null)
            }
            Log.i(TAG, "saved ${finalTarget.displayName} (${downloaded.bytes}B) -> $uri")
            SavedFile(uri = uri, bytes = downloaded.bytes)
        } catch (t: Throwable) {
            Log.w(TAG, "download failed for ${target.displayName}: ${t.message}")
            try {
                resolver.delete(uri, null, null)
            } catch (cleanup: Throwable) {
                Log.w(TAG, "failed to clean up pending row", cleanup)
            }
            null
        }
    }

    /**
     * Sniffs the actual bytes and fixes the MediaStore name/MIME if the URL
     * extension was missing or wrong (common for xiaohongshu originals).
     */
    private fun correctContainer(
        uri: Uri,
        target: MediaNaming.Target,
        item: MediaItem,
        header: ByteArray,
    ): MediaNaming.Target {
        val detected = MediaSniffer.detect(header) ?: return target
        if (detected == target.extension) return target
        val mime = MediaNaming.mimeOf(detected, item.kind)
        val base = target.displayName.substringBeforeLast('.', target.displayName)
        val fixed = target.copy(displayName = "$base.$detected", mimeType = mime)
        try {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fixed.displayName)
                put(MediaStore.MediaColumns.MIME_TYPE, fixed.mimeType)
            }
            resolver.update(uri, values, null, null)
            Log.i(TAG, "container corrected ${target.displayName} -> ${fixed.displayName}")
        } catch (t: Throwable) {
            Log.w(TAG, "cannot correct media metadata: ${t.message}")
        }
        return fixed
    }

    private data class DownloadResult(val bytes: Long, val header: ByteArray)

    private fun downloadInto(url: String, uri: Uri, referer: String): DownloadResult {
        var connection: HttpURLConnection? = null
        try {
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                instanceFollowRedirects = true
                requestMethod = "GET"
                setRequestProperty("User-Agent", USER_AGENT)
                if (referer.isNotBlank()) setRequestProperty("Referer", referer)
                setRequestProperty("Accept", "*/*")
                cookieFor(url)?.let { setRequestProperty("Cookie", it) }
            }
            val status = connection.responseCode
            if (status !in 200..299) throw IllegalStateException("HTTP $status")
            val expected = connection.contentLengthLong
            var total = 0L
            val header = ByteArray(HEADER_SIZE)

            connection.inputStream.use { rawInput: InputStream ->
                val input = BufferedInputStream(rawInput, BUFFER_SIZE)
                val rawOutput = resolver.openOutputStream(uri)
                    ?: throw IllegalStateException("no output stream for $uri")
                val output = BufferedOutputStream(rawOutput, BUFFER_SIZE)
                try {
                    val buffer = ByteArray(BUFFER_SIZE)
                    var headerFilled = 0
                    while (true) {
                        val read = input.read(buffer)
                        if (read == -1) break
                        if (headerFilled < HEADER_SIZE) {
                            val copy = minOf(read, HEADER_SIZE - headerFilled)
                            System.arraycopy(buffer, 0, header, headerFilled, copy)
                            headerFilled += copy
                        }
                        output.write(buffer, 0, read)
                        total += read
                    }
                    output.flush()
                } finally {
                    output.close()
                }
            }

            if (expected > 0 && total != expected) {
                throw IllegalStateException("truncated body $total/$expected bytes")
            }
            return DownloadResult(bytes = total, header = header)
        } finally {
            connection?.disconnect()
        }
    }

    /** In-process WebView session cookies for the CDN host; no cookie files. */
    private fun cookieFor(url: String): String? = try {
        CookieManager.getInstance().getCookie(url)?.takeIf { it.isNotBlank() }
    } catch (t: Throwable) {
        Log.w(TAG, "cookie lookup failed: ${t.message}")
        null
    }

    private companion object {
        private const val TAG = "AnySave"
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val READ_TIMEOUT_MS = 120_000
        private const val BUFFER_SIZE = 64 * 1024
        private const val HEADER_SIZE = 16
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14; Pixel 7) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
    }
}
