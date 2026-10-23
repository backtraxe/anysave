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
import io.github.backtraxe.anysave.model.MediaKind
import io.github.backtraxe.anysave.model.PostMedia
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Downloads one media URL into the system gallery.
 *
 * The URL is the one the page itself received (CDN signed), so this is a plain
 * GET with the headers the page's CDN expects — no API replay, no signature
 * computation. Session cookies, when present for that host, are read from the
 * WebView's own [CookieManager] in-process (never exported to disk).
 *
 * Three save shapes:
 * - image / single-file video: stream straight into MediaStore
 * - bilibili DASH ([MediaItem.audioUrl] set): download the video and audio
 *   tracks to cache, mux them with [DashMuxer], then copy one MP4 into MediaStore
 * - primary URL failure falls back to [MediaItem.fallbackUrl]
 *
 * After the bytes arrive the container is sniffed, so the MediaStore name + MIME
 * type are honest even when the URL lied about the extension.
 */
class MediaSaver(context: Context) {

    private val appContext = context.applicationContext
    private val resolver = appContext.contentResolver
    private val cacheRoot = File(appContext.cacheDir, "anysave-save")

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
            val result = if (item.kind == MediaKind.VIDEO && item.audioUrl.isNotBlank()) {
                saveMuxed(post, item, target, mediaUrl)
            } else {
                saveSingle(post, item, target, mediaUrl)
            }
            if (result != null) return result
            if (attempt < candidates.lastIndex) {
                Log.w(TAG, "primary media url failed, trying fallback for ${target.displayName}")
            }
        }
        return null
    }

    // ------------------------------------------------------------- single asset

    private fun saveSingle(
        post: PostMedia,
        item: MediaItem,
        target: MediaNaming.Target,
        mediaUrl: String,
    ): SavedFile? {
        var lastError: Throwable? = null
        for (attempt in 0 until MAX_ATTEMPTS) {
            val uri = insertPending(target) ?: return null
            try {
                val output = resolver.openOutputStream(uri)
                    ?: throw IllegalStateException("no output stream for $uri")
                val downloaded = output.use {
                    downloadToStream(
                        url = mediaUrl,
                        referer = post.site.referer,
                        userAgent = post.site.downloadUserAgent ?: DEFAULT_USER_AGENT,
                        destination = it,
                    )
                }
                if (downloaded.bytes <= 0L) throw IllegalStateException("empty response body")
                val finalTarget = correctContainer(uri, target, item, downloaded.header)
                publish(uri)
                Log.i(TAG, "saved ${finalTarget.displayName} (${downloaded.bytes}B) -> $uri")
                return SavedFile(uri = uri, bytes = downloaded.bytes)
            } catch (t: Throwable) {
                deleteQuietly(uri)
                lastError = t
                if (!isRetryable(t) || attempt == MAX_ATTEMPTS - 1) break
                Log.w(
                    TAG,
                    "download attempt ${attempt + 1} failed for ${target.displayName}: " +
                        "${t.message}; retrying",
                )
                sleepBackoff(attempt)
            }
        }
        Log.w(TAG, "download failed for ${target.displayName}: ${lastError?.message}")
        return null
    }

    // --------------------------------------------------------------- dash mux

    private fun saveMuxed(
        post: PostMedia,
        item: MediaItem,
        target: MediaNaming.Target,
        videoUrl: String,
    ): SavedFile? {
        val uri = insertPending(target) ?: return null
        val work = File(cacheRoot, "mux-${System.nanoTime()}").apply { mkdirs() }
        val videoFile = File(work, "video.m4s")
        val audioFile = File(work, "audio.m4s")
        val outputFile = File(work, "output.mp4")
        return try {
            val downloadUa = post.site.downloadUserAgent ?: DEFAULT_USER_AGENT
            val videoBytes = downloadToFile(videoUrl, videoFile, post.site.referer, downloadUa)
            val audioBytes = downloadToFile(item.audioUrl, audioFile, post.site.referer, downloadUa)
            if (videoBytes <= 0L || audioBytes <= 0L) {
                throw IllegalStateException("empty dash track (v=$videoBytes a=$audioBytes)")
            }
            if (!DashMuxer.mux(videoFile, audioFile, outputFile)) {
                throw IllegalStateException("dash mux failed")
            }
            val bytes = outputFile.length()
            if (bytes <= 0L) throw IllegalStateException("empty muxed output")
            val out = resolver.openOutputStream(uri)
                ?: throw IllegalStateException("no output stream for $uri")
            out.use { sink ->
                outputFile.inputStream().use { source -> source.copyTo(sink, BUFFER_SIZE) }
            }
            val finalTarget = correctContainer(uri, target, item, readHeader(outputFile))
            publish(uri)
            Log.i(
                TAG,
                "muxed ${finalTarget.displayName} (v=${videoBytes}B a=${audioBytes}B " +
                    "-> ${bytes}B) -> $uri",
            )
            SavedFile(uri = uri, bytes = bytes)
        } catch (t: Throwable) {
            Log.w(TAG, "muxed save failed for ${target.displayName}: ${t.message}")
            deleteQuietly(uri)
            null
        } finally {
            work.deleteRecursively()
        }
    }

    // ------------------------------------------------------------- mediasstore

    private fun insertPending(target: MediaNaming.Target): Uri? {
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
        return try {
            resolver.insert(collection, values)
        } catch (t: Throwable) {
            Log.w(TAG, "media insert failed for ${target.displayName}", t)
            null
        }
    }

    private fun publish(uri: Uri) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val published = ContentValues().apply {
                put(MediaStore.MediaColumns.IS_PENDING, 0)
            }
            resolver.update(uri, published, null, null)
        }
    }

    private fun deleteQuietly(uri: Uri) {
        try {
            resolver.delete(uri, null, null)
        } catch (cleanup: Throwable) {
            Log.w(TAG, "failed to clean up pending row", cleanup)
        }
    }

    /** Sniffs the actual bytes and fixes the name/MIME if the extension was wrong. */
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

    // ----------------------------------------------------------------- network

    private data class DownloadResult(val bytes: Long, val header: ByteArray)

    private fun downloadToFile(
        url: String,
        file: File,
        referer: String,
        userAgent: String,
    ): Long {
        var lastError: Throwable? = null
        for (attempt in 0 until MAX_ATTEMPTS) {
            try {
                return FileOutputStream(file).use { out ->
                    downloadToStream(url, referer, userAgent, out).bytes
                }
            } catch (t: Throwable) {
                lastError = t
                if (!isRetryable(t) || attempt == MAX_ATTEMPTS - 1) throw t
                Log.w(
                    TAG,
                    "track download attempt ${attempt + 1} failed: ${t.message}; retrying",
                )
                sleepBackoff(attempt)
            }
        }
        throw lastError ?: java.io.IOException("download failed")
    }

    private fun downloadToStream(
        url: String,
        referer: String,
        userAgent: String,
        destination: OutputStream,
    ): DownloadResult {
        var connection: HttpURLConnection? = null
        try {
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                instanceFollowRedirects = true
                requestMethod = "GET"
                setRequestProperty("User-Agent", userAgent)
                if (referer.isNotBlank()) setRequestProperty("Referer", referer)
                setRequestProperty("Accept", "*/*")
                // Avoid transparent gzip: it makes contentLengthLong disagree
                // with the byte count and looks like a truncated body.
                setRequestProperty("Accept-Encoding", "identity")
                cookieFor(url)?.let { setRequestProperty("Cookie", it) }
            }
            val status = connection.responseCode
            if (status !in 200..299) throw HttpStatusException(status)
            val expected = connection.contentLengthLong
            var total = 0L
            val header = ByteArray(HEADER_SIZE)

            connection.inputStream.use { rawInput: InputStream ->
                val input = BufferedInputStream(rawInput, BUFFER_SIZE)
                val output = BufferedOutputStream(destination, BUFFER_SIZE)
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

    private class HttpStatusException(val code: Int) : java.io.IOException("HTTP $code")

    /** 5xx/429/408 and plain IO/timeout/truncated bodies are worth retrying. */
    private fun isRetryable(t: Throwable): Boolean {
        if (t is HttpStatusException) {
            return t.code == 408 || t.code == 425 || t.code == 429 || t.code >= 500
        }
        if (t is java.io.IOException) return true
        val message = t.message.orEmpty()
        return message.contains("truncated", ignoreCase = true) ||
            message.contains("empty response", ignoreCase = true)
    }

    private fun sleepBackoff(attempt: Int) {
        try {
            Thread.sleep((attempt + 1) * 500L)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    private fun readHeader(file: File): ByteArray {
        val header = ByteArray(HEADER_SIZE)
        return try {
            file.inputStream().use { it.read(header) }
            header
        } catch (t: Throwable) {
            header
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
        private const val MAX_ATTEMPTS = 3
        private const val DEFAULT_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14; Pixel 7) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
    }
}
