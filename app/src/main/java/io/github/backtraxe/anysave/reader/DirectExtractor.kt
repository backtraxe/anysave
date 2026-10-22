package io.github.backtraxe.anysave.reader

import io.github.backtraxe.anysave.model.PostMedia

/**
 * A site that cannot be read by observing a WebView, but has a dedicated
 * extractor implementation. Used for YouTube (M8), whose player URLs are
 * signature-ciphered and only resolvable by a dedicated extractor.
 */
interface DirectExtractor {
    val id: String
    fun matches(url: String): Boolean

    /** Blocking; call on a background thread. @return null when extraction fails. */
    fun extract(url: String): PostMedia?
}

object DirectExtractors {
    private val extractors: List<DirectExtractor> = listOf(YouTubeExtractor)

    fun forUrl(url: String): DirectExtractor? =
        extractors.firstOrNull { it.matches(url) }

    fun all(): List<DirectExtractor> = extractors
}
