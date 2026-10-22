package io.github.backtraxe.anysave.reader

import io.github.backtraxe.anysave.model.MediaItem
import io.github.backtraxe.anysave.model.MediaKind
import io.github.backtraxe.anysave.model.MediaSite
import io.github.backtraxe.anysave.model.PostMedia
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * AcFun article reader (M10).
 *
 * `https://www.acfun.cn/a/ac<id>` is server-rendered: the page exposes
 * `window.articleInfo` with title, author, cover and `parts[].content` HTML.
 * Images are embedded in that HTML (`<img src="https://imgs.aixifan.com/...">`);
 * no login and no API replay needed. (AcFun *videos* use HLS, which the download
 * pipeline does not handle yet, so this reader only covers articles.)
 *
 * Compact payload returned by [AcfunReader]:
 * `{"articleId":"48924768","title":"...","author":"AcFun活动","cover":"https://...","parts":["<p>...<img src=...>"]}`
 */
object AcfunParser {
    private val IMG_SRC = Regex(
        """<img[^>]*?\ssrc=["']([^"']+)["']""",
        RegexOption.IGNORE_CASE,
    )
    private val ARTICLE_ID_IN_URL = Regex("""acfun\.cn/a/ac(\d+)""")

    fun parse(body: String, sourceUrl: String): PostMedia? {
        if (body.isBlank()) return null
        val root = try {
            JSONObject(body)
        } catch (t: JSONException) {
            return null
        }
        val articleId = root.optString("articleId").trim().ifEmpty {
            ARTICLE_ID_IN_URL.find(sourceUrl)?.groupValues?.getOrNull(1).orEmpty()
        }
        if (articleId.isBlank()) return null

        val title = cleanText(root.optString("title")).ifBlank { "AcFun article" }
        val author = root.optString("author").trim().ifEmpty {
            root.optJSONObject("user")?.optString("name").orEmpty().trim()
        }

        val imageUrls = LinkedHashSet<String>()
        val parts = root.optJSONArray("parts")
        if (parts != null) {
            for (i in 0 until parts.length()) {
                // The reader script emits strings; hand-saved fixtures may keep
                // AcFun's {title, content} objects, so accept both shapes.
                val part = parts.opt(i)
                val content = (part as? JSONObject)?.optString("content") ?: parts.optString(i)
                if (content.isNotBlank()) {
                    IMG_SRC.findAll(content).forEach { match ->
                        normalizeUrl(match.groupValues[1])?.let { imageUrls += it }
                    }
                }
            }
        }
        normalizeUrl(root.optString("coverUrl").ifBlank { root.optString("cover") })?.let { imageUrls += it }

        if (imageUrls.isEmpty()) return null
        val awemeId = "ac$articleId"
        val items = imageUrls.mapIndexed { index, url ->
            MediaItem(
                id = "$awemeId-image-${index + 1}",
                kind = MediaKind.IMAGE,
                url = url,
                previewUrl = url,
                index = index + 1,
            )
        }
        return PostMedia(
            awemeId = awemeId,
            author = author,
            desc = title,
            sourceUrl = sourceUrl,
            items = items,
            site = MediaSite.ACFUN,
        )
    }

    private fun cleanText(html: String): String = html
        .replace(Regex("""<br\s*/?>""", RegexOption.IGNORE_CASE), " ")
        .replace(Regex("""<[^>]+>"""), "")
        .replace("&nbsp;", " ")
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .trim()

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
