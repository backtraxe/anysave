package io.github.backtraxe.anysave

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.os.Bundle
import io.github.backtraxe.anysave.log.AppLog
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import io.github.backtraxe.anysave.download.SaveService
import io.github.backtraxe.anysave.download.SaveStateBus
import io.github.backtraxe.anysave.extractor.PageExtractor
import io.github.backtraxe.anysave.model.ExtractionSnapshot
import io.github.backtraxe.anysave.model.MediaItem
import io.github.backtraxe.anysave.model.PostMedia
import io.github.backtraxe.anysave.model.DESKTOP_CHROME_UA
import io.github.backtraxe.anysave.model.SaveState
import io.github.backtraxe.anysave.reader.DirectExtractor
import io.github.backtraxe.anysave.reader.DirectExtractors
import io.github.backtraxe.anysave.reader.ReaderOverrides
import io.github.backtraxe.anysave.settings.BilibiliQuality
import io.github.backtraxe.anysave.settings.BilibiliQualityConfig
import io.github.backtraxe.anysave.session.SiteLoginCheck
import io.github.backtraxe.anysave.reader.SiteReader
import io.github.backtraxe.anysave.reader.UrlInput
import io.github.backtraxe.anysave.ui.PreviewPanel

/**
 * Shell: paste a link, open it in a real WebView, capture the response the
 * page produced, parse it into a post, preview the media grid and save the
 * selected items into the system gallery.
 */
class MainActivity : Activity() {

    private lateinit var urlInput: EditText
    private lateinit var statusText: TextView
    private lateinit var webView: WebView
    private lateinit var siteLoginCheck: SiteLoginCheck
    private lateinit var extractor: PageExtractor
    private lateinit var preview: PreviewPanel

    private var currentPost: PostMedia? = null

    /**
     * Post a save was started for. Bus states are only painted onto the panel
     * while they belong to the post currently on screen.
     */
    private var savingPostId: String? = null
    private var pendingSaveItems: List<MediaItem>? = null

    private val saveStateListener: (SaveState) -> Unit = { state ->
        val postId = currentPost?.awemeId
        if (state is SaveState.Idle || (postId != null && postId == savingPostId)) {
            preview.renderSaveState(state)
            if (state is SaveState.Finished) {
                statusText.text = getString(R.string.status_saved, state.saved)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        urlInput = findViewById(R.id.urlInput)
        statusText = findViewById(R.id.statusText)
        webView = findViewById(R.id.webView)

        if (BuildConfig.DEBUG) {
            // Never expose the production WebView to DevTools debugging.
            ReaderOverrides.load(this)
        BilibiliQualityConfig.maxHeight = BilibiliQuality.maxHeight(this)

        if (BuildConfig.DEBUG) {
            // Never expose the production WebView to DevTools debugging.
            WebView.setWebContentsDebuggingEnabled(true)
        }
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?,
            ): Boolean {
                val scheme = request?.url?.scheme?.lowercase() ?: return false
                // Keep everything in our WebView; never launch external apps
                // or browsers from custom schemes (bilibili://, intent://, ...).
                return scheme != "http" && scheme != "https"
            }
        }

        siteLoginCheck = SiteLoginCheck(this)
        extractor = PageExtractor(this, webView) { snapshot -> showResult(snapshot) }
        preview = PreviewPanel(this, findViewById(R.id.previewRoot)) { closePreview() }
        preview.setSaveActions(object : PreviewPanel.SaveActions {
            override fun onSaveRequested(items: List<MediaItem>) = requestSave(items)

            override fun onOpenAlbum() = openAlbum()

            override fun onRetryFailed() = retryFailedSave()

            override fun onRetryUpload() = showUploadRetryDialog()
        })
        findViewById<Button>(R.id.extractButton).setOnClickListener { startExtraction() }
        findViewById<View>(R.id.settingsButton).setOnClickListener {
            startActivity(Intent(this, TelegramSettingsActivity::class.java))
        }

        handleExternalOpenUrl(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleExternalOpenUrl(intent)
    }

    /**
     * Opens a page for login (settings → Login). The page is loaded with a
     * desktop UA so the site shows its QR login instead of the mobile app
     * download flow; cookies it sets are shared with all later extractions.
     */
    private fun handleExternalOpenUrl(intent: Intent?) {
        val url = intent?.getStringExtra(EXTRA_OPEN_URL) ?: return
        if (!url.startsWith("https://") && !url.startsWith("http://")) return
        intent.removeExtra(EXTRA_OPEN_URL)
        closePreview()
        hideKeyboard()
        webView.settings.userAgentString = DESKTOP_CHROME_UA
        urlInput.setText(url)
        urlInput.clearFocus()
        statusText.text = getString(R.string.status_login_opened)
        webView.loadUrl(url)
        AppLog.i(TAG, "opened login page $url")
    }

    override fun onStart() {
        super.onStart()
        SaveStateBus.addListener(saveStateListener)
    }

    override fun onStop() {
        SaveStateBus.removeListener(saveStateListener)
        super.onStop()
    }

    private fun startExtraction() {
        val pasted = urlInput.text?.toString()?.trim().orEmpty()
        val url = UrlInput.extract(pasted)
        if (url == null) {
            statusText.text = getString(R.string.status_invalid_url)
            return
        }
        // Normalize what the user sees to the URL we actually use (share text
        // often wraps the link in a sentence).
        if (url != pasted) {
            urlInput.setText(url)
            urlInput.setSelection(url.length)
        }
        val direct = DirectExtractors.forUrl(url)
        if (direct != null) {
            startDirectExtraction(direct, url)
            return
        }
        val reader = SiteReader.forUrl(url)
        if (reader == null) {
            statusText.text = getString(R.string.status_no_reader)
            return
        }
        closePreview()
        hideKeyboard()
        if (::siteLoginCheck.isInitialized) siteLoginCheck.cancel()
        statusText.text = getString(R.string.status_extracting)
        extractor.extract(url, reader)
    }

    /**
     * Sites with a dedicated extractor (YouTube) do not use the WebView; the
     * extractor performs its own network calls on a background thread.
     */
    private fun startDirectExtraction(extractor: DirectExtractor, url: String) {
        closePreview()
        hideKeyboard()
        if (::siteLoginCheck.isInitialized) siteLoginCheck.cancel()
        statusText.text = getString(R.string.status_extracting)
        val startedAt = SystemClock.elapsedRealtime()
        Thread({
            val post = try {
                extractor.extract(url)
            } catch (t: Throwable) {
                AppLog.w(TAG, "direct extractor ${extractor.id} failed", t)
                null
            }
            val elapsed = SystemClock.elapsedRealtime() - startedAt
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (post == null || post.isEmpty) {
                    statusText.text = getString(R.string.status_direct_failed)
                    AppLog.i(TAG, "direct extract failed extractor=${extractor.id}")
                    return@runOnUiThread
                }
                currentPost = post
                savingPostId = null
                statusText.text = getString(R.string.status_parsed, post.items.size, elapsed)
                AppLog.i(
                    TAG,
                    "parsed(direct) id=${post.awemeId} author=${post.author} " +
                        "items=${post.items.size} elapsed=${elapsed}ms",
                )
                webView.onPause()
                preview.show(post)
                preview.renderSaveState(SaveState.Idle)
            }
        }, "anysave-direct-extract").start()
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
                currentPost = post
                savingPostId = null
                statusText.text =
                    getString(R.string.status_parsed, post.items.size, snapshot.elapsedMs)
                AppLog.i(
                    TAG,
                    "parsed aweme=${post.awemeId} author=${post.author} " +
                        "images=${post.imageCount} videos=${post.videoCount} " +
                        "elapsed=${snapshot.elapsedMs}ms",
                )
                webView.onPause()
                hideKeyboard()
                preview.show(post)
                preview.renderSaveState(SaveState.Idle)
            }

            snapshot.hits.isEmpty() -> {
                statusText.text = getString(R.string.status_timeout)
                AppLog.i(TAG, "timeout: no matching response in ${snapshot.elapsedMs}ms")
                diagnoseTimeoutLogin(snapshot.pageUrl)
            }

            else -> {
                statusText.text = getString(R.string.status_parse_failed, snapshot.hits.size)
                AppLog.i(
                    TAG,
                    "unparsed: ${snapshot.hits.size} response(s), " +
                        "patched=${snapshot.patched}, elapsed=${snapshot.elapsedMs}ms",
                )
                snapshot.hits.forEach {
                    AppLog.i(TAG, "hit ${it.via} status=${it.status} len=${it.length} ${it.url}")
                }
                diagnoseTimeoutLogin(snapshot.pageUrl)
            }
        }
    }

    // ------------------------------------------------------------------ saving

    private fun requestSave(items: List<MediaItem>) {
        if (items.isEmpty() || currentPost == null) return
        if (SaveStateBus.state() is SaveState.Running) return
        pendingSaveItems = items
        val missing = missingSavePermissions()
        if (missing.isEmpty()) {
            startSave(items)
        } else {
            requestPermissions(missing.toTypedArray(), REQUEST_SAVE_PERMISSIONS)
        }
    }

    private fun startSave(items: List<MediaItem>) {
        val post = currentPost ?: return
        savingPostId = post.awemeId
        statusText.text = getString(R.string.status_saving, items.size)
        SaveService.start(this, post, items)
    }

    private fun missingSavePermissions(): List<String> {
        val missing = ArrayList<String>(2)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            missing += Manifest.permission.POST_NOTIFICATIONS
        }
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
            checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            missing += Manifest.permission.WRITE_EXTERNAL_STORAGE
        }
        return missing
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_SAVE_PERMISSIONS) return
        val items = pendingSaveItems ?: return
        pendingSaveItems = null
        // Saving still works without the notification permission; the progress
        // notification simply stays hidden. Storage permission is only needed on
        // API <= 28, where the write below would fail loudly if denied.
        startSave(items)
    }

    /**
     * A timeout often means "not logged in" (douyin) or a site change. Probe
     * the current site's session and replace the generic timeout text with a
     * concrete next step.
     */
    private fun diagnoseTimeoutLogin(pageUrl: String) {
        // Prefer the page the extraction actually ended on (redirects included);
        // fall back to the input field for cases without a navigated page.
        val url = pageUrl.ifBlank { urlInput.text?.toString()?.trim().orEmpty() }
        val readerId = SiteReader.forUrl(url)?.id ?: return
        val label = when (readerId) {
            "douyin" -> getString(R.string.login_douyin)
            "bilibili" -> getString(R.string.login_bilibili)
            "xiaohongshu" -> getString(R.string.login_xiaohongshu)
            else -> return
        }
        val siteId = readerId
        statusText.text = getString(R.string.status_login_checking)
        siteLoginCheck.check(siteId) { result ->
            if (isFinishing || isDestroyed) return@check
            statusText.text = when (result?.loggedIn) {
                true -> getString(R.string.status_login_logged_in, label)
                false -> getString(R.string.status_login_logged_out, label)
                else -> getString(R.string.status_timeout)
            }
        }
    }

    private fun showUploadRetryDialog() {
        val names = SaveService.lastUploadFailureNames()
        if (names.isEmpty()) {
            statusText.text = getString(R.string.status_nothing_to_retry)
            return
        }
        val listed = names.take(MAX_UPLOAD_RETRY_NAMES).joinToString("\n")
        val message = if (names.size > MAX_UPLOAD_RETRY_NAMES) "$listed\n…" else listed
        android.app.AlertDialog.Builder(this)
            .setTitle(getString(R.string.upload_retry_title, names.size))
            .setMessage(message)
            .setPositiveButton(R.string.upload_retry_action) { _, _ ->
                if (SaveService.retryLastUpload(this)) {
                    statusText.text = getString(R.string.status_retrying_upload)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun retryFailedSave() {
        if (SaveService.retryLastFailed(this)) {
            statusText.text = getString(R.string.status_retrying_failed)
        } else {
            statusText.text = getString(R.string.status_nothing_to_retry)
        }
    }

    private fun openAlbum() {
        val state = SaveStateBus.state() as? SaveState.Finished ?: return
        val uri = state.firstUri?.let(Uri::parse) ?: return
        val mime = contentResolver.getType(uri) ?: "*/*"
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivity(intent)
        } catch (t: Throwable) {
            AppLog.w(TAG, "no gallery app for $uri", t)
            Toast.makeText(this, R.string.album_open_failed, Toast.LENGTH_SHORT).show()
        }
    }

    private fun closePreview() {
        if (::preview.isInitialized && preview.isShowing) {
            preview.hide()
            webView.onResume()
        }
    }

    override fun onResume() {
        super.onResume()
        // Settings may have changed while the preview/settings screen was open.
        BilibiliQualityConfig.maxHeight = BilibiliQuality.maxHeight(this)
    }

    override fun onDestroy() {
        if (::siteLoginCheck.isInitialized) siteLoginCheck.cancel()
        extractor.release()
        webView.destroy()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "AnySave"
        private const val REQUEST_SAVE_PERMISSIONS = 41
        private const val MAX_UPLOAD_RETRY_NAMES = 12
        const val EXTRA_OPEN_URL = "io.github.backtraxe.anysave.extra.OPEN_URL"
    }
}
