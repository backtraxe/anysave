package io.github.backtraxe.anysave.telegram

import android.content.Context
import android.content.SharedPreferences
import io.github.backtraxe.anysave.BuildConfig

/** User-provided Telegram bot settings. Empty token/chat id means "not configured". */
data class TelegramConfig(
    val botToken: String = "",
    val chatId: String = "",
    val autoUpload: Boolean = true,
) {
    val isConfigured: Boolean get() = botToken.isNotBlank() && chatId.isNotBlank()

    /** Upload after saving only when configured and the toggle is on. */
    val effectiveAutoUpload: Boolean get() = isConfigured && autoUpload
}

/**
 * SharedPreferences-backed Telegram settings.
 *
 * The bot token is a secret: it lives only in the app's private prefs (never in
 * the repo, never logged, never in a job payload). Debug builds honour an
 * `api_base` override so the upload path can be exercised against a local mock
 * server; release builds always talk to https://api.telegram.org.
 */
object TelegramSettings {
    private const val PREFS = "anysave_settings"
    private const val KEY_TOKEN = "bot_token"
    private const val KEY_CHAT_ID = "chat_id"
    private const val KEY_AUTO_UPLOAD = "auto_upload"
    private const val KEY_API_BASE = "api_base"

    const val DEFAULT_API_BASE = "https://api.telegram.org"

    fun load(context: Context): TelegramConfig {
        val prefs = prefs(context)
        return TelegramConfig(
            botToken = prefs.getString(KEY_TOKEN, "").orEmpty(),
            chatId = prefs.getString(KEY_CHAT_ID, "").orEmpty(),
            autoUpload = prefs.getBoolean(KEY_AUTO_UPLOAD, true),
        )
    }

    fun save(context: Context, config: TelegramConfig) {
        prefs(context).edit()
            .putString(KEY_TOKEN, config.botToken.trim())
            .putString(KEY_CHAT_ID, config.chatId.trim())
            .putBoolean(KEY_AUTO_UPLOAD, config.autoUpload)
            .apply()
    }

    fun apiBase(context: Context): String {
        if (!BuildConfig.DEBUG) return DEFAULT_API_BASE
        val override = prefs(context).getString(KEY_API_BASE, null)
            ?.trim()
            ?.trimEnd('/')
            ?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
        return override ?: DEFAULT_API_BASE
    }

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
