package io.github.backtraxe.anysave.reader

import io.github.backtraxe.anysave.model.PostMedia

/**
 * Weibo reader (M8).
 *
 * `weibo.com/<uid>/<bid>` redirects to `m.weibo.cn/status/<bid>`, whose HTML is
 * server-rendered with `window.$render_data.status`. No login is needed for
 * public posts (Weibo sets visitor cookies automatically). The reader only reads
 * the embedded data; no response patterns are needed.
 */
object WeiboReader : SiteReader {
    override val id = "weibo"
    override val origin = "https://weibo.com"
    override val responsePatterns: List<String> = emptyList()

    override fun matches(url: String): Boolean =
        url.contains("weibo.com/") ||
            url.contains("m.weibo.cn/status/") ||
            url.contains("m.weibo.cn/detail/")

    override fun parse(body: String, sourceUrl: String): PostMedia? =
        WeiboParser.parse(body, sourceUrl)

    override val embeddedPayloadScript: String = """
        (function () {
          try {
            var rd = window['${'$'}render_data'] || window.__render_data;
            var st = rd && rd.status;
            if (!st) return '';
            var pics = (st.pics || []).map(function (p) {
              var large = p.large || {};
              var geo = large.geo || {};
              return {
                url: large.url || p.url || '',
                width: parseInt(geo.width || p.width || 0, 10),
                height: parseInt(geo.height || p.height || 0, 10)
              };
            });
            var pi = st.page_info || {};
            var mi = pi.media_info || {};
            var out = {
              bid: st.bid || '',
              mid: st.mid || st.id || '',
              text: st.text || '',
              user: { screenName: ((st.user || {}).screen_name) || '' },
              pics: pics,
              pageInfo: {
                type: pi.type || '',
                pagePic: ((pi.page_pic || {}).url) || '',
                durationSeconds: mi.duration || 0,
                urls: pi.urls || {},
                streamUrl: mi.stream_url || '',
                streamUrlHd: mi.stream_url_hd || ''
              }
            };
            return JSON.stringify(out);
          } catch (e) {
            return '';
          }
        })()
    """.trimIndent()
}
