package io.github.backtraxe.anysave.reader

import io.github.backtraxe.anysave.model.DESKTOP_CHROME_UA
import io.github.backtraxe.anysave.settings.BilibiliQualityConfig
import io.github.backtraxe.anysave.model.PostMedia

/**
 * Bilibili reader (M5).
 *
 * Video pages are server-rendered: `__INITIAL_STATE__.videoData` plus
 * `__playinfo__` (DASH manifest) are embedded in the HTML. No playurl XHR is
 * visible on the www page, so this reader has no [responsePatterns] and reads
 * the embedded data only (read-only, never issues a request).
 *
 * The DASH streams are separate video/audio tracks; the save layer muxes them
 * into one MP4. Short links (b23.tv) are not resolved yet.
 */
object BilibiliReader : SiteReader {
    override val id = "bilibili"
    override val origin = "https://www.bilibili.com"
    override val responsePatterns: List<String> = emptyList()

    /** Android WebView UA triggers bilibili's app-open flow; use desktop Chrome. */
    override val pageUserAgent: String = DESKTOP_CHROME_UA

    override fun matches(url: String): Boolean =
        url.contains("www.bilibili.com/video/") || url.contains("bilibili.com/video/BV")

    override fun parse(body: String, sourceUrl: String): PostMedia? =
        BilibiliParser.parse(body, sourceUrl, BilibiliQualityConfig.maxHeight)

    override val embeddedPayloadScript: String = """
        (function () {
          try {
            var st = window.__INITIAL_STATE__;
            var vd = st && (st.videoData || (st.video && st.video.videoData));
            if (!vd) return '';
            var pi = window.__playinfo__;
            var pdata = pi && (pi.data ? pi.data : pi);
            if (!pdata || !pdata.dash) return '';
            var payload = {
              videoData: {
                bvid: vd.bvid,
                cid: vd.cid,
                title: vd.title,
                desc: vd.desc,
                duration: vd.duration,
                pic: vd.pic,
                owner: { name: (vd.owner || {}).name }
              },
              playinfo: {
                quality: pdata.quality,
                timelength: pdata.timelength,
                dash: {
                  duration: pdata.dash.duration,
                  video: pdata.dash.video,
                  audio: pdata.dash.audio
                }
              }
            };
            return JSON.stringify(payload);
          } catch (e) {
            return '';
          }
        })()
    """.trimIndent()
}
