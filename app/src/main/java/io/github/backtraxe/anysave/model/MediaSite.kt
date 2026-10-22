package io.github.backtraxe.anysave.model

/**
 * Sites AnySave knows how to read.
 *
 * The site owns two things the download/preview layers need:
 * - [id]: filename prefix for saved files (`douyin_...`, `xiaohongshu_...`)
 * - [referer]: the page Referer that the site's media CDN expects when the
 *   page itself requests the asset (CDNs sometimes reject missing referers).
 */
enum class MediaSite(
    val id: String,
    val referer: String,
) {
    DOUYIN("douyin", "https://www.douyin.com/"),
    XIAOHONGSHU("xiaohongshu", "https://www.xiaohongshu.com/"),
    BILIBILI("bilibili", "https://www.bilibili.com/"),
    ;

    companion object {
        fun forPageUrl(url: String): MediaSite = when {
            url.contains("xiaohongshu.com") -> XIAOHONGSHU
            url.contains("bilibili.com") -> BILIBILI
            else -> DOUYIN
        }

        /**
         * Referer for a media URL, resolved from the media host. Used by the
         * thumbnail loader, which only sees one URL at a time.
         */
        fun refererForMediaUrl(url: String): String = when {
            url.contains("xhscdn.com") || url.contains("xiaohongshu.com") -> XIAOHONGSHU.referer
            url.contains("hdslb.com") || url.contains("bilivideo") ||
                url.contains("bilibili.com") -> BILIBILI.referer

            else -> DOUYIN.referer
        }
    }
}
