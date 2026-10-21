package io.github.backtraxe.anysave

import android.app.Activity
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
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

        val saved = TelegramSettings.load(this)
        tokenInput.setText(saved.botToken)
        chatInput.setText(saved.chatId)
        autoUpload.isChecked = saved.autoUpload

        backButton.setOnClickListener { finish() }
        saveButton.setOnClickListener { saveSettings() }
        testButton.setOnClickListener { sendTestMessage() }
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

    private fun showStatus(text: String) {
        status.text = text
        status.visibility = View.VISIBLE
    }

    private companion object {
        private const val TEST_MESSAGE = "AnySave test message"
    }
}
