package io.github.backtraxe.anysave.telegram

import org.json.JSONArray
import org.json.JSONObject

/**
 * Pure Bot API protocol rules: what can be sent, how captions are trimmed and
 * how media is grouped into albums. No Android/no network, so it is unit tested.
 *
 * Bot API limits mirrored here:
 * - sendMediaGroup takes 2..10 items; a single item needs sendPhoto/sendVideo.
 * - photo uploads are capped at 10 MB, video at 50 MB.
 * - a caption is at most 1024 characters.
 */
object TelegramProtocol {
    const val MAX_ITEMS_PER_GROUP = 10
    const val MAX_CAPTION_LENGTH = 1024
    const val MAX_PHOTO_BYTES = 10L * 1024 * 1024
    const val MAX_VIDEO_BYTES = 50L * 1024 * 1024
    private const val CAPTION_DESC_LIMIT = 800

    private val TOKEN_PATTERN = Regex("^\\d{5,}:[A-Za-z0-9_-]{25,}$")
    private val CHAT_ID_PATTERN = Regex("^(-?\\d{4,}|@[A-Za-z0-9_]{4,})$")

    /** One file to upload. [bytes] is the already-downloaded payload size. */
    data class Attachment(
        val fileName: String,
        val mimeType: String,
        val bytes: Int,
        val isVideo: Boolean,
    )

    fun isValidToken(token: String): Boolean = TOKEN_PATTERN.matches(token.trim())

    fun isValidChatId(chatId: String): Boolean = CHAT_ID_PATTERN.matches(chatId.trim())

    fun isWithinLimits(attachment: Attachment): Boolean =
        attachment.bytes in 1..maxBytes(attachment.isVideo)

    fun maxBytes(isVideo: Boolean): Long =
        if (isVideo) MAX_VIDEO_BYTES else MAX_PHOTO_BYTES

    /**
     * Caption shared by a post: description first, then author and source link.
     * Trimmed to the Bot API limit.
     */
    fun buildCaption(
        desc: String,
        author: String,
        sourceUrl: String,
        platform: String = "",
    ): String {
        val footer = listOf(author.trim(), sourceUrl.trim())
            .filter { it.isNotEmpty() }
            .joinToString(" · ")
        val tags = listOf(hashtag(author), hashtag(platform))
            .filter { it.isNotEmpty() }
            .distinct()
            .joinToString(" ")
        val descText = desc.trim()
        val combined = listOf(descText, footer, tags)
            .filter { it.isNotEmpty() }
            .joinToString("\n\n")
        if (combined.length <= MAX_CAPTION_LENGTH) return combined

        // Trim the description, never the author/platform tags.
        val tail = listOf(footer, tags).filter { it.isNotEmpty() }.joinToString("\n\n")
        val separator = if (tail.isEmpty()) "" else "\n\n"
        val available = (MAX_CAPTION_LENGTH - tail.length - separator.length - 1).coerceAtLeast(0)
        val short = descText.take(available).trimEnd()
        val body = when {
            short.isEmpty() -> tail
            tail.isEmpty() -> short + "…"
            else -> "$short…$separator$tail"
        }
        return body.take(MAX_CAPTION_LENGTH)
    }

    /** Telegram hashtags allow letters/digits/underscore; spaces would break them. */
    private fun hashtag(raw: String): String {
        val cleaned = raw.trim().filter { it.isLetterOrDigit() || it == '_' }
        return if (cleaned.isEmpty()) "" else "#" + cleaned.take(64)
    }

    /** Albums are capped at 10 items by the Bot API; larger posts become several albums. */
    fun group(attachments: List<Attachment>): List<List<Attachment>> {
        if (attachments.isEmpty()) return emptyList()
        val chunks = attachments.chunked(MAX_ITEMS_PER_GROUP)
            .map { it.toMutableList() }
            .toMutableList()
        // A trailing 1-item "album" would arrive as a lonely single message;
        // rebalance with the previous album (11 -> 9+2, 21 -> 10+9+2).
        if (chunks.size > 1 && chunks.last().size == 1) {
            val last = chunks.removeAt(chunks.lastIndex)
            val moved = chunks.last().removeAt(chunks.last().lastIndex)
            last.add(0, moved)
            chunks.add(last)
        }
        return chunks
    }

    /** JSON `media` field for sendMediaGroup: attach://fileN references the parts. */
    fun mediaJson(group: List<Attachment>, caption: String): String {
        val array = JSONArray()
        group.forEachIndexed { index, attachment ->
            val item = JSONObject()
                .put("type", if (attachment.isVideo) "video" else "photo")
                .put("media", "attach://file$index")
            if (index == 0 && caption.isNotBlank()) {
                item.put("caption", caption)
            }
            array.put(item)
        }
        return array.toString()
    }

    /** Telegram method + form field name for a single-file send. */
    fun singleSend(attachment: Attachment): Pair<String, String> =
        if (attachment.isVideo) "sendVideo" to "video" else "sendPhoto" to "photo"
}
