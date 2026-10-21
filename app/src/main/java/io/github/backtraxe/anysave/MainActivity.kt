package io.github.backtraxe.anysave

import android.app.Activity
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.webkit.WebView
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import io.github.backtraxe.anysave.extractor.PageExtractor
import io.github.backtraxe.anysave.model.ExtractionSnapshot
import io.github.backtraxe.anysave.reader.SiteReader
import io.github.backtraxe.anysave.ui.PreviewPanel

/**
 * Shell: paste a link, open it in a real WebView, capture the response the
 * page produced, parse it into a post and preview the media grid.
 *
 * M1 is preview only: selection is kept in the preview panel, downloading
 * arrives in M2.
 */
class MainActivity : Activity() {

    private lateinit var urlInput: EditText
    private lateinit var statusText: TextView
    private lateinit var webView: WebView
    private lateinit var extractor: PageExtractor
    private lateinit var preview: PreviewPanel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        urlInput = findViewById(R.id.urlInput)
        statusText = findViewById(R.id.statusText)
        webView = findViewById(R.id.webView)

        WebView.setWebContentsDebuggingEnabled(true)

        extractor = PageExtractor(this, webView) { snapshot -> showResult(snapshot) }
        preview = PreviewPanel(this, findViewById(R.id.previewRoot)) { closePreview() }
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
        closePreview()
        hideKeyboard()
        statusText.text = getString(R.string.status_extracting)
        extractor.extract(url, reader)
    }

    private fun hideKeyboard() {
        val imm = getSystemService(InputMethodManager::class.java)
        imm?.hideSoftInputFromWindow(urlInput.windowToken, 0)
        urlInput.clearFocus()
    }

    private fun showResult(snapshot: ExtractionSnapshot) {
        val post = snapshot.post
        when {
            post != null -> {
                statusText.text =
                    getString(R.string.status_parsed, post.items.size, snapshot.elapsedMs)
                Log.i(
                    TAG,
                    "parsed aweme=${post.awemeId} author=${post.author} " +
                        "images=${post.imageCount} videos=${post.videoCount} " +
                        "elapsed=${snapshot.elapsedMs}ms",
                )
                webView.onPause()
                hideKeyboard()
                preview.show(post)
            }

            snapshot.hits.isEmpty() -> {
                statusText.text = getString(R.string.status_timeout)
                Log.i(TAG, "timeout: no matching response in ${snapshot.elapsedMs}ms")
            }

            else -> {
                statusText.text = getString(R.string.status_parse_failed, snapshot.hits.size)
                Log.i(
                    TAG,
                    "unparsed: ${snapshot.hits.size} response(s), " +
                        "patched=${snapshot.patched}, elapsed=${snapshot.elapsedMs}ms",
                )
                snapshot.hits.forEach {
                    Log.i(TAG, "hit ${it.via} status=${it.status} len=${it.length} ${it.url}")
                }
            }
        }
    }

    private fun closePreview() {
        if (::preview.isInitialized && preview.isShowing) {
            preview.hide()
            webView.onResume()
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
