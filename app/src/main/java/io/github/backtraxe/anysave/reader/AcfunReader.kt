package io.github.backtraxe.anysave.reader

import io.github.backtraxe.anysave.model.PostMedia

/**
 * AcFun article reader (M10).
 *
 * Article pages (`/a/ac<id>`) are server-rendered and expose
 * `window.articleInfo` (title, author, cover, parts[].content). Public pages
 * need no login; images are on `imgs.aixifan.com` / `tx-free-imgs.acfun.cn`.
 */
object AcfunReader : SiteReader {
    override val id = "acfun"
    override val origin = "https://www.acfun.cn"
    override val responsePatterns: List<String> = emptyList()

    override fun matches(url: String): Boolean =
        url.contains("acfun.cn/a/ac") || url.contains("m.acfun.cn/a/ac")

    override fun parse(body: String, sourceUrl: String): PostMedia? =
        AcfunParser.parse(body, sourceUrl)

    override val embeddedPayloadScript: String = """
        (function () {
          try {
            var a = window.articleInfo;
            if (!a) return '';
            var out = {
              articleId: a.articleId || '',
              title: a.title || '',
              author: ((a.user || {}).name) || '',
              coverUrl: a.coverUrl || '',
              parts: (a.parts || []).map(function (p) { return p.content || ''; })
            };
            return JSON.stringify(out);
          } catch (e) {
            return '';
          }
        })()
    """.trimIndent()
}
