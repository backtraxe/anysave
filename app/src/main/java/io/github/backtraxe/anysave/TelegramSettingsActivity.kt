package io.github.backtraxe.anysave

import android.app.Activity
import android.content.ClipboardManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.Switch
import android.widget.TextView
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import io.github.backtraxe.anysave.model.DESKTOP_CHROME_UA
import io.github.backtraxe.anysave.reader.ReaderPackRepository
import io.github.backtraxe.anysave.log.LogActivity
import io.github.backtraxe.anysave.session.LoginSignals
import io.github.backtraxe.anysave.settings.BilibiliQuality
import io.github.backtraxe.anysave.session.SessionManager
import io.github.backtraxe.anysave.telegram.TelegramClient
import io.github.backtraxe.anysave.telegram.TelegramConfig
import io.github.backtraxe.anysave.telegram.TelegramProtocol
import io.github.backtraxe.anysave.telegram.TelegramSettings

/**
 * Bot token / chat id / auto-upload settings.
 *
 * The token is stored in the app's private SharedPreferences only. The "send
 * test message" button validates it against the Bot API (getMe + sendMessage),
 * which is also how a user confirms their chat id is reachable.
 */
class TelegramSettingsActivity : Activity() {

    private lateinit var tokenInput: EditText
    private lateinit var chatInput: EditText
    private lateinit var autoUpload: Switch
    private lateinit var testButton: View
    private lateinit var saveButton: View
    private lateinit var backButton: View
    private lateinit var status: TextView
    private lateinit var readerPackUrlInput: EditText
    private lateinit var readerPackCheckButton: View
    private lateinit var readerPackStatus: TextView
    private lateinit var readerPackRepository: ReaderPackRepository
    private lateinit var loginDouyin: View
    private lateinit var loginBilibili: View
    private lateinit var loginXiaohongshu: View
    private lateinit var clearSessions: View
    private lateinit var sessionsStatus: TextView
    private lateinit var cookieImportInput: EditText
    private lateinit var importDouyinCookies: View
    private lateinit var importBilibiliCookies: View
    private lateinit var importXiaohongshuCookies: View
    private lateinit var clearDouyinSession: View
    private lateinit var clearBilibiliSession: View
    private lateinit var clearXiaohongshuSession: View
    private lateinit var qualityAuto: TextView
    private lateinit var quality1080: TextView
    private lateinit var quality720: TextView
    private lateinit var quality480: TextView
    private lateinit var qualityStatus: TextView

    private lateinit var probeWebView: WebView
    private val probeHandler = Handler(Looper.getMainLooper())
    private val loginResults = arrayOfNulls<LoginSignals.Result>(LOGIN_PROBES.size)
    private var probeIndex = -1
    private var probeGeneration = 0
    private var probePageLoaded = false
    private var probeExpectedHost = ""
    private var probeRetries = 0
    private var checkingLoginState = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_telegram_settings)

        tokenInput = findViewById(R.id.settingsTokenInput)
        chatInput = findViewById(R.id.settingsChatInput)
        autoUpload = findViewById(R.id.settingsAutoUpload)
        testButton = findViewById(R.id.settingsTestButton)
        saveButton = findViewById(R.id.settingsSaveButton)
        backButton = findViewById(R.id.settingsBack)
        status = findViewById(R.id.settingsStatus)
        readerPackUrlInput = findViewById(R.id.readerPackUrlInput)
        readerPackCheckButton = findViewById(R.id.readerPackCheckButton)
        readerPackStatus = findViewById(R.id.readerPackStatus)
        readerPackRepository = ReaderPackRepository(this)
        loginDouyin = findViewById(R.id.loginDouyin)
        loginBilibili = findViewById(R.id.loginBilibili)
        loginXiaohongshu = findViewById(R.id.loginXiaohongshu)
        clearSessions = findViewById(R.id.clearSessions)
        sessionsStatus = findViewById(R.id.sessionsStatus)
        cookieImportInput = findViewById(R.id.cookieImportInput)
        importDouyinCookies = findViewById(R.id.importDouyinCookies)
        importBilibiliCookies = findViewById(R.id.importBilibiliCookies)
        importXiaohongshuCookies = findViewById(R.id.importXiaohongshuCookies)
        clearDouyinSession = findViewById(R.id.clearDouyinSession)
        clearBilibiliSession = findViewById(R.id.clearBilibiliSession)
        clearXiaohongshuSession = findViewById(R.id.clearXiaohongshuSession)
        qualityAuto = findViewById(R.id.qualityAuto)
        quality1080 = findViewById(R.id.quality1080)
        quality720 = findViewById(R.id.quality720)
        quality480 = findViewById(R.id.quality480)
        qualityStatus = findViewById(R.id.qualityStatus)
        setupProbeWebView()
        probeHandler.post { refreshLoginStates() }

        val saved = TelegramSettings.load(this)
        tokenInput.setText(saved.botToken)
        chatInput.setText(saved.chatId)
        autoUpload.isChecked = saved.autoUpload

        readerPackUrlInput.setText(readerPackRepository.savedUrl())

        backButton.setOnClickListener { finish() }
        saveButton.setOnClickListener { saveSettings() }
        testButton.setOnClickListener { sendTestMessage() }
        readerPackCheckButton.setOnClickListener { checkReaderPack() }
        loginDouyin.setOnClickListener { openLoginPage(SessionManager.SITES[0].url) }
        loginBilibili.setOnClickListener { openLoginPage(SessionManager.SITES[1].url) }
        loginXiaohongshu.setOnClickListener { openLoginPage(SessionManager.SITES[2].url) }
        clearSessions.setOnClickListener { clearAllSessions() }
        importDouyinCookies.setOnClickListener { importCookies("douyin") }
        importBilibiliCookies.setOnClickListener { importCookies("bilibili") }
        importXiaohongshuCookies.setOnClickListener { importCookies("xiaohongshu") }
        clearDouyinSession.setOnClickListener { clearSiteSession("douyin", getString(R.string.login_douyin)) }
        clearBilibiliSession.setOnClickListener { clearSiteSession("bilibili", getString(R.string.login_bilibili)) }
        clearXiaohongshuSession.setOnClickListener { clearSiteSession("xiaohongshu", getString(R.string.login_xiaohongshu)) }
        findViewById<View>(R.id.openLogs).setOnClickListener {
            startActivity(android.content.Intent(this, LogActivity::class.java))
        }
        qualityAuto.setOnClickListener { setQuality(BilibiliQuality.AUTO) }
        quality1080.setOnClickListener { setQuality(BilibiliQuality.P1080) }
        quality720.setOnClickListener { setQuality(BilibiliQuality.P720) }
        quality480.setOnClickListener { setQuality(BilibiliQuality.P480) }
        updateQualityUi()
    }

    private fun currentConfig(): TelegramConfig = TelegramConfig(
        botToken = tokenInput.text?.toString()?.trim().orEmpty(),
        chatId = chatInput.text?.toString()?.trim().orEmpty(),
        autoUpload = autoUpload.isChecked,
    )

    private fun saveSettings() {
        val config = currentConfig()
        if (config.botToken.isNotEmpty() && !TelegramProtocol.isValidToken(config.botToken)) {
            showStatus(getString(R.string.settings_invalid_token))
            return
        }
        TelegramSettings.save(this, config)
        Toast.makeText(this, R.string.settings_saved, Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun sendTestMessage() {
        val config = currentConfig()
        if (!TelegramProtocol.isValidToken(config.botToken)) {
            showStatus(getString(R.string.settings_invalid_token))
            return
        }
        if (!TelegramProtocol.isValidChatId(config.chatId)) {
            showStatus(getString(R.string.settings_test_failed, "chat_id"))
            return
        }
        if (testButton.isEnabled.not()) return
        testButton.isEnabled = false
        showStatus(getString(R.string.settings_test) + "…")

        val apiBase = TelegramSettings.apiBase(this)
        Thread {
            val client = TelegramClient(apiBase)
            val result: Result<String> = client.getMe(config.botToken).fold(
                onSuccess = { info ->
                    client.sendMessage(config.botToken, config.chatId, TEST_MESSAGE)
                        .map { info.username }
                },
                onFailure = { Result.failure(it) },
            )
            runOnUiThread {
                testButton.isEnabled = true
                result.fold(
                    onSuccess = { username ->
                        val name = username.ifBlank { "bot" }
                        showStatus(getString(R.string.settings_test_ok, name))
                    },
                    onFailure = { error ->
                        showStatus(
                            getString(
                                R.string.settings_test_failed,
                                error.message ?: error.javaClass.simpleName,
                            ),
                        )
                    },
                )
            }
        }.start()
    }

    private fun setupProbeWebView() {
        probeWebView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.userAgentString = DESKTOP_CHROME_UA
            alpha = 0f
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            layoutParams = ViewGroup.LayoutParams(1, 1)
            webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                    // A new navigation starts: only the expected probe page may
                    // schedule an evaluation (avoid stale onPageFinished events
                    // when the reused WebView is re-targeted quickly).
                    if (url != null && hostOf(url) == probeExpectedHost) probePageLoaded = false
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    if (!checkingLoginState || probePageLoaded) return
                    if (url == null || hostOf(url) != probeExpectedHost) return
                    probePageLoaded = true
                    val delay = LOGIN_PROBES.getOrNull(probeIndex)?.delayMs ?: 0L
                    val index = probeIndex
                    val generation = probeGeneration
                    probeHandler.postDelayed({
                        if (generation == probeGeneration && index == probeIndex) {
                            evaluateProbe(index, generation)
                        }
                    }, delay)
                }
            }
        }
        (findViewById<ViewGroup>(android.R.id.content)).addView(probeWebView)
    }

    /**
     * Runs three read-only session probes in a hidden WebView that shares the
     * app's cookies (M11): douyin user/self title, bilibili nav JSON, xiaohongshu
     * __INITIAL_STATE__.user.loggedIn. No cookie values leave the process.
     */
    private fun refreshLoginStates() {
        if (checkingLoginState) return
        checkingLoginState = true
        probeGeneration += 1
        probeWebView.stopLoading()
        for (i in loginResults.indices) loginResults[i] = null
        renderLoginStates(checking = true)
        startProbe(0, probeGeneration)
    }

    private fun startProbe(index: Int, generation: Int) {
        if (generation != probeGeneration) return
        if (index >= LOGIN_PROBES.size) {
            checkingLoginState = false
            renderLoginStates(checking = false)
            return
        }
        probeIndex = index
        probePageLoaded = false
        probeRetries = 0
        renderLoginStates(checking = true)
        val probe = LOGIN_PROBES[index]
        probeExpectedHost = hostOf(probe.url)
        probeWebView.stopLoading()
        probeWebView.loadUrl(probe.url)
        probeHandler.postDelayed({
            if (generation == probeGeneration && probeIndex == index && checkingLoginState) {
                loginResults[index] = LoginSignals.Result(null)
                startProbe(index + 1, generation)
            }
        }, probe.delayMs + PROBE_TIMEOUT_MS)
    }

    private fun evaluateProbe(index: Int, generation: Int) {
        if (generation != probeGeneration || index != probeIndex) return
        val probe = LOGIN_PROBES.getOrNull(index) ?: return
        probeWebView.evaluateJavascript(probe.script) { raw ->
            if (generation != probeGeneration || index != probeIndex) return@evaluateJavascript
            val payload = jsString(raw)
            if (payload.isBlank() && probeRetries < PROBE_RETRIES) {
                // The reused WebView may evaluate before the document is ready
                // (e.g. right after "clear all sessions"); retry the read once
                // instead of reporting a false "Check failed".
                probeRetries += 1
                probeHandler.postDelayed({
                    if (generation == probeGeneration && index == probeIndex) {
                        evaluateProbe(index, generation)
                    }
                }, PROBE_RETRY_DELAY_MS)
                return@evaluateJavascript
            }
            loginResults[index] = when (index) {
                0 -> douyinResult(payload)
                1 -> LoginSignals.bilibili(payload)
                else -> LoginSignals.xiaohongshu(payload)
            }
            startProbe(index + 1, generation)
        }
    }

    private fun hostOf(url: String): String = try {
        android.net.Uri.parse(url).host.orEmpty()
    } catch (t: Throwable) {
        ""
    }

    private fun douyinResult(payload: String): LoginSignals.Result = try {
        LoginSignals.douyin(org.json.JSONObject(payload).optString("title"))
    } catch (t: Throwable) {
        LoginSignals.Result(null)
    }

    private fun jsString(raw: String?): String {
        if (raw.isNullOrBlank() || raw == "null") return ""
        return try {
            val value = org.json.JSONTokener(raw).nextValue()
            value as? String ?: raw
        } catch (t: Throwable) {
            raw
        }
    }

    private fun renderLoginStates(checking: Boolean) {
        val lines = LOGIN_PROBES.mapIndexed { index, probe ->
            val result = loginResults[index]
            val status = when {
                result == null -> getString(
                    if (checking) R.string.login_state_checking else R.string.login_state_failed,
                )

                result.loggedIn == true && result.account.isNotBlank() ->
                    getString(R.string.login_state_logged_in_as, result.account)

                result.loggedIn == true -> getString(R.string.login_state_logged_in)
                result.loggedIn == false -> getString(R.string.login_state_logged_out)
                else -> getString(R.string.login_state_failed)
            }
            getString(R.string.login_state_line, getString(probe.labelRes), status)
        }
        sessionsStatus.text = lines.joinToString("\n")
        sessionsStatus.visibility = View.VISIBLE
    }

    override fun onDestroy() {
        probeGeneration += 1
        probeHandler.removeCallbacksAndMessages(null)
        if (::probeWebView.isInitialized) {
            probeWebView.stopLoading()
            (probeWebView.parent as? ViewGroup)?.removeView(probeWebView)
            probeWebView.destroy()
        }
        super.onDestroy()
    }

    private fun setQuality(height: Int) {
        BilibiliQuality.setMaxHeight(this, height)
        updateQualityUi()
    }

    private fun updateQualityUi() {
        val current = BilibiliQuality.maxHeight(this)
        val entries = listOf(
            BilibiliQuality.AUTO to qualityAuto,
            BilibiliQuality.P1080 to quality1080,
            BilibiliQuality.P720 to quality720,
            BilibiliQuality.P480 to quality480,
        )
        for ((height, view) in entries) {
            val selected = height == current
            view.setBackgroundResource(
                if (selected) R.drawable.bg_settings_secondary_button else R.drawable.bg_text_button,
            )
            view.setTextColor(
                getColor(if (selected) R.color.brand else R.color.preview_text_secondary),
            )
        }
        qualityStatus.text = getString(R.string.quality_current, qualityLabel(current))
        qualityStatus.visibility = View.VISIBLE
    }

    private fun qualityLabel(height: Int): String = when (height) {
        BilibiliQuality.P1080 -> getString(R.string.quality_1080p)
        BilibiliQuality.P720 -> getString(R.string.quality_720p)
        BilibiliQuality.P480 -> getString(R.string.quality_480p)
        else -> getString(R.string.quality_auto)
    }

    private fun openLoginPage(url: String) {
        val host = try {
            android.net.Uri.parse(url).host ?: url
        } catch (t: Throwable) {
            url
        }
        showSessionsStatus(getString(R.string.login_opening, host))
        startActivity(
            android.content.Intent(this, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_OPEN_URL, url)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP),
        )
        finish()
    }

    private fun importCookies(siteId: String) {
        var text = cookieImportInput.text?.toString().orEmpty().trim()
        if (text.isBlank()) {
            // Convenience: copy the export, then tap the site's import button.
            text = readClipboard()
        }
        if (text.isBlank()) {
            showSessionsStatus(getString(R.string.cookie_import_empty))
            return
        }
        val imported = try {
            SessionManager.importCookies(siteId, text)
        } catch (t: Throwable) {
            0
        }
        if (imported <= 0) {
            showSessionsStatus(getString(R.string.cookie_import_failed))
            return
        }
        showSessionsStatus(getString(R.string.cookie_import_done, imported))
        cookieImportInput.setText("")
        probeHandler.postDelayed({ refreshLoginStates() }, 1_200)
    }

    private fun clearSiteSession(siteId: String, label: String) {
        val cleared = try {
            SessionManager.clearSite(siteId)
        } catch (t: Throwable) {
            0
        }
        val text = if (cleared > 0) {
            getString(R.string.session_clear_site_done, label)
        } else {
            getString(R.string.session_clear_site_failed, label)
        }
        showSessionsStatus(text)
        probeHandler.postDelayed({ refreshLoginStates() }, 1_000)
    }

    private fun readClipboard(): String {
        val manager = getSystemService(CLIPBOARD_SERVICE) as? ClipboardManager ?: return ""
        val clip = manager.primaryClip ?: return ""
        if (clip.itemCount == 0) return ""
        return clip.getItemAt(0).coerceToText(this).toString().trim()
    }

    private fun clearAllSessions() {
        if (clearSessions.isEnabled.not()) return
        clearSessions.isEnabled = false
        showSessionsStatus(getString(R.string.login_clear_all) + "…")
        SessionManager.clearAll { count ->
            runOnUiThread {
                clearSessions.isEnabled = true
                showSessionsStatus(getString(R.string.login_cleared, count))
                probeHandler.postDelayed({ refreshLoginStates() }, 1_500)
            }
        }
    }

    private fun showSessionsStatus(text: String) {
        sessionsStatus.text = text
        sessionsStatus.visibility = View.VISIBLE
    }

    private fun checkReaderPack() {
        val url = readerPackUrlInput.text?.toString()?.trim().orEmpty()
        if (url.isBlank()) {
            // Blank URL + check removes an applied pack (no dedicated button needed).
            val removed = readerPackRepository.clear()
            showReaderPackStatus(
                getString(R.string.settings_reader_pack_updated, removed.message),
            )
            return
        }
        if (!url.startsWith("https://") && !url.startsWith("http://")) {
            showReaderPackStatus(getString(R.string.settings_reader_pack_invalid))
            return
        }
        if (readerPackCheckButton.isEnabled.not()) return
        readerPackCheckButton.isEnabled = false
        showReaderPackStatus(getString(R.string.settings_reader_pack_check) + "…")
        val repository = readerPackRepository
        Thread {
            val result = repository.refresh(url)
            runOnUiThread {
                readerPackCheckButton.isEnabled = true
                val text = if (result.ok) {
                    getString(R.string.settings_reader_pack_updated, result.message)
                } else {
                    getString(R.string.settings_reader_pack_failed, result.message)
                }
                showReaderPackStatus(text)
                if (result.ok) readerPackUrlInput.setText(repository.savedUrl())
            }
        }.start()
    }

    private fun showReaderPackStatus(text: String) {
        readerPackStatus.text = text
        readerPackStatus.visibility = View.VISIBLE
    }

    private fun showStatus(text: String) {
        status.text = text
        status.visibility = View.VISIBLE
    }

    private companion object {
        private const val TEST_MESSAGE = "AnySave test message"
    }
}

private data class LoginProbe(
    val labelRes: Int,
    val url: String,
    val delayMs: Long,
    val script: String,
)

private const val PROBE_TIMEOUT_MS = 12_000L
private const val PROBE_RETRIES = 2
private const val PROBE_RETRY_DELAY_MS = 2_500L

private val LOGIN_PROBES = listOf(
    LoginProbe(
        R.string.login_douyin,
        "https://www.douyin.com/user/self",
        5_000L,
        "JSON.stringify({title: document.title})",
    ),
    LoginProbe(
        R.string.login_bilibili,
        "https://api.bilibili.com/x/web-interface/nav",
        1_500L,
        "document.body.innerText",
    ),
    LoginProbe(
        R.string.login_xiaohongshu,
        "https://www.xiaohongshu.com/explore",
        5_000L,
        "(function(){try{var s=window.__INITIAL_STATE__;var u=s&&s.user;" +
            "function val(x){return x&&typeof x==='object'&&'value' in x?x.value:x;}" +
            "var li=val(u&&u.loggedIn);var ui=val(u&&u.userInfo);" +
            "return JSON.stringify({logged:!!li,nickname:(ui&&ui.nickname)||''});" +
            "}catch(e){return JSON.stringify({logged:null});}})()",
    ),
)
