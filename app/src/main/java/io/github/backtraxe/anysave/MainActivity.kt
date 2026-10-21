package io.github.backtraxe.anysave

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.webkit.WebView
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
import io.github.backtraxe.anysave.model.SaveState
import io.github.backtraxe.anysave.reader.SiteReader
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
            WebView.setWebContentsDebuggingEnabled(true)
        }

        extractor = PageExtractor(this, webView) { snapshot -> showResult(snapshot) }
        preview = PreviewPanel(this, findViewById(R.id.previewRoot)) { closePreview() }
        preview.setSaveActions(object : PreviewPanel.SaveActions {
            override fun onSaveRequested(items: List<MediaItem>) = requestSave(items)

            override fun onOpenAlbum() = openAlbum()
        })
        findViewById<Button>(R.id.extractButton).setOnClickListener { startExtraction() }
        findViewById<View>(R.id.settingsButton).setOnClickListener {
            startActivity(Intent(this, TelegramSettingsActivity::class.java))
        }
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
                currentPost = post
                savingPostId = null
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
                preview.renderSaveState(SaveState.Idle)
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
            Log.w(TAG, "no gallery app for $uri", t)
            Toast.makeText(this, R.string.album_open_failed, Toast.LENGTH_SHORT).show()
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
        private const val REQUEST_SAVE_PERMISSIONS = 41
    }
}
