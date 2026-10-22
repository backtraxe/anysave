package io.github.backtraxe.anysave.reader

import io.github.backtraxe.anysave.model.DESKTOP_CHROME_UA
import io.github.backtraxe.anysave.model.PostMedia

/**
 * Xiaohongshu reader (M4).
 *
 * Note pages are server-rendered: the note JSON lives in
 * `window.__INITIAL_STATE__.note.noteDetailMap`. There is no detail XHR to
 * observe, so this reader has no [responsePatterns] and relies entirely on the
 * embedded-data script (read-only: it never issues a request).
 *
 * Note URLs must carry `xsec_token` (bare `/explore/<id>` links are redirected
 * to a 404/risk page by xiaohongshu itself).
 */
object XiaohongshuReader : SiteReader {
    override val id = "xiaohongshu"
    override val origin = "https://www.xiaohongshu.com"
    override val responsePatterns: List<String> = emptyList()

    /**
     * The mobile UA gets the app-oriented page which, under anti-bot pressure,
     * diverges to `/404/sec_*` risk pages. The desktop page carries the same
     * `note.noteDetailMap` data and is the layout a logged-in browser shows.
     */
    override val pageUserAgent: String = DESKTOP_CHROME_UA

    /**
     * Short share links (`xhslink.cn` / `xhslink.com`) 307-redirect to the
     * canonical note URL on www.xiaohongshu.com, so they must map to this
     * reader as well; the hook is installed for the redirect target below.
     */
    override val originAliases: List<String> = listOf("https://www.xiaohongshu.com")

    override fun matches(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains("xiaohongshu.com") ||
            lower.contains("xhslink.cn") ||
            lower.contains("xhslink.com")
    }

    override fun parse(body: String, sourceUrl: String): PostMedia? =
        XiaohongshuParser.parse(body, sourceUrl)

    /**
     * Returns `{noteId, note}` for the note on the current page, or the whole
     * `__INITIAL_STATE__` JSON when the runtime object is unavailable (Kotlin
     * normalizes the JS `undefined` tokens before parsing).
     */
    override val embeddedPayloadScript: String = """
        (function () {
          try {
            var path = (location && location.pathname) || '';
            var m = /\/(?:explore|discovery\/item)\/([0-9a-zA-Z]+)/.exec(path);
            var pageId = m ? m[1] : '';
            var st = window.__INITIAL_STATE__;
            if (st && st.note && st.note.noteDetailMap) {
              var map = st.note.noteDetailMap;
              var keys = Object.keys(map);
              var id = (pageId && map[pageId]) ? pageId : keys[0];
              if (id && map[id] && map[id].note) {
                return JSON.stringify({ noteId: id, note: map[id].note });
              }
            }
            if (st && st.noteData) {
              var data = st.noteData.data ? st.noteData.data : st.noteData;
              var mobileNote = data && data.noteData;
              if (mobileNote && (mobileNote.imageList || mobileNote.video)) {
                return JSON.stringify({
                  noteId: mobileNote.noteId || pageId || '',
                  note: mobileNote
                });
              }
            }
            var html = document.documentElement.innerHTML;
            var idx = html.indexOf('window.__INITIAL_STATE__');
            if (idx < 0) return '';
            var brace = html.indexOf('{', idx);
            if (brace < 0) return '';
            var depth = 0;
            var inStr = false;
            var esc = false;
            for (var i = brace; i < html.length; i++) {
              var c = html.charAt(i);
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
                if (depth === 0) return html.slice(brace, i + 1);
              }
            }
            return '';
          } catch (e) {
            return '';
          }
        })()
    """.trimIndent()
}
