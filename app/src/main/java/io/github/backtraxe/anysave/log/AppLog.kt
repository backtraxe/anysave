package io.github.backtraxe.anysave.log

import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * App log facade: writes to logcat and keeps a bounded history for the
 * in-app diagnostics screen (Settings → Diagnostics → View logs).
 *
 * Cookie/token values are never passed here by callers; the screen is meant to
 * be shareable when a save/upload fails.
 */
object AppLog {

    const val MAX_LINES = 2_000

    private val ring = LogRing(MAX_LINES)
    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    fun i(tag: String, message: String) {
        Log.i(tag, message)
        append("I", tag, message)
    }

    fun w(tag: String, message: String, throwable: Throwable? = null) {
        Log.w(tag, message, throwable)
        append("W", tag, if (throwable == null) message else "$message: ${throwable.message}")
    }

    fun e(tag: String, message: String, throwable: Throwable? = null) {
        Log.e(tag, message, throwable)
        append("E", tag, if (throwable == null) message else "$message: ${throwable.message}")
    }

    /** Snapshot for the diagnostics screen / share intent. */
    fun lines(): List<String> = ring.snapshot()

    fun clear() = ring.clear()

    private fun append(level: String, tag: String, message: String) {
        val stamp = synchronized(timeFormat) { timeFormat.format(Date()) }
        ring.add("$stamp $level/$tag: ${LogSanitizer.sanitize(message)}")
    }
}
