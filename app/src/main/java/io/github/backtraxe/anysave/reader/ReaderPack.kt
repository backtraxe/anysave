package io.github.backtraxe.anysave.reader

import org.json.JSONObject
import java.security.MessageDigest

/** One reader override from a remote pack: new SSR script and/or URL patterns. */
data class ReaderOverride(
    val readerId: String,
    val embeddedPayloadScript: String? = null,
    val responsePatterns: List<String>? = null,
    val sha256: String? = null,
)

/** A versioned set of reader overrides shipped as JSON. */
data class ReaderPack(
    val version: Int,
    val readers: List<ReaderOverride>,
)

/**
 * Reader pack format (M7):
 *
 * ```json
 * {
 *   "version": 1,
 *   "readers": [
 *     {
 *       "id": "douyin",
 *       "embeddedPayloadScript": "(function(){ ... })()",
 *       "sha256": "<hex sha256 of the script string>",
 *       "responsePatterns": ["/aweme/v1/web/aweme/detail/"]
 *     }
 *   ]
 * }
 * ```
 *
 * A pack can update the page-side extraction script and response patterns of a
 * built-in reader without an app release — the compiled parser stays in the app.
 * Every script must carry its sha256 so a truncated/tampered download is rejected.
 */
object ReaderPackParser {
    const val VERSION = 1
    const val MAX_PACK_BYTES = 512 * 1024
    const val MAX_SCRIPT_CHARS = 200_000
    const val MAX_READERS = 8
    const val MAX_PATTERNS = 8
    const val MAX_PATTERN_CHARS = 300

    private val KNOWN_READER_IDS = setOf("douyin", "xiaohongshu", "bilibili", "weibo")

    /** @throws IllegalArgumentException with a user-readable reason. */
    fun parse(json: String): ReaderPack {
        require(json.length <= MAX_PACK_BYTES) { "pack too large" }
        val root = try {
            JSONObject(json)
        } catch (t: Throwable) {
            throw IllegalArgumentException("not valid JSON")
        }
        val version = root.optInt("version", -1)
        require(version == VERSION) { "unsupported pack version $version" }
        val array = root.optJSONArray("readers")
            ?: throw IllegalArgumentException("missing readers")
        require(array.length() in 1..MAX_READERS) { "bad reader count ${array.length()}" }

        val overrides = ArrayList<ReaderOverride>(array.length())
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i)
                ?: throw IllegalArgumentException("reader[$i] is not an object")
            overrides += parseOverride(obj, i)
        }
        val duplicate = overrides.groupBy { it.readerId }.entries.firstOrNull { it.value.size > 1 }
        require(duplicate == null) { "duplicate reader id ${duplicate?.key}" }
        return ReaderPack(version = version, readers = overrides)
    }

    private fun parseOverride(obj: JSONObject, index: Int): ReaderOverride {
        val id = obj.optString("id").trim()
        require(id in KNOWN_READER_IDS) { "reader[$index] has unknown id '$id'" }

        val script = obj.optString("embeddedPayloadScript").trim().takeIf { it.isNotEmpty() }
        require(script == null || script.length <= MAX_SCRIPT_CHARS) { "script for $id too large" }

        val patterns = obj.optJSONArray("responsePatterns")?.let { array ->
            require(array.length() <= MAX_PATTERNS) { "too many patterns for $id" }
            (0 until array.length()).map { i ->
                val pattern = array.optString(i).trim()
                require(pattern.isNotEmpty() && pattern.length <= MAX_PATTERN_CHARS) {
                    "bad pattern for $id"
                }
                pattern
            }
        }
        require(script != null || !patterns.isNullOrEmpty()) { "reader $id has no update" }

        val sha = obj.optString("sha256").trim().lowercase().takeIf { it.isNotEmpty() }
        if (script != null) {
            require(sha != null) { "reader $id script is missing sha256" }
            val actual = sha256(script)
            require(actual == sha) { "sha256 mismatch for reader $id" }
        }
        return ReaderOverride(
            readerId = id,
            embeddedPayloadScript = script,
            responsePatterns = patterns,
            sha256 = sha,
        )
    }

    fun sha256(text: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        val out = StringBuilder(digest.size * 2)
        for (byte in digest) {
            out.append(String.format("%02x", byte.toInt() and 0xFF))
        }
        return out.toString()
    }
}
