package io.github.backtraxe.anysave.reader

/**
 * A site reader describes how to observe one site inside a real WebView.
 *
 * A reader is intentionally dumb: it only declares the origin to inject into
 * and the response URL patterns that carry the data we need. Parsing the
 * captured JSON into media items is the next layer (see docs/architecture.md).
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

    companion object {
        private val readers: List<SiteReader> = listOf(DouyinReader)

        fun forUrl(url: String): SiteReader? = readers.firstOrNull { it.matches(url) }

        fun all(): List<SiteReader> = readers
    }
}
