package io.github.backtraxe.anysave.log

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import io.github.backtraxe.anysave.BuildConfig
import io.github.backtraxe.anysave.R
import java.util.Locale

/**
 * In-app diagnostics log: the bounded history from [AppLog].
 *
 * Meant for "why did this save/upload fail" questions: the same lines the app
 * writes to logcat, with Copy/Share so they can be sent along with a report.
 */
class LogActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var logText: TextView
    private lateinit var logScroll: ScrollView
    private lateinit var logInfo: TextView

    private var lastSize = -1
    private var firstLoad = true
    private var atBottom = true

    private val refresh = object : Runnable {
        override fun run() {
            render()
            handler.postDelayed(this, REFRESH_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_log)

        logText = findViewById(R.id.logText)
        logScroll = findViewById(R.id.logScroll)
        logInfo = findViewById(R.id.logInfo)
        logInfo.text = buildInfo()

        findViewById<View>(R.id.logBack).setOnClickListener { finish() }
        findViewById<View>(R.id.logCopy).setOnClickListener { copy() }
        findViewById<View>(R.id.logShare).setOnClickListener { share() }
        findViewById<View>(R.id.logClear).setOnClickListener {
            AppLog.clear()
            render(force = true)
        }

        logScroll.setOnScrollChangeListener { _, _, _, _, _ ->
            atBottom = isAtBottom()
        }
        render(force = true)
    }

    override fun onResume() {
        super.onResume()
        handler.removeCallbacks(refresh)
        handler.post(refresh)
    }

    override fun onPause() {
        handler.removeCallbacks(refresh)
        super.onPause()
    }

    private fun render(force: Boolean = false) {
        val lines = AppLog.lines()
        if (!force && lines.size == lastSize) return
        lastSize = lines.size
        logText.text = if (lines.isEmpty()) {
            getString(R.string.log_empty)
        } else {
            lines.joinToString("\n")
        }
        if (firstLoad || atBottom) {
            logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
            firstLoad = false
        }
    }

    private fun isAtBottom(): Boolean {
        val child = logScroll.getChildAt(0) ?: return true
        return child.bottom - (logScroll.scrollY + logScroll.height) <= dp(8)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun copy() {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(getString(R.string.log_title), payload()))
        Toast.makeText(this, R.string.log_copied, Toast.LENGTH_SHORT).show()
    }

    private fun share() {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, getString(R.string.log_title))
            putExtra(Intent.EXTRA_TEXT, payload())
        }
        startActivity(Intent.createChooser(intent, getString(R.string.log_share)))
    }

    private fun payload(): String {
        val lines = AppLog.lines()
        return buildString {
            appendLine(buildInfo())
            appendLine()
            if (lines.isEmpty()) {
                append(getString(R.string.log_empty))
            } else {
                append(lines.joinToString("\n"))
            }
        }
    }

    private fun buildInfo(): String = buildString {
        append("AnySave ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        append(" · Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
        append(" · ${Build.MANUFACTURER} ${Build.MODEL}")
        append(" · ${Locale.getDefault()}")
        append(" · ${AppLog.lines().size}/${AppLog.MAX_LINES} lines")
    }

    private companion object {
        private const val REFRESH_MS = 1_000L
    }
}
