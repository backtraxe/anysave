package io.github.backtraxe.anysave.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import io.github.backtraxe.anysave.model.MediaSite
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/**
 * Hand-rolled thumbnail loader (no image library dependency).
 *
 * - [LruCache] keyed by normalized url + requested size, so grid cells and the
 *   full-screen viewer can ask for different resolutions of the same asset.
 * - Four daemon worker threads using [HttpURLConnection] with the douyin
 *   `Referer` and a mobile Chrome UA; CDN images reject requests without them.
 * - Two-pass [BitmapFactory] decode with `inSampleSize`, a download byte cap and
 *   a decoded-edge cap so a 50 MP image can never OOM the app.
 * - Results are always delivered on the main thread. Callers pass a per-bind
 *   token and ignore results whose token no longer matches the view (tag-based
 *   cancellation on RecyclerView reuse).
 */
object ThumbnailLoader {

    private const val CONNECT_TIMEOUT_MS = 8_000
    private const val READ_TIMEOUT_MS = 10_000
    private const val MAX_DOWNLOAD_BYTES = 12 * 1024 * 1024
    private const val MAX_DECODED_EDGE_PX = 2_048
    private const val MAX_CACHE_BYTES = 24L * 1024 * 1024
    private const val CACHE_HEAP_DIVISOR = 8
    private const val WORKER_COUNT = 4
    private const val MAX_FAILED_ENTRIES = 200

    private const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14; Pixel 7) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

    private val mainHandler = Handler(Looper.getMainLooper())

    private val workers = Executors.newFixedThreadPool(WORKER_COUNT) { runnable ->
        Thread(runnable, "anysave-thumb").apply { isDaemon = true }
    }

    private val cache = object : LruCache<String, Bitmap>(cacheSizeBytes()) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    /** url size key -> callbacks waiting for one in-flight decode. */
    private val inFlight = ConcurrentHashMap<String, MutableList<(Bitmap?) -> Unit>>()

    /** Failures are cached too, so scrolling does not retry dead URLs. */
    private val failed = ConcurrentHashMap.newKeySet<String>()

    /**
     * Loads [rawUrl] decoded close to [targetWidth] x [targetHeight] pixels.
     * [callback] receives `null` when the url is unusable or the download and
     * decode both fail. Always called on the main thread.
     */
    fun load(
        rawUrl: String,
        targetWidth: Int,
        targetHeight: Int,
        callback: (Bitmap?) -> Unit,
    ) {
        val url = normalizeUrl(rawUrl)
        if (url == null) {
            callback(null)
            return
        }
        val width = targetWidth.coerceIn(1, MAX_DECODED_EDGE_PX)
        val height = targetHeight.coerceIn(1, MAX_DECODED_EDGE_PX)
        val key = cacheKey(url, width, height)

        cache.get(key)?.let {
            callback(it)
            return
        }
        if (failed.contains(url) || failed.contains(key)) {
            callback(null)
            return
        }

        val waiting = inFlight[key]
        if (waiting != null) {
            waiting.add(callback)
            return
        }
        val listeners = CopyOnWriteArrayList<(Bitmap?) -> Unit>().apply { add(callback) }
        if (inFlight.putIfAbsent(key, listeners) != null) {
            // Another thread registered first; join its waiter list.
            inFlight[key]?.add(callback)
            return
        }
        workers.execute { decodeAndDeliver(url, key, width, height, listeners) }
    }

    /**
     * Protocol-relative urls are common in captured JSON (`//p3-pc-sign...`).
     * They must be upgraded to https explicitly; unknown schemes are rejected.
     */
    fun normalizeUrl(rawUrl: String): String? {
        val trimmed = rawUrl.trim()
        if (trimmed.isEmpty()) return null
        val url = when {
            trimmed.startsWith("//") -> "https:$trimmed"
            trimmed.startsWith("https://") -> trimmed
            trimmed.startsWith("http://") -> trimmed
            else -> return null
        }
        return try {
            URL(url)
            url
        } catch (t: Throwable) {
            null
        }
    }

    private fun decodeAndDeliver(
        url: String,
        key: String,
        width: Int,
        height: Int,
        listeners: MutableList<(Bitmap?) -> Unit>,
    ) {
        val bitmap = try {
            downloadBitmap(url, width, height)
        } catch (t: Throwable) {
            null
        }
        mainHandler.post {
            if (bitmap != null) {
                cache.put(key, bitmap)
            } else {
                if (failed.size >= MAX_FAILED_ENTRIES) failed.clear()
                failed.add(key)
                failed.add(url)
            }
            inFlight.remove(key)
            listeners.forEach { it(bitmap) }
        }
    }

    private fun downloadBitmap(url: String, targetWidth: Int, targetHeight: Int): Bitmap? {
        var connection: HttpURLConnection? = null
        try {
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                instanceFollowRedirects = true
                requestMethod = "GET"
                setRequestProperty("User-Agent", USER_AGENT)
                setRequestProperty("Referer", MediaSite.refererForMediaUrl(url))
                setRequestProperty("Accept", "image/*,*/*;q=0.8")
                setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
            }
            if (connection.responseCode !in 200..299) return null
            val bytes = connection.inputStream.use { readCapped(it) } ?: return null
            return decodeSampled(bytes, targetWidth, targetHeight)
        } catch (t: Throwable) {
            return null
        } finally {
            connection?.disconnect()
        }
    }

    private fun readCapped(input: InputStream): ByteArray? {
        val output = ByteArrayOutputStream(64 * 1024)
        val buffer = ByteArray(16 * 1024)
        var total = 0
        while (true) {
            val read = input.read(buffer)
            if (read == -1) break
            total += read
            if (total > MAX_DOWNLOAD_BYTES) return null
            output.write(buffer, 0, read)
        }
        return if (total == 0) null else output.toByteArray()
    }

    private fun decodeSampled(bytes: ByteArray, targetWidth: Int, targetHeight: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sampleSize = 1
        while (bounds.outWidth / (sampleSize * 2) >= targetWidth &&
            bounds.outHeight / (sampleSize * 2) >= targetHeight
        ) {
            sampleSize *= 2
        }

        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = try {
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        } catch (oom: OutOfMemoryError) {
            cache.evictAll()
            try {
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
            } catch (t: Throwable) {
                null
            }
        } ?: return null

        if (decoded.width <= MAX_DECODED_EDGE_PX && decoded.height <= MAX_DECODED_EDGE_PX) {
            return decoded
        }
        return scaleDown(decoded)
    }

    private fun scaleDown(source: Bitmap): Bitmap? {
        val scale = MAX_DECODED_EDGE_PX.toFloat() / maxOf(source.width, source.height)
        val width = (source.width * scale).toInt().coerceAtLeast(1)
        val height = (source.height * scale).toInt().coerceAtLeast(1)
        return try {
            val scaled = Bitmap.createScaledBitmap(source, width, height, true)
            if (scaled !== source) source.recycle()
            scaled
        } catch (t: Throwable) {
            null
        }
    }

    private fun cacheKey(url: String, width: Int, height: Int) = "$url@${width}x$height"

    private fun cacheSizeBytes(): Int {
        val heap = Runtime.getRuntime().maxMemory()
        val target = minOf(MAX_CACHE_BYTES, maxOf(heap / CACHE_HEAP_DIVISOR, 4L * 1024 * 1024))
        return target.toInt()
    }
}
