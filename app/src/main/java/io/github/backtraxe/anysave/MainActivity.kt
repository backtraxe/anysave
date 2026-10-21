package io.github.backtraxe.anysave

import android.app.Activity
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import android.webkit.WebView
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import io.github.backtraxe.anysave.extractor.PageExtractor
import io.github.backtraxe.anysave.model.ExtractionSnapshot
import io.github.backtraxe.anysave.reader.SiteReader

/**
 * M0 shell: paste a link, open it in a real WebView, capture the responses
 * that carry the media data. Preview / selection / download come next.
 */
class MainActivity : Activity() {

    private lateinit var urlInput: EditText
    private lateinit var statusText: TextView
    private lateinit var webView: WebView
    private lateinit var extractor: PageExtractor

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        urlInput = findViewById(R.id.urlInput)
        statusText = findViewById(R.id.statusText)
        webView = findViewById(R.id.webView)

        WebView.setWebContentsDebuggingEnabled(true)

        extractor = PageExtractor(this, webView) { snapshot -> showResult(snapshot) }
        findViewById<Button>(R.id.extractButton).setOnClickListener { startExtraction() }
    }

    private fun startExtraction() {
        val url = urlInput.text?.toString()?.trim().orEmpty()
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            statusText.text = getString(R.string.status_invalid_url)
            return
        }
        val reader = SiteReader.forUrl(url)
        if (reader == null) {
            statusText.text = getString(R.string.status_no_reader)
            return
        }
        statusText.text = getString(R.string.status_extracting)
        extractor.extract(url, reader)
    }

    private fun showResult(snapshot: ExtractionSnapshot) {
        statusText.text = if (snapshot.hits.isEmpty()) {
            getString(R.string.status_timeout)
        } else {
            getString(R.string.status_hits, snapshot.hits.size, snapshot.elapsedMs)
        }
        Log.i(
            TAG,
            "title=${snapshot.title} hits=${snapshot.hits.size} " +
                "patched=${snapshot.patched} elapsed=${snapshot.elapsedMs}ms",
        )
        snapshot.hits.forEach {
            Log.i(TAG, "hit ${it.via} status=${it.status} len=${it.length} ${it.url}")
        }
    }

    override fun onDestroy() {
        extractor.release()
        webView.destroy()
        super.onDestroy()
    }

    private companion object {
        private const val TAG = "AnySave"
    }
}
