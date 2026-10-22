package io.github.backtraxe.anysave.reader

/**
 * Extracts a usable URL from whatever the user pasted.
 *
 * Share sheets often copy a sentence, not a bare link:
 * `看看这个 http://xhslink.com/a/abc，复制本条信息…`. The UI should still work.
 * Also accepts a bare host/path (`xhslink.cn/a/abc`) by adding https://.
 *
 * Pure JVM code, covered by unit tests.
 */
object UrlInput {

    private val URL_IN_TEXT = Regex(
        """https?://[^\s，。！？、；：“”‘’（）()\[\]{}<>《》]+""",
        RegexOption.IGNORE_CASE,
    )

    private val BARE_HOST = Regex(
        """^[A-Za-z0-9-]+(?:\.[A-Za-z0-9-]+)*\.[A-Za-z]{2,}(?:/[^\s，。！？、；：]*)?$""",
    )

    private val TRAILING = charArrayOf(
        '，', '。', '、', '；', '：', '！', '？', ')', '）', ']', '】', '》', '”', '’', ',', '.', ';', ':', '!', '?',
    )

    /** Returns a normalized https URL, or null when [text] contains none. */
    fun extract(text: String): String? {
        val input = text.trim()
        if (input.isEmpty()) return null
        URL_IN_TEXT.find(input)?.let { match ->
            return match.value.trimEnd(*TRAILING).takeIf { it.length > 8 }
        }
        val bare = input.split(Regex("""\s+""")).firstOrNull() ?: return null
        if (!BARE_HOST.matches(bare)) return null
        return "https://" + bare.trimEnd(*TRAILING)
    }
}
