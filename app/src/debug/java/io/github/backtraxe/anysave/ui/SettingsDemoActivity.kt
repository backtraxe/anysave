package io.github.backtraxe.anysave.ui

import android.app.Activity
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.Switch
import android.widget.TextView
import io.github.backtraxe.anysave.R

/**
 * DEBUG-ONLY visual harness for `activity_telegram_settings` (debug source set,
 * never shipped). TelegramSettingsActivity owns the real wiring; this exists so
 * the screen can be screenshot-verified before/without a bot token.
 *
 * Launch with:
 *   adb shell am start -n io.github.backtraxe.anysave/.ui.SettingsDemoActivity
 *   ... --es state filled | test-ok | test-failed | saved
 */
class SettingsDemoActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_telegram_settings)

        val tokenInput = findViewById<EditText>(R.id.settingsTokenInput)
        val chatInput = findViewById<EditText>(R.id.settingsChatInput)
        val autoUpload = findViewById<Switch>(R.id.settingsAutoUpload)
        val status = findViewById<TextView>(R.id.settingsStatus)
        val testButton = findViewById<Button>(R.id.settingsTestButton)
        val saveButton = findViewById<Button>(R.id.settingsSaveButton)

        findViewById<View>(R.id.settingsBack).setOnClickListener { finish() }

        fun fillForm() {
            tokenInput.setText("123456789:AAH-demoToken_abcdefghijklmnop")
            chatInput.setText("-1001234567890")
            autoUpload.isChecked = true
        }

        when (intent?.getStringExtra(EXTRA_STATE)) {
            STATE_FILLED -> fillForm()

            STATE_TEST_OK -> {
                fillForm()
                status.visibility = View.VISIBLE
                status.text = getString(R.string.settings_test_ok, "AnySave Bot")
            }

            STATE_TEST_FAILED -> {
                fillForm()
                status.visibility = View.VISIBLE
                status.text = getString(R.string.settings_test_failed, "401 Unauthorized")
            }

            STATE_SAVED -> {
                fillForm()
                status.visibility = View.VISIBLE
                status.text = getString(R.string.settings_saved)
            }
        }

        testButton.setOnClickListener {
            status.visibility = View.VISIBLE
            status.text = getString(R.string.settings_test_ok, "AnySave Bot")
        }
        saveButton.setOnClickListener {
            status.visibility = View.VISIBLE
            status.text = getString(R.string.settings_saved)
        }
    }

    private companion object {
        private const val EXTRA_STATE = "state"
        private const val STATE_FILLED = "filled"
        private const val STATE_TEST_OK = "test-ok"
        private const val STATE_TEST_FAILED = "test-failed"
        private const val STATE_SAVED = "saved"
    }
}
