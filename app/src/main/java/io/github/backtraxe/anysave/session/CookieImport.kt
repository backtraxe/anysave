package io.github.backtraxe.anysave.session

import org.json.JSONArray
import org.json.JSONObject

/**
 * Parses browser-exported cookies for the in-app "cookie import" feature (M12).
 *
 * Accepted inputs (auto-detected):
 * - Cookie-Editor JSON array (or `{"cookies": [...]}` wrapper)
 * - Netscape cookie file lines (`#HttpOnly_` prefix supported)
 * - A raw `Cookie:` request header (`name=value; name2=value2`)
 *
 * Values are only ever passed to WebView's CookieManager; they are never logged.
 */
data class ImportedCookie(
    val name: String,
    val value: String,
    val domain: String?,
    val path: String = "/",
    val secure: Boolean = false,
    val httpOnly: Boolean = false,
    val expires: Long? = null,
)

object CookieImport {
    const val MAX_COOKIES = 500

    /** @throws IllegalArgumentException when the text is empty or unparseable. */
    fun parse(text: String): List<ImportedCookie> {
        val trimmed = text.trim()
        require(trimmed.isNotEmpty()) { "empty cookie text" }
        val cookies = when {
            trimmed.startsWith("[") || trimmed.startsWith("{") -> parseJson(trimmed)
            looksLikeNetscape(trimmed) -> parseNetscape(trimmed)
            else -> parseHeader(trimmed)
        }.filter { it.name.isNotBlank() }
        require(cookies.isNotEmpty()) { "no cookies found" }
        require(cookies.size <= MAX_COOKIES) { "too many cookies" }
        return cookies
    }

    private fun looksLikeNetscape(text: String): Boolean =
        text.lineSequence().any { line ->
            line.startsWith("#HttpOnly_") ||
                (line.isNotBlank() && !line.startsWith("#") && line.split('\t').size >= 7)
        }

    private fun parseJson(text: String): List<ImportedCookie> {
        val array = try {
            val value = if (text.startsWith("[")) {
                JSONArray(text)
            } else {
                JSONObject(text).optJSONArray("cookies")
                    ?: throw IllegalArgumentException("missing cookies array")
            }
            value
        } catch (t: Throwable) {
            throw IllegalArgumentException("invalid JSON cookies")
        }
        val out = ArrayList<ImportedCookie>(array.length())
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            val name = obj.optString("name").trim()
            if (name.isEmpty()) continue
            out += ImportedCookie(
                name = name,
                value = obj.optString("value"),
                domain = obj.optString("domain").trim().takeIf { it.isNotEmpty() },
                path = obj.optString("path").trim().ifEmpty { "/" },
                secure = boolean(obj, "secure"),
                httpOnly = boolean(obj, "httpOnly"),
                expires = long(obj, "expirationDate") ?: long(obj, "expires"),
            )
        }
        return out
    }

    private fun parseNetscape(text: String): List<ImportedCookie> {
        val out = ArrayList<ImportedCookie>()
        for (rawLine in text.lineSequence()) {
            var line = rawLine.trimEnd('\r')
            if (line.isBlank() || (line.startsWith("#") && !line.startsWith("#HttpOnly_"))) continue
            var httpOnly = false
            if (line.startsWith("#HttpOnly_")) {
                httpOnly = true
                line = line.removePrefix("#HttpOnly_")
            }
            val fields = line.split('\t')
            if (fields.size < 7) continue
            val domain = fields[0].trim().takeIf { it.isNotEmpty() }
            val name = fields[5].trim()
            if (name.isEmpty()) continue
            out += ImportedCookie(
                name = name,
                value = fields[6],
                domain = domain,
                path = fields[2].trim().ifEmpty { "/" },
                secure = fields[3].equals("TRUE", ignoreCase = true),
                httpOnly = httpOnly,
                expires = fields[4].trim().toLongOrNull()?.takeIf { it > 0 },
            )
        }
        return out
    }

    private fun parseHeader(text: String): List<ImportedCookie> {
        var header = text.trim()
        if (header.startsWith("Cookie:", ignoreCase = true)) {
            header = header.substringAfter(':').trim()
        }
        val out = ArrayList<ImportedCookie>()
        for (part in header.split(';')) {
            val pair = part.trim()
            if (pair.isEmpty()) continue
            val index = pair.indexOf('=')
            if (index <= 0) continue
            val name = pair.substring(0, index).trim()
            if (name.isEmpty()) continue
            out += ImportedCookie(
                name = name,
                value = pair.substring(index + 1).trim(),
                domain = null,
            )
        }
        return out
    }

    private fun boolean(obj: JSONObject, key: String): Boolean {
        if (!obj.has(key)) return false
        return when (val value = obj.opt(key)) {
            is Boolean -> value
            is String -> value.equals("true", ignoreCase = true)
            else -> false
        }
    }

    private fun long(obj: JSONObject, key: String): Long? {
        val value = obj.opt(key) ?: return null
        val number = when (value) {
            is Number -> value.toDouble()
            is String -> value.toDoubleOrNull()
            else -> null
        } ?: return null
        val seconds = number.toLong()
        return seconds.takeIf { it > 0 }
    }
}
