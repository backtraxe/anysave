package io.github.backtraxe.anysave

import android.app.Activity
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import io.github.backtraxe.anysave.reader.ReaderPackRepository
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

    private fun clearAllSessions() {
        if (clearSessions.isEnabled.not()) return
        clearSessions.isEnabled = false
        showSessionsStatus(getString(R.string.login_clear_all) + "…")
        SessionManager.clearAll { count ->
            runOnUiThread {
                clearSessions.isEnabled = true
                showSessionsStatus(getString(R.string.login_cleared, count))
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
