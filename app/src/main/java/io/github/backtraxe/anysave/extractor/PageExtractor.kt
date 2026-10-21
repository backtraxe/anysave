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
 * 3. poll window.__anysaveHits until a matching response arrives or we time out
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
    private var generation = 0
    private var startedAt = 0L

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

        val origin = originOf(url) ?: reader.origin
        if (installedOrigins.add(origin)) {
            val patterns = JSONArray(reader.responsePatterns).toString()
            val script = "window.__anysavePatterns=$patterns;\n$hookScript"
            WebViewCompat.addDocumentStartJavaScript(webView, script, setOf(origin))
            Log.i(TAG, "reader=${reader.id} hook installed for $origin")
        }

        generation += 1
        startedAt = SystemClock.elapsedRealtime()
        webView.loadUrl(url)
        handler.postDelayed({ poll(generation) }, FIRST_POLL_DELAY_MS)
    }

    fun release() {
        generation += 1
        handler.removeCallbacksAndMessages(null)
    }

    private fun poll(gen: Int) {
        if (gen != generation) return
        webView.evaluateJavascript(STATUS_JS) { raw ->
            if (gen != generation) return@evaluateJavascript
            val snapshot = parse(raw)
            val elapsed = SystemClock.elapsedRealtime() - startedAt
            if (snapshot == null) {
                handler.postDelayed({ poll(gen) }, POLL_INTERVAL_MS)
                return@evaluateJavascript
            }
            if (snapshot.hits.isNotEmpty() || elapsed >= TIMEOUT_MS) {
                onFinished(snapshot.copy(elapsedMs = elapsed))
            } else {
                handler.postDelayed({ poll(gen) }, POLL_INTERVAL_MS)
            }
        }
    }

    private fun parse(raw: String?): ExtractionSnapshot? {
        if (raw.isNullOrBlank() || raw == "null") return null
        return try {
            val payload = JSONTokener(raw).nextValue() as? String ?: return null
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

        private val STATUS_JS = """
            (function () {
              try {
                var hits = window.__anysaveHits || [];
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
    }
}
