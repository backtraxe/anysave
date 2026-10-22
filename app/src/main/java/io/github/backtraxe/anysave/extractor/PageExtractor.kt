package io.github.backtraxe.anysave.extractor

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.webkit.WebView
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import io.github.backtraxe.anysave.R
import io.github.backtraxe.anysave.model.ExtractionSnapshot
import io.github.backtraxe.anysave.model.MediaHit
import io.github.backtraxe.anysave.model.PostMedia
import io.github.backtraxe.anysave.reader.SiteReader
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/**
 * Owns the WebView extraction loop for one screen.
 *
 * Flow:
 * 1. register the hook as a document-start script for the reader's origin
 * 2. load the page and let the site's own JavaScript run (and sign its requests)
 * 3. poll window.__anysaveHits (metadata only) until a response arrives or we time out
 * 4. pull the captured body once, hand it to the reader's parser
 * 5. report back a snapshot carrying either a parsed post or a diagnostic
 *
 * The extractor never builds network requests and never computes signatures;
 * it only observes responses the page already produced.
 */
@SuppressLint("SetJavaScriptEnabled")
class PageExtractor(
    context: Context,
    private val webView: WebView,
    private val onFinished: (ExtractionSnapshot) -> Unit,
) {
    private val appContext = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private val installedOrigins = mutableSetOf<String>()
    private val hookScript: String = readRaw(R.raw.hook)
    private val defaultUserAgent: String = webView.settings.userAgentString
    private var generation = 0
    private var startedAt = 0L
    private var reader: SiteReader? = null
    private var finished = false
    private var lastEmbeddedAttemptAt = 0L

    init {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
        }
    }

    fun extract(url: String, reader: SiteReader) {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            Log.e(TAG, "DOCUMENT_START_SCRIPT is not supported by this WebView")
            onFinished(ExtractionSnapshot(url, "", false, emptyList(), 0L))
            return
        }

        // Some sites abort Android-WebView navigations; readers may pin a page UA.
        webView.settings.userAgentString = reader.pageUserAgent ?: defaultUserAgent

        val origin = originOf(url) ?: reader.origin
        if (installedOrigins.add(origin)) {
            val patterns = JSONArray(reader.responsePatterns).toString()
            val script = "window.__anysavePatterns=$patterns;\n$hookScript"
            WebViewCompat.addDocumentStartJavaScript(webView, script, setOf(origin))
            Log.i(TAG, "reader=${reader.id} hook installed for $origin")
        }

        generation += 1
        finished = false
        lastEmbeddedAttemptAt = 0L
        this.reader = reader
        startedAt = SystemClock.elapsedRealtime()
        // Drop anything captured on the previous page before navigating.
        webView.evaluateJavascript(RESET_JS, null)
        webView.loadUrl(url)
        handler.postDelayed({ poll(generation) }, FIRST_POLL_DELAY_MS)
    }

    fun release() {
        generation += 1
        handler.removeCallbacksAndMessages(null)
        webView.settings.userAgentString = defaultUserAgent
    }

    private fun poll(gen: Int) {
        if (gen != generation) return
        webView.evaluateJavascript(STATUS_JS) { raw ->
            if (gen != generation) return@evaluateJavascript
            val snapshot = parseStatus(raw)
            if (snapshot == null) {
                handler.postDelayed({ poll(gen) }, POLL_INTERVAL_MS)
                return@evaluateJavascript
            }
            val elapsed = SystemClock.elapsedRealtime() - startedAt
            val currentReader = reader
            when {
                snapshot.hits.isNotEmpty() -> captureBody(gen, snapshot)
                elapsed >= TIMEOUT_MS -> finish(snapshot.copy(elapsedMs = elapsed))
                currentReader?.embeddedPayloadScript != null &&
                    embeddedDue(currentReader, elapsed) ->
                    readEmbeddedPayload(gen, snapshot, currentReader)

                else -> handler.postDelayed({ poll(gen) }, POLL_INTERVAL_MS)
            }
        }
    }

    /** Response bodies are only transferred once a hit was observed: polling stays cheap. */
    private fun captureBody(gen: Int, snapshot: ExtractionSnapshot) {
        webView.evaluateJavascript(CAPTURE_JS) { raw ->
            if (gen != generation) return@evaluateJavascript
            val post = parsePost(raw, snapshot.pageUrl)
            val elapsed = SystemClock.elapsedRealtime() - startedAt
            Log.i(
                TAG,
                "captured ${snapshot.hits.size} response(s) in ${elapsed}ms, post=${post?.items?.size}",
            )
            finish(snapshot.copy(elapsedMs = elapsed, post = post))
        }
    }

    /**
     * Read-only fallback for server-rendered pages that never issue the
     * observed response: ask the reader for a payload already embedded in the
     * page (douyin note pages embed their detail in RSC flight data).
     */
    private fun readEmbeddedPayload(
        gen: Int,
        snapshot: ExtractionSnapshot,
        reader: SiteReader,
    ) {
        lastEmbeddedAttemptAt = SystemClock.elapsedRealtime()
        val script = reader.embeddedPayloadScript ?: return
        webView.evaluateJavascript(script) { raw ->
            if (gen != generation) return@evaluateJavascript
            val payload = jsonString(raw)?.takeIf { it.isNotBlank() }
            val pageUrl = snapshot.pageUrl.ifBlank { webView.url.orEmpty() }
            val post = if (payload == null) {
                Log.i(TAG, "no embedded payload for ${reader.id} (${pageUrl})")
                null
            } else {
                Log.i(TAG, "embedded payload found: ${payload.length} chars")
                try {
                    reader.parse(payload, pageUrl)
                } catch (t: Throwable) {
                    Log.w(TAG, "reader ${reader.id} failed on embedded payload", t)
                    null
                }
            }
            if (post != null && !post.isEmpty) {
                val elapsed = SystemClock.elapsedRealtime() - startedAt
                Log.i(TAG, "embedded parse ok in ${elapsed}ms, items=${post.items.size}")
                finish(snapshot.copy(elapsedMs = elapsed, post = post))
            } else {
                handler.postDelayed({ poll(gen) }, POLL_INTERVAL_MS)
            }
        }
    }

    private fun finish(snapshot: ExtractionSnapshot) {
        if (finished) return
        finished = true
        webView.settings.userAgentString = defaultUserAgent
        onFinished(snapshot)
    }

    private fun parsePost(raw: String?, pageUrl: String): PostMedia? {
        val reader = reader ?: return null
        val payload = jsonString(raw) ?: return null
        val array = try {
            JSONArray(payload)
        } catch (t: Throwable) {
            Log.w(TAG, "failed to parse captured bodies", t)
            return null
        }
        for (i in 0 until array.length()) {
            val entry = array.optJSONObject(i) ?: continue
            val body = entry.optString("body")
            if (body.isEmpty()) continue
            val post = try {
                reader.parse(body, pageUrl)
            } catch (t: Throwable) {
                Log.w(TAG, "reader ${reader.id} failed on captured body", t)
                null
            }
            if (post != null && !post.isEmpty) return post
        }
        return null
    }

    private fun parseStatus(raw: String?): ExtractionSnapshot? {
        val payload = jsonString(raw) ?: return null
        return try {
            val obj = JSONObject(payload)
            val array = obj.optJSONArray("hits") ?: JSONArray()
            val hits = ArrayList<MediaHit>(array.length())
            for (i in 0 until array.length()) {
                val hit = array.optJSONObject(i) ?: continue
                hits += MediaHit(
                    via = hit.optString("via"),
                    url = hit.optString("url"),
                    status = hit.optInt("status"),
                    length = hit.optInt("len"),
                )
            }
            ExtractionSnapshot(
                pageUrl = obj.optString("url"),
                title = obj.optString("title"),
                patched = obj.optBoolean("patched"),
                hits = hits,
                elapsedMs = 0L,
            )
        } catch (t: Throwable) {
            Log.w(TAG, "failed to parse extraction snapshot", t)
            null
        }
    }

    /**
     * Embedded SSR data is the primary source for readers with no observed
     * network response (xiaohongshu notes), and a fallback for the ones that do
     * (douyin notes). Retry while the page is still hydrating instead of
     * trying exactly once.
     */
    private fun embeddedDue(reader: SiteReader, elapsed: Long): Boolean {
        val sinceLast = SystemClock.elapsedRealtime() - lastEmbeddedAttemptAt
        return if (reader.responsePatterns.isEmpty()) {
            elapsed >= EMBEDDED_PRIMARY_AFTER_MS && sinceLast >= EMBEDDED_RETRY_MS
        } else {
            elapsed >= EMBEDDED_FALLBACK_AFTER_MS && sinceLast >= EMBEDDED_FALLBACK_RETRY_MS
        }
    }

    private fun jsonString(raw: String?): String? {
        if (raw.isNullOrBlank() || raw == "null") return null
        return try {
            JSONTokener(raw).nextValue() as? String
        } catch (t: Throwable) {
            Log.w(TAG, "failed to decode JS result", t)
            null
        }
    }

    private fun originOf(url: String): String? {
        val uri = Uri.parse(url)
        val scheme = uri.scheme?.lowercase() ?: return null
        val host = uri.host ?: return null
        if (scheme != "http" && scheme != "https") return null
        val port = uri.port
        return if (port == -1) "$scheme://$host" else "$scheme://$host:$port"
    }

    private fun readRaw(resId: Int): String =
        appContext.resources.openRawResource(resId).bufferedReader().use { it.readText() }

    private companion object {
        private const val TAG = "AnySave"
        private const val FIRST_POLL_DELAY_MS = 800L
        private const val POLL_INTERVAL_MS = 600L
        private const val TIMEOUT_MS = 25_000L

        /** Give the page's own request a head start before reading embedded data. */
        private const val EMBEDDED_FALLBACK_AFTER_MS = 9_000L

        /** SSE-only readers: read embedded data as soon as the page is parsed. */
        private const val EMBEDDED_PRIMARY_AFTER_MS = 2_000L
        private const val EMBEDDED_RETRY_MS = 1_200L
        private const val EMBEDDED_FALLBACK_RETRY_MS = 2_000L

        private val RESET_JS = """
            (function () {
              window.__anysaveHits = [];
              window.__anysaveSeen = {};
            })()
        """.trimIndent()

        /** Metadata only: keeps the 600 ms poll cheap even before a body arrives. */
        private val STATUS_JS = """
            (function () {
              try {
                var source = window.__anysaveHits || [];
                var hits = [];
                for (var i = 0; i < source.length; i++) {
                  var h = source[i];
                  hits.push({ via: h.via, url: h.url, status: h.status, len: h.len });
                }
                return JSON.stringify({
                  title: document.title || '',
                  url: location.href || '',
                  patched: !String(window.fetch).includes('[native code]'),
                  hits: hits,
                  error: window.__anysaveHookError || null
                });
              } catch (e) {
                return JSON.stringify({
                  title: '', url: '', patched: false, hits: [], error: String(e)
                });
              }
            })()
        """.trimIndent()

        /** One-shot body pull, after a hit was seen. Total transfer is capped. */
        private val CAPTURE_JS = """
            (function () {
              try {
                var source = window.__anysaveHits || [];
                var out = [];
                var total = 0;
                for (var i = 0; i < source.length; i++) {
                  var body = source[i].body || '';
                  if (!body) continue;
                  if (total + body.length > 6000000) break;
                  out.push({ url: source[i].url, status: source[i].status, body: body });
                  total += body.length;
                }
                return JSON.stringify(out);
              } catch (e) {
                return JSON.stringify([]);
              }
            })()
        """.trimIndent()
    }
}
