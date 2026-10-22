package io.github.backtraxe.anysave.reader

import io.github.backtraxe.anysave.model.PostMedia

/**
 * A site reader describes how to observe one site inside a real WebView.
 *
 * A reader declares the origin to inject into, the response URL patterns that
 * carry the data we need, and how to turn one captured response body into a
 * previewable post. It never computes signatures or builds requests.
 */
interface SiteReader {
    /** Stable reader id, also used for reader packs later. */
    val id: String

    /** Origin the document-start script is allowed to run on. */
    val origin: String

    /** URL substrings that identify useful responses. */
    val responsePatterns: List<String>

    /** Whether this reader handles the given page URL. */
    fun matches(url: String): Boolean

    /**
     * Parse one captured response body into a previewable post.
     *
     * @param body raw response text captured by the page-context hook.
     * @param sourceUrl page URL the response belongs to.
     * @return the post, or null when this body is not a post payload.
     */
    fun parse(body: String, sourceUrl: String): PostMedia? = null

    /**
     * Optional UA override for loading this site's pages. Some sites (bilibili)
     * detect the Android WebView UA and abort the in-app navigation; a desktop
     * UA keeps the page in our WebView.
     */
    val pageUserAgent: String? get() = null

    /**
     * Optional JavaScript expression that returns a JSON payload the page
     * already embedded in its own markup (server-side rendered data), or an
     * empty string. This is a read-only fallback for pages that never issue the
     * response we observe (douyin note pages are server-rendered this way).
     */
    val embeddedPayloadScript: String? get() = null

    companion object {
        private val readers: List<SiteReader> = listOf(DouyinReader, XiaohongshuReader, BilibiliReader)

        fun forUrl(url: String): SiteReader? = readers.firstOrNull { it.matches(url) }

        fun all(): List<SiteReader> = readers
    }
}
