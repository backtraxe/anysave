package io.github.backtraxe.anysave.reader

import io.github.backtraxe.anysave.model.MediaItem
import io.github.backtraxe.anysave.model.MediaKind
import io.github.backtraxe.anysave.model.PostMedia
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * Turns one captured douyin `aweme_detail` response into a [PostMedia].
 *
 * Field knowledge lives here (and in the tests), not in the hook:
 * - 图文 posts expose `images[]`; each image has `url_list` (display, usually
 *   watermark-free) and `download_url_list` (often the watermarked variant).
 *   URLs containing the `dy-water` pipeline marker are skipped when a clean
 *   alternative exists, and jpeg wins over webp at the same quality tier.
 * - Video posts expose `video.play_addr.url_list` (web player CDN, no
 *   watermark); `video.bit_rate[]` tiers are the fallback. `playwm` URLs are
 *   watermarked and always skipped.
 * - Live-photo videos (`images[i].video`) are intentionally ignored in M1.
 *
 * The parser is pure: no Android APIs, so it is covered by JVM unit tests.
 */
object DouyinParser {
    private const val WATERMARK_MARK = "dy-water"
    private const val PLAYWM_MARK = "playwm"

    /** Page URLs look like /video/<id> or /note/<id>. */
    private val AWEME_ID_IN_URL = Regex("/(?:video|note)/(\\d{6,})")

    fun parse(body: String, sourceUrl: String): PostMedia? {
        if (body.isBlank()) return null
        val detail = try {
            detailOf(body)
        } catch (t: JSONException) {
            null
        } ?: return null

        val awemeId = firstNonBlank(detail, "aweme_id", "awemeId")
        val fallbackId = AWEME_ID_IN_URL.find(sourceUrl)?.groupValues?.getOrNull(1).orEmpty()
        val id = awemeId.ifEmpty { fallbackId }

        val items = ArrayList<MediaItem>()
        val images = detail.optJSONArray("images")
        if (images != null) {
            for (i in 0 until images.length()) {
                val image = images.optJSONObject(i) ?: continue
                val url = pickImageUrl(image) ?: continue
                val index = items.size + 1
                items += MediaItem(
                    id = "$id-image-$index",
                    kind = MediaKind.IMAGE,
                    url = url,
                    previewUrl = url,
                    width = image.optInt("width"),
                    height = image.optInt("height"),
                    index = index,
                )
            }
        }

        val video = detail.optJSONObject("video")
        if (video != null) {
            val playUrl = pickVideoUrl(video)
            val durationMs = video.optLong("duration")
            // 图文 notes also carry a `video` object (duration 0, static asset)
            // that is not a real video; only treat it as media when the post
            // has no images or when it is an actual clip attached to the note.
            val isRealVideo = images == null || images.length() == 0 || durationMs > 0L
            if (playUrl != null && isRealVideo) {
                val index = items.size + 1
                items += MediaItem(
                    id = "$id-video-$index",
                    kind = MediaKind.VIDEO,
                    url = playUrl,
                    previewUrl = pickCoverUrl(video),
                    width = video.optInt("width"),
                    height = video.optInt("height"),
                    durationMs = durationMs,
                    index = index,
                )
            }
        }

        if (items.isEmpty()) return null
        return PostMedia(
            awemeId = id,
            author = authorOf(detail),
            desc = detail.optString("desc").trim(),
            sourceUrl = sourceUrl,
            items = items,
        )
    }

    private fun detailOf(body: String): JSONObject? {
        val root = JSONObject(body)
        root.optJSONObject("aweme_detail")?.let { if (looksLikeDetail(it)) return it }
        // SSR note pages embed the same payload in camelCase under aweme.detail.
        root.optJSONObject("aweme")?.optJSONObject("detail")?.let {
            if (looksLikeDetail(it)) return it
        }
        return null
    }

    private fun looksLikeDetail(detail: JSONObject): Boolean {
        if (detail.length() == 0) return false
        val hasId = firstNonBlank(detail, "aweme_id", "awemeId").isNotEmpty()
        return hasId || detail.has("images") || detail.has("video")
    }

    private fun authorOf(detail: JSONObject): String {
        val author = detail.optJSONObject("authorInfo")
            ?: detail.optJSONObject("author")
            ?: return ""
        return firstNonBlank(author, "nickname", "uniqueId", "unique_id")
    }

    private fun pickImageUrl(image: JSONObject): String? {
        val candidates = urlArray(image, "download_url_list", "downloadUrlList") +
            urlArray(image, "url_list", "urlList")
        val valid = candidates.mapNotNull(::normalizeUrl)
        if (valid.isEmpty()) return null
        val clean = valid.filterNot { it.contains(WATERMARK_MARK) }
        val pool = clean.ifEmpty { valid }
        return pool.firstOrNull(::isJpeg) ?: pool.first()
    }

    private fun pickVideoUrl(video: JSONObject): String? {
        for (key in PLAY_KEYS) {
            val node = video.opt(key) ?: continue
            firstPlayable(urlsOf(node))?.let { return it }
        }
        for (key in BITRATE_KEYS) {
            val tiers = video.optJSONArray(key) ?: continue
            val ranked = (0 until tiers.length())
                .mapNotNull { tiers.optJSONObject(it) }
                .sortedByDescending { it.optLong("bit_rate", it.optLong("bitRate")) }
            for (tier in ranked) {
                val node = tier.opt("play_addr") ?: tier.opt("playAddr") ?: continue
                firstPlayable(urlsOf(node))?.let { return it }
            }
        }
        return null
    }

    private fun pickCoverUrl(video: JSONObject): String {
        val candidates = ArrayList<String>()
        for (key in COVER_KEYS) {
            val node = video.opt(key) ?: continue
            candidates += urlsOf(node)
        }
        val valid = candidates.mapNotNull(::normalizeUrl)
        val clean = valid.filterNot { it.contains(WATERMARK_MARK) }
        return (clean.ifEmpty { valid }).firstOrNull().orEmpty()
    }

    private fun firstPlayable(urls: List<String>): String? =
        urls.mapNotNull(::normalizeUrl)
            .firstOrNull { !it.contains(PLAYWM_MARK) }

    private fun urlArray(obj: JSONObject, vararg keys: String): List<String> {
        val out = ArrayList<String>(keys.size * 3)
        for (key in keys) {
            val array: JSONArray = obj.optJSONArray(key) ?: continue
            for (i in 0 until array.length()) {
                val value = array.optString(i)
                if (value.isNotEmpty()) out += value
            }
        }
        return out
    }

    /**
     * Collects urls from either JSON shape the site uses:
     * API (`{"url_list": [...]}`), camelCase (`{"urlList": [...]}`) or the
     * SSR variant (`[{"src": "..."}]`, or a bare string).
     */
    private fun urlsOf(node: Any?): List<String> = when (node) {
        is String -> listOf(node)
        is JSONArray -> (0 until node.length()).flatMap { urlsOf(node.opt(it)) }
        is JSONObject -> {
            val direct = urlArray(node, "url_list", "urlList", "src", "url")
            if (direct.isNotEmpty()) {
                direct
            } else {
                node.keys().asSequence().flatMap { urlsOf(node.opt(it)) }.toList()
            }
        }

        else -> emptyList()
    }

    private fun firstNonBlank(obj: JSONObject, vararg keys: String): String {
        for (key in keys) {
            val value = obj.optString(key).trim()
            if (value.isNotEmpty()) return value
        }
        return ""
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

    private fun isJpeg(url: String): Boolean {
        val path = url.substringBefore('?').lowercase()
        return path.contains(".jpeg") || path.contains(".jpg")
    }

    private val PLAY_KEYS = listOf("play_addr", "playAddr", "playApi", "play_api")

    private val BITRATE_KEYS = listOf("bit_rate", "bitRate", "bitRateList")

    private val COVER_KEYS = listOf(
        "cover",
        "origin_cover",
        "originCover",
        "cover_url",
        "coverUrl",
        "coverUrlList",
        "originCoverUrlList",
        "dynamic_cover",
        "dynamicCover",
    )
}
