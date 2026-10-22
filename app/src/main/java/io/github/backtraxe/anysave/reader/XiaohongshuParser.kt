package io.github.backtraxe.anysave.reader

import io.github.backtraxe.anysave.model.MediaItem
import io.github.backtraxe.anysave.model.MediaKind
import io.github.backtraxe.anysave.model.MediaSite
import io.github.backtraxe.anysave.model.PostMedia
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * Turns a xiaohongshu note payload into a [PostMedia].
 *
 * The note page is server-rendered; its data lives in `window.__INITIAL_STATE__`
 * (`note.noteDetailMap[<id>].note`). The reader's JS extraction returns either
 * `{noteId, note}` (compact) or the raw state object (HTML fallback), and this
 * parser accepts both.
 *
 * Media rules:
 * - images: the original asset is `https://ci.xiaohongshu.com/<fileId>` (no
 *   extension, full resolution). The display copy (`urlDefault`, usually a
 *   watermarked webp on xhscdn) is kept as a fallback URL and used for previews.
 * - video: `video.media.stream.h264[]` has multiple quality tiers; pick the best
 *   (quality rank then bitrate) `masterUrl`. Cover comes from
 *   `video.image.firstFrameFileid` / `thumbnailFileid`.
 * - live photos are ignored in this milestone (static image only).
 *
 * Pure JVM code, so it is covered by unit tests with real captured payloads.
 */
object XiaohongshuParser {
    private val NOTE_ID_IN_URL = Regex("/(?:explore|discovery/item)/([0-9a-zA-Z]+)")
    private val UNDEFINED_VALUE = Regex("(?<=[\\[:\\s,])\\s*undefined\\b")
    private val TAG_SUFFIX = Regex("\\[话题\\]")

    fun parse(body: String, sourceUrl: String): PostMedia? {
        if (body.isBlank()) return null
        val normalized = if (body.contains("undefined")) {
            UNDEFINED_VALUE.replace(body, " null")
        } else {
            body
        }
        val root = try {
            JSONObject(normalized)
        } catch (t: JSONException) {
            return null
        }
        val resolved = resolveNote(root, sourceUrl) ?: return null
        val (noteId, note) = resolved

        val items = ArrayList<MediaItem>()
        val video = note.optJSONObject("video")
        if (video != null) {
            addVideo(noteId, video, items)
        } else {
            addImages(noteId, note, items)
        }
        if (items.isEmpty()) return null

        return PostMedia(
            awemeId = noteId,
            author = authorOf(note),
            desc = captionOf(note),
            sourceUrl = sourceUrl,
            items = items,
            site = MediaSite.XIAOHONGSHU,
        )
    }

    // ---------------------------------------------------------------- resolving

    private fun resolveNote(root: JSONObject, sourceUrl: String): Pair<String, JSONObject>? {
        // Mobile/app-like pages use __INITIAL_STATE__.noteData.data.noteData.
        val noteData = root.optJSONObject("noteData")
        if (noteData != null) {
            val inner = noteData.optJSONObject("data")?.optJSONObject("noteData")
                ?: noteData.optJSONObject("noteData")
            if (inner != null && (inner.has("imageList") || inner.has("video"))) {
                val id = inner.optString("noteId").ifBlank {
                    NOTE_ID_IN_URL.find(sourceUrl)?.groupValues?.getOrNull(1).orEmpty()
                }
                if (id.isNotBlank()) return id to inner
            }
        }
        val noteField = root.optJSONObject("note") ?: return null
        val map = noteField.optJSONObject("noteDetailMap")
        if (map != null) {
            val idFromUrl = NOTE_ID_IN_URL.find(sourceUrl)?.groupValues?.getOrNull(1)
            val keys = map.keys().asSequence().toList()
            val id = keys.firstOrNull { it == idFromUrl } ?: keys.firstOrNull() ?: return null
            val entry = map.optJSONObject(id) ?: return null
            val note = entry.optJSONObject("note") ?: entry
            return id to note
        }
        // Compact payload from the reader script: {noteId, note}.
        val noteId = root.optString("noteId").ifBlank {
            NOTE_ID_IN_URL.find(sourceUrl)?.groupValues?.getOrNull(1).orEmpty()
        }
        if (noteId.isBlank()) return null
        if (noteField.length() == 0) return null
        return noteId to noteField
    }

    // ------------------------------------------------------------------- media

    private fun addImages(noteId: String, note: JSONObject, items: MutableList<MediaItem>) {
        val images = note.optJSONArray("imageList") ?: return
        for (i in 0 until images.length()) {
            val image = images.optJSONObject(i) ?: continue
            val original = originalImageUrl(image)
            val display = displayImageUrl(image)
            val url = original ?: display ?: continue
            val index = items.size + 1
            items += MediaItem(
                id = "$noteId-image-$index",
                kind = MediaKind.IMAGE,
                url = url,
                previewUrl = display ?: url,
                width = image.optInt("width"),
                height = image.optInt("height"),
                index = index,
                fallbackUrl = if (original != null && display != null && display != original) {
                    display
                } else {
                    ""
                },
            )
        }
    }

    private fun addVideo(noteId: String, video: JSONObject, items: MutableList<MediaItem>) {
        val playUrl = videoUrl(video) ?: return
        val (width, height) = videoDimensions(video)
        items += MediaItem(
            id = "$noteId-video-1",
            kind = MediaKind.VIDEO,
            url = playUrl,
            previewUrl = videoCover(video).orEmpty(),
            width = width,
            height = height,
            durationMs = videoDurationMs(video),
            index = 1,
        )
    }

    /** Full-resolution original; URL has no extension (server sniffs the bytes). */
    private fun originalImageUrl(image: JSONObject): String? {
        val fileId = image.optString("fileId").trim()
        if (fileId.isBlank()) return null
        return normalizeUrl("https://ci.xiaohongshu.com/$fileId")
    }

    private fun displayImageUrl(image: JSONObject): String? {
        stringOrNull(image, "urlDefault")?.let(::normalizeUrl)?.let { return it }
        stringOrNull(image, "url")?.let(::normalizeUrl)?.let { return it }
        val info = image.optJSONArray("infoList") ?: return null
        var preview: String? = null
        for (i in 0 until info.length()) {
            val entry = info.optJSONObject(i) ?: continue
            val url = stringOrNull(entry, "url")?.let(::normalizeUrl) ?: continue
            when (entry.optString("imageScene").uppercase()) {
                "H5_DTL", "WB_DFT" -> return url
                "H5_PRV", "WB_PRV" -> if (preview == null) preview = url
                else -> if (preview == null) preview = url
            }
        }
        return preview
    }

    private fun stringOrNull(obj: JSONObject, key: String): String? {
        if (!obj.has(key) || obj.isNull(key)) return null
        val value = obj.optString(key)
        return value.takeIf { it.isNotBlank() && it != "null" }
    }

    private fun videoUrl(video: JSONObject): String? {
        val media = video.optJSONObject("media") ?: video
        val stream = media.optJSONObject("stream")
        val h264 = stream?.optJSONArray("h264")
        if (h264 != null) {
            val best = (0 until h264.length())
                .mapNotNull { h264.optJSONObject(it) }
                .maxWithOrNull(
                    compareBy(
                        { QUALITY_RANK[it.optString("qualityType").uppercase()] ?: 0 },
                        { it.optLong("videoBitrate") },
                    ),
                )
            normalizeUrl(best?.optString("masterUrl"))?.let { return it }
        }
        return normalizeUrl(video.optString("url"))
    }

    private fun videoCover(video: JSONObject): String? {
        val image = video.optJSONObject("image")
        if (image != null) {
            for (key in listOf("firstFrameFileid", "thumbnailFileid")) {
                val fileId = image.optString(key).trim()
                if (fileId.isNotBlank()) {
                    normalizeUrl("https://ci.xiaohongshu.com/$fileId")?.let { return it }
                }
            }
        }
        // Last resort: the note's first image (video notes usually carry a cover there).
        return null
    }

    private fun videoDurationMs(video: JSONObject): Long {
        val media = video.optJSONObject("media")
        val h264Duration = media?.optJSONObject("stream")?.optJSONArray("h264")
            ?.optJSONObject(0)?.optLong("duration") ?: 0L
        if (h264Duration > 10_000L) return h264Duration
        val fromMedia = media?.optJSONObject("video")?.optLong("duration") ?: 0L
        val fromCapa = video.optJSONObject("capa")?.optLong("duration") ?: 0L
        val raw = fromMedia.takeIf { it > 0 } ?: fromCapa.takeIf { it > 0 } ?: return 0L
        // Xiaohongshu reports seconds (e.g. 128); tolerate millisecond payloads.
        return if (raw > 10_000L) raw else raw * 1000L
    }

    private fun videoDimensions(video: JSONObject): Pair<Int, Int> {
        val mediaV2 = video.optString("mediaV2")
        if (mediaV2.isNotBlank()) {
            try {
                val inner = JSONObject(mediaV2).optJSONObject("video")
                if (inner != null) {
                    val w = inner.optInt("width")
                    val h = inner.optInt("height")
                    if (w > 0 && h > 0) return w to h
                }
            } catch (_: JSONException) {
                // fall through
            }
        }
        val mediaVideo = video.optJSONObject("media")?.optJSONObject("video")
        return (mediaVideo?.optInt("width") ?: 0) to (mediaVideo?.optInt("height") ?: 0)
    }

    // ------------------------------------------------------------------ header

    private fun authorOf(note: JSONObject): String {
        val user = note.optJSONObject("user") ?: return ""
        return user.optString("nickname").trim().ifEmpty { user.optString("nickName").trim() }
    }

    private fun captionOf(note: JSONObject): String {
        val title = note.optString("title").trim()
        val desc = note.optString("desc").trim()
        val cleanedDesc = TAG_SUFFIX.replace(desc, "#")
        return when {
            title.isBlank() -> cleanedDesc
            cleanedDesc.isBlank() || cleanedDesc == title -> title
            else -> "$title\n\n$cleanedDesc"
        }
    }

    private fun normalizeUrl(raw: String?): String? {
        val url = raw?.trim().orEmpty()
        if (url.isEmpty()) return null
        return when {
            url.startsWith("//") -> "https:$url"
            url.startsWith("https://") -> url
            url.startsWith("http://") -> "https://${url.removePrefix("http://")}"
            else -> null
        }
    }

    private val QUALITY_RANK = mapOf("LD" to 1, "SD" to 2, "HD" to 3)
}
