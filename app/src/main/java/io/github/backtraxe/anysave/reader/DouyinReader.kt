package io.github.backtraxe.anysave.reader

import io.github.backtraxe.anysave.model.DESKTOP_CHROME_UA
import io.github.backtraxe.anysave.model.PostMedia

/**
 * Douyin reader (M1).
 *
 * No a_bogus / msToken / x-secsdk-web-signature is computed here. The page
 * ships bdms.js, which signs every /aweme/v1/web/aweme/detail/ request made
 * from its own context. We only capture the response and parse it; see
 * docs/verification.md for the controlled experiment that proved this.
 *
 * Note pages (`/note/<id>`) are server-rendered and never issue that request.
 * For them we read the detail payload the page already embedded in its React
 * Server Components data (`window.__pace_f`) — still pure observation, no new
 * request. See [embeddedPayloadScript] and docs/verification.md.
 *
 * Known limitation: only www.douyin.com pages are handled. Short links
 * (v.douyin.com) need to be resolved before injection.
 */
object DouyinReader : SiteReader {
    override val id = "douyin"
    override val origin = "https://www.douyin.com"
    override val responsePatterns = listOf("/aweme/v1/web/aweme/detail/")

    /** Logged-in douyin redirects mobile UAs to m.douyin.com/share; desktop stays www. */
    override val pageUserAgent: String = DESKTOP_CHROME_UA
    override val originAliases: List<String> = listOf("https://m.douyin.com")

    override fun matches(url: String): Boolean =
        url.contains("www.douyin.com") || url.contains("://douyin.com")

    override fun parse(body: String, sourceUrl: String): PostMedia? =
        DouyinParser.parse(body, sourceUrl)

    /**
     * Walks the page's RSC flight chunks, finds `"awemeId":"<id>"` for the
     * current page and returns the balanced `"aweme": {...}` object as JSON.
     * The payload is the same camelCase detail the site renders with.
     */
    override val embeddedPayloadScript: String = """
        (function () {
          try {
            var pace = window.__pace_f;
            if (!pace || !pace.length) return '';
            var parts = [];
            for (var i = 0; i < pace.length; i++) {
              var entry = pace[i];
              var s = '';
              if (Array.isArray(entry)) {
                for (var j = 0; j < entry.length; j++) {
                  if (typeof entry[j] === 'string') s += entry[j];
                }
              } else if (typeof entry === 'string') {
                s = entry;
              }
              if (s) parts.push(s);
            }
            var blob = parts.join('');
            var path = (location && location.pathname) || '';
            var m = /(?:video|note)\/(\d{6,})/.exec(path);
            if (!m) return '';
            var idPos = blob.indexOf('"awemeId":"' + m[1] + '"');
            if (idPos < 0) return '';
            var awemePos = blob.indexOf('"aweme":', idPos);
            if (awemePos < 0) return '';
            var start = blob.indexOf('{', awemePos);
            if (start < 0) return '';
            var depth = 0;
            var inStr = false;
            var esc = false;
            for (var k = start; k < blob.length; k++) {
              var c = blob.charAt(k);
              if (inStr) {
                if (esc) esc = false;
                else if (c === '\\') esc = true;
                else if (c === '"') inStr = false;
                continue;
              }
              if (c === '"') { inStr = true; continue; }
              if (c === '{') depth++;
              else if (c === '}') {
                depth--;
                if (depth === 0) {
                  return '{"aweme":' + blob.slice(start, k + 1) + '}';
                }
              }
            }
            return '';
          } catch (e) {
            return '';
          }
        })()
    """.trimIndent()
}
