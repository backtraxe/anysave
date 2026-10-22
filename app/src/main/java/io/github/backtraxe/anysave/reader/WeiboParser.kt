package io.github.backtraxe.anysave.reader

import io.github.backtraxe.anysave.model.MediaItem
import io.github.backtraxe.anysave.model.MediaKind
import io.github.backtraxe.anysave.model.MediaSite
import io.github.backtraxe.anysave.model.PostMedia
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * Turns a Weibo post payload into a [PostMedia] (M8).
 *
 * The mobile post page (`m.weibo.cn/status/<bid>`, which `weibo.com/<uid>/<bid>`
 * redirects to) embeds `window.$render_data.status` with the whole post, so the
 * reader script returns a compact version of it:
 *
 * ```json
 * {"bid":"...","text":"...","user":{"screenName":"..."},
 *  "pics":[{"url":"...","width":1440,"height":1080}],
 *  "pageInfo":{"type":"video","pagePic":"...","durationSeconds":13.722,
 *              "urls":{"mp4_720p_mp4":"..."},"streamUrl":"...","streamUrlHd":"..."}}
 * ```
 *
 * The parser also accepts the raw `status` object (same field names in Weibo's
 * snake_case form) so captured fixtures can be tested directly.
 *
 * Media rules:
 * - images: `pics[].large.url` (sinaimg, needs a weibo Referer)
 * - video: best of `urls.mp4_720p_mp4 → mp4_hd_mp4 → mp4_ld_mp4 →
 *   media_info.stream_url_hd → stream_url`; duration from `media_info.duration`
 *   (seconds); cover from `page_info.page_pic.url`
 * - post text is HTML: `<br>` becomes a newline, emoticon `<img alt="[x]">`
 *   becomes `[x]`, other tags are stripped and entities decoded.
 */
object WeiboParser {
    private val BID_IN_URL = Regex("""(?:weibo\.com/[^/]+/|weibo\.com/detail/|m\.weibo\.cn/(?:status|detail)/)([0-9A-Za-z]+)""")
    private val IMG_TAG = Regex("""<img[^>]*?alt="([^"]*)"[^>]*>""", RegexOption.IGNORE_CASE)
    private val BR_TAG = Regex("""<br\s*/?>""", RegexOption.IGNORE_CASE)
    private val HTML_TAG = Regex("""<[^>]+>""")
    private val MULTI_SPACE = Regex("""[ \t]+""")
    private val MULTI_NEWLINE = Regex("""\n{3,}""")

    fun parse(body: String, sourceUrl: String): PostMedia? {
        if (body.isBlank()) return null
        val root = try {
            JSONObject(body)
        } catch (t: JSONException) {
            return null
        }
        val status = root.optJSONObject("status") ?: root
        val bid = status.optString("bid").trim().ifEmpty {
            status.optString("mid").trim().ifEmpty {
                BID_IN_URL.find(sourceUrl)?.groupValues?.getOrNull(1).orEmpty()
            }
        }
        if (bid.isBlank()) return null

        val text = cleanText(status.optString("text"))
        val author = authorOf(status)
        val pageInfo = status.optJSONObject("pageInfo") ?: status.optJSONObject("page_info")
        val pageType = pageInfo?.optString("type").orEmpty()

        val items = ArrayList<MediaItem>()
        val videoUrl = if (pageType.equals("video", ignoreCase = true) || pageInfo?.has("urls") == true) {
            videoUrl(pageInfo)
        } else {
            null
        }
        if (videoUrl != null) {
            val durationSeconds = pageInfo?.optDouble("durationSeconds", 0.0)
                ?.takeIf { it > 0 }
                ?: pageInfo?.optJSONObject("media_info")?.optDouble("duration", 0.0) ?: 0.0
            val cover = pagePic(pageInfo) ?: firstImageUrl(status).orEmpty()
            items += MediaItem(
                id = "$bid-video-1",
                kind = MediaKind.VIDEO,
                url = videoUrl,
                previewUrl = cover,
                durationMs = (durationSeconds * 1000.0).toLong(),
                index = 1,
            )
        } else {
            addImages(bid, status, items)
        }
        if (items.isEmpty()) return null

        return PostMedia(
            awemeId = bid,
            author = author,
            desc = text,
            sourceUrl = sourceUrl,
            items = items,
            site = MediaSite.WEIBO,
        )
    }

    private fun addImages(bid: String, status: JSONObject, items: MutableList<MediaItem>) {
        val pics = status.optJSONArray("pics") ?: return
        for (i in 0 until pics.length()) {
            val pic = pics.optJSONObject(i) ?: continue
            val url = imageUrl(pic) ?: continue
            val (width, height) = imageDimensions(pic)
            val index = items.size + 1
            items += MediaItem(
                id = "$bid-image-$index",
                kind = MediaKind.IMAGE,
                url = url,
                previewUrl = url,
                width = width,
                height = height,
                index = index,
            )
        }
    }

    private fun imageUrl(pic: JSONObject): String? {
        val large = pic.optJSONObject("large")
        normalizeUrl(large?.optString("url"))
            ?.let { return it }
        normalizeUrl(pic.optString("url"))?.let { return it }
        val info = pic.optJSONArray("infoList")
        if (info != null) {
            for (i in info.length() - 1 downTo 0) {
                normalizeUrl(info.optJSONObject(i)?.optString("url"))?.let { return it }
            }
        }
        return null
    }

    private fun imageDimensions(pic: JSONObject): Pair<Int, Int> {
        val geo = pic.optJSONObject("large")?.optJSONObject("geo") ?: pic.optJSONObject("geo")
        if (geo != null) {
            val width = geo.optString("width").toIntOrNull() ?: geo.optInt("width")
            val height = geo.optString("height").toIntOrNull() ?: geo.optInt("height")
            return width to height
        }
        val width = pic.optString("width").toIntOrNull() ?: pic.optInt("width")
        val height = pic.optString("height").toIntOrNull() ?: pic.optInt("height")
        return width to height
    }

    private fun videoUrl(pageInfo: JSONObject?): String? {
        pageInfo ?: return null
        val urls = pageInfo.optJSONObject("urls")
        for (key in listOf("mp4_720p_mp4", "mp4_hd_mp4", "mp4_ld_mp4")) {
            normalizeUrl(urls?.optString(key))?.let { return it }
        }
        normalizeUrl(pageInfo.optString("streamUrlHd"))?.let { return it }
        normalizeUrl(pageInfo.optString("streamUrl"))?.let { return it }
        val mediaInfo = pageInfo.optJSONObject("media_info")
        normalizeUrl(mediaInfo?.optString("stream_url_hd"))?.let { return it }
        normalizeUrl(mediaInfo?.optString("stream_url"))?.let { return it }
        return null
    }

    private fun pagePic(pageInfo: JSONObject?): String? {
        pageInfo ?: return null
        val compact = normalizeUrl(pageInfo.optString("pagePic"))
        if (compact != null) return compact
        val raw = pageInfo.optJSONObject("page_pic")
        return normalizeUrl(raw?.optString("url"))
    }

    private fun firstImageUrl(status: JSONObject): String? {
        val pics = status.optJSONArray("pics") ?: return null
        for (i in 0 until pics.length()) {
            val pic = pics.optJSONObject(i) ?: continue
            imageUrl(pic)?.let { return it }
        }
        return null
    }

    private fun authorOf(status: JSONObject): String {
        val user = status.optJSONObject("user") ?: return ""
        return user.optString("screenName").trim().ifEmpty { user.optString("screen_name").trim() }
    }

    /** Weibo text is HTML; make it readable without shipping an HTML parser. */
    internal fun cleanText(html: String): String {
        if (html.isBlank()) return ""
        var text = IMG_TAG.replace(html) { match -> " ${match.groupValues[1]} " }
        text = BR_TAG.replace(text, "\n")
        text = HTML_TAG.replace(text, "")
        text = decodeEntities(text)
        text = MULTI_SPACE.replace(text, " ")
        text = MULTI_NEWLINE.replace(text, "\n\n")
        return text.trim()
    }

    private fun decodeEntities(text: String): String {
        var out = text
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&apos;", "'")
        out = Regex("""&#(\d+);""").replace(out) { match ->
            match.groupValues[1].toIntOrNull()?.toChar()?.toString() ?: match.value
        }
        out = Regex("""&#x([0-9a-fA-F]+);""").replace(out) { match ->
            match.groupValues[1].toIntOrNull(16)?.toChar()?.toString() ?: match.value
        }
        return out
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
}
