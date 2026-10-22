package io.github.backtraxe.anysave.model

/** What one captured media asset is. */
enum class MediaKind { IMAGE, VIDEO }

/**
 * One previewable media item extracted from a page response.
 *
 * [url] is the best direct URL we could find (no-watermark version when the
 * site exposes one). [previewUrl] is what the grid loads: for images that is
 * the same URL, for videos it is the cover image. A video without a cover has
 * an empty [previewUrl] and the grid renders a placeholder.
 */
data class MediaItem(
    /** Stable key: aweme id + ordinal, used for selection state. */
    val id: String,
    val kind: MediaKind,
    val url: String,
    val previewUrl: String,
    val width: Int = 0,
    val height: Int = 0,
    val durationMs: Long = 0L,
    /** 1-based ordinal inside the post, matching the future download order. */
    val index: Int = 0,
    /**
     * Optional second URL to try when [url] fails. Used by readers that prefer
     * an original asset but can fall back to the site's display-transcoded copy.
     */
    val fallbackUrl: String = "",
    /**
     * Separate audio asset for sites that stream DASH tracks (bilibili). When
     * set, the saver downloads both and muxes them into one MP4.
     */
    val audioUrl: String = "",
) {
    val hasDimensions: Boolean get() = width > 0 && height > 0

    /** width / height, or 3:4 (the usual douyin image ratio) when unknown. */
    val aspectRatio: Float get() = if (hasDimensions) width.toFloat() / height else 0.75f
}

/**
 * Everything we can preview from one captured post: the author/caption header
 * plus the ordered media list.
 */
data class PostMedia(
    val awemeId: String,
    val author: String,
    val desc: String,
    /** The page URL the user extracted from, for attribution / future downloads. */
    val sourceUrl: String,
    val items: List<MediaItem>,
    /** Which site reader produced this post (drives file naming + CDN referer). */
    val site: MediaSite = MediaSite.DOUYIN,
) {
    val imageCount: Int get() = items.count { it.kind == MediaKind.IMAGE }
    val videoCount: Int get() = items.count { it.kind == MediaKind.VIDEO }

    val isEmpty: Boolean get() = items.isEmpty()

    val isImagePost: Boolean get() = imageCount > 0
}
