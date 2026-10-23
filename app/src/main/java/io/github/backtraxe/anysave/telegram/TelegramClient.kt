package io.github.backtraxe.anysave.telegram

import android.util.Log
import org.json.JSONObject
import kotlin.math.max
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Thin Bot API client (no third-party HTTP library).
 *
 * Only three operations are needed by the app: validate the bot, send a test
 * message, and upload the saved media as albums. The token is never logged; it
 * only appears inside the request URL.
 */
class TelegramClient(private val baseUrl: String) {

    data class BotInfo(val id: Long, val username: String)

    data class UploadAttachment(
        val fileName: String,
        val mimeType: String,
        val bytes: ByteArray,
        val isVideo: Boolean,
    ) {
        fun describe(): TelegramProtocol.Attachment =
            TelegramProtocol.Attachment(
                fileName = fileName,
                mimeType = mimeType,
                bytes = bytes.size,
                isVideo = isVideo,
            )
    }

    data class UploadSummary(
        val uploaded: Int,
        val failed: Int,
        val lastError: String?,
        val failedNames: List<String> = emptyList(),
    ) {
        val ok: Boolean get() = failed == 0 && uploaded > 0
    }

    fun getMe(token: String): Result<BotInfo> = runCatching {
        val response = post(token, "getMe", formBody(emptyList()), null)
        val result = response.optJSONObject("result")
            ?: throw TelegramApiException(response.optString("description", "empty getMe result"))
        BotInfo(
            id = result.optLong("id"),
            username = result.optString("username").ifBlank { result.optString("first_name") },
        )
    }

    fun sendMessage(token: String, chatId: String, text: String): Result<Unit> = runCatching {
        post(
            token,
            "sendMessage",
            formBody(listOf("chat_id" to chatId, "text" to text)),
            null,
        )
        Unit
    }

    /**
     * Uploads [attachments] as Telegram albums. Groups are sent sequentially so
     * the caller can show real progress; a failing group is counted and the next
     * one is still attempted.
     */
    fun uploadMedia(
        token: String,
        chatId: String,
        caption: String,
        attachments: List<UploadAttachment>,
        onProgress: (uploaded: Int, failed: Int) -> Unit = { _, _ -> },
    ): UploadSummary {
        val valid = attachments.filter { TelegramProtocol.isWithinLimits(it.describe()) }
        val oversized = attachments.size - valid.size
        if (oversized > 0) {
            Log.w(TAG, "telegram: skipping $oversized attachment(s) over the Bot API size limit")
        }
        if (valid.isEmpty()) {
            return UploadSummary(uploaded = 0, failed = oversized, lastError = "size limit")
        }

        val groups = valid.chunked(TelegramProtocol.MAX_ITEMS_PER_GROUP)
        var uploaded = 0
        var failed = oversized
        var lastError: String? = if (oversized > 0) "size limit" else null
        val failedNames = LinkedHashSet<String>()
        attachments.filter { !valid.contains(it) }.forEach { failedNames += it.fileName }
        var captionPending = caption.isNotBlank()
        val retryQueue = ArrayList<UploadAttachment>()

        groups.forEachIndexed { index, group ->
            val groupCaption = if (captionPending) caption else ""
            var sent = false
            try {
                sendGroup(token, chatId, group, groupCaption)
                sent = true
            } catch (t: Throwable) {
                lastError = t.message ?: t.javaClass.simpleName
                Log.w(TAG, "telegram: group ${index + 1}/${groups.size} failed: $lastError")
                // Albums are atomic server-side, so an immediate retry cannot
                // duplicate a partial album. Transient Telegram/CDN issues are
                // common; retry before degrading to single messages.
                if (group.size > 1) {
                    try {
                        Thread.sleep(
                            max(GROUP_RETRY_DELAY_MS, retryDelayMillis(t))
                                .coerceAtMost(MAX_RETRY_WAIT_MS),
                        )
                        sendGroup(token, chatId, group, groupCaption)
                        sent = true
                        Log.i(TAG, "telegram: group ${index + 1}/${groups.size} sent on retry")
                    } catch (retryError: Throwable) {
                        lastError = retryError.message ?: retryError.javaClass.simpleName
                        Log.w(
                            TAG,
                            "telegram: group ${index + 1}/${groups.size} retry failed: $lastError",
                        )
                    }
                }
            }
            if (sent) {
                uploaded += group.size
                captionPending = false
                Log.i(TAG, "telegram: group ${index + 1}/${groups.size} sent (${group.size} item(s))")
            } else {
                Log.w(
                    TAG,
                    "telegram: group ${index + 1}/${groups.size} failed, " +
                        "trying items individually",
                )
                // One bad item must not lose the rest of the album; failed
                // singles are queued for one retry pass after all groups.
                var groupUploaded = 0
                for (attachment in group) {
                    val itemCaption = if (captionPending && groupUploaded == 0) caption else ""
                    try {
                        sleepForRateLimit(lastError)
                        sendSingle(token, chatId, attachment, itemCaption)
                        groupUploaded += 1
                        captionPending = false
                    } catch (itemError: Throwable) {
                        lastError = itemError.message ?: itemError.javaClass.simpleName
                        retryQueue += attachment
                        failedNames += attachment.fileName
                    }
                }
                uploaded += groupUploaded
                failed += group.size - groupUploaded
            }
            onProgress(uploaded, failed)
        }

        if (retryQueue.isNotEmpty()) {
            Log.i(TAG, "telegram: retrying ${retryQueue.size} failed attachment(s) once")
            for (attachment in retryQueue) {
                try {
                    sleepForRateLimit(lastError)
                    sendSingle(token, chatId, attachment, "")
                    uploaded += 1
                    failed -= 1
                    failedNames -= attachment.fileName
                    captionPending = false
                } catch (t: Throwable) {
                    lastError = t.message ?: t.javaClass.simpleName
                }
            }
            onProgress(uploaded, failed)
        }
        return UploadSummary(
            uploaded = uploaded,
            failed = failed,
            lastError = lastError,
            failedNames = failedNames.toList(),
        )
    }

    private fun sendGroup(
        token: String,
        chatId: String,
        group: List<UploadAttachment>,
        caption: String,
    ) {
        if (group.size == 1) {
            sendSingle(token, chatId, group.single(), caption)
        } else {
            sendAlbum(token, chatId, group, caption)
        }
    }

    private fun sendSingle(
        token: String,
        chatId: String,
        attachment: UploadAttachment,
        caption: String,
    ) {
        val (method, field) = TelegramProtocol.singleSend(attachment.describe())
        val parts = ArrayList<MultipartBody.Part>(4)
        parts += MultipartBody.Part(name = "chat_id", value = chatId)
        if (caption.isNotBlank()) {
            parts += MultipartBody.Part(name = "caption", value = caption)
        }
        parts += MultipartBody.Part(
            name = field,
            bytes = attachment.bytes,
            fileName = attachment.fileName,
            contentType = attachment.mimeType,
        )
        val boundary = MultipartBody.boundary()
        val body = MultipartBody.build(boundary, parts)
        post(token, method, body, "multipart/form-data; boundary=$boundary")
    }

    private fun sendAlbum(
        token: String,
        chatId: String,
        group: List<UploadAttachment>,
        caption: String,
    ) {
        val boundary = MultipartBody.boundary()
        val parts = ArrayList<MultipartBody.Part>(group.size + 2)
        parts += MultipartBody.Part(name = "chat_id", value = chatId)
        parts += MultipartBody.Part(
            name = "media",
            value = TelegramProtocol.mediaJson(group.map { it.describe() }, caption),
        )
        group.forEachIndexed { index, attachment ->
            parts += MultipartBody.Part(
                name = "file$index",
                bytes = attachment.bytes,
                fileName = attachment.fileName,
                contentType = attachment.mimeType,
            )
        }
        val body = MultipartBody.build(boundary, parts)
        post(token, "sendMediaGroup", body, "multipart/form-data; boundary=$boundary")
    }

    /**
     * POSTs [body] to `/bot<token>/<method>` and returns the parsed response,
     * throwing [TelegramApiException] when the Bot API reports `ok: false`.
     */
    private fun post(
        token: String,
        method: String,
        body: ByteArray,
        contentType: String?,
    ): JSONObject {
        val url = URL("$baseUrl/bot$token/$method")
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            doOutput = true
            setFixedLengthStreamingMode(body.size)
            if (contentType != null) {
                setRequestProperty("Content-Type", contentType)
            }
            setRequestProperty("Accept", "application/json")
        }
        try {
            connection.outputStream.use { it.write(body) }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            val json = if (text.isBlank()) JSONObject() else JSONObject(text)
            if (!json.optBoolean("ok", false)) {
                val description = json.optString("description").ifBlank { "HTTP $status" }
                val retryAfter = json.optJSONObject("parameters")?.optLong("retry_after") ?: 0L
                throw TelegramApiException(description, retryAfter * 1000L)
            }
            return json
        } finally {
            connection.disconnect()
        }
    }

    private fun formBody(fields: List<Pair<String, String>>): ByteArray {
        if (fields.isEmpty()) return ByteArray(0)
        return fields.joinToString("&") { (key, value) ->
            "${URLEncoder.encode(key, "UTF-8")}=${URLEncoder.encode(value, "UTF-8")}"
        }.toByteArray(Charsets.UTF_8)
    }

    class TelegramApiException(
        message: String,
        val retryAfterMs: Long = 0L,
    ) : IllegalStateException(message)

    private fun retryDelayMillis(t: Throwable?): Long =
        (t as? TelegramApiException)?.retryAfterMs ?: 0L

    /** Honors Bot API 429 `parameters.retry_after`, capped for UI sanity. */
    private fun sleepForRateLimit(lastError: String?) {
        val delay = lastError
            ?.let { Regex("retry after (\\d+)", RegexOption.IGNORE_CASE).find(it) }
            ?.groupValues
            ?.getOrNull(1)
            ?.toLongOrNull()
            ?.times(1000L)
            ?: 0L
        if (delay > 0) {
            Thread.sleep(delay.coerceAtMost(MAX_RETRY_WAIT_MS))
        }
    }

    private companion object {
        private const val TAG = "AnySave"
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val READ_TIMEOUT_MS = 120_000
        private const val GROUP_RETRY_DELAY_MS = 700L
        private const val MAX_RETRY_WAIT_MS = 10_000L
    }
}
