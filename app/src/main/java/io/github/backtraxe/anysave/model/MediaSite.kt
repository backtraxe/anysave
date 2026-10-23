package io.github.backtraxe.anysave.model

/**
 * Sites AnySave knows how to read.
 *
 * The site owns two things the download/preview layers need:
 * - [id]: filename prefix for saved files (`douyin_...`, `xiaohongshu_...`)
 * - [referer]: the page Referer that the site's media CDN expects when the
 *   page itself requests the asset (CDNs sometimes reject missing referers).
 */
/** Desktop Chrome UA: bilibili page loads and CDN downloads require a non-mobile UA. */
const val DESKTOP_CHROME_UA: String =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/150.0.0.0 Safari/537.36"

enum class MediaSite(
    val id: String,
    val referer: String,
    /** Some CDNs reject the app's mobile UA; null keeps the default UA. */
    val downloadUserAgent: String? = null,
) {
    DOUYIN("douyin", "https://www.douyin.com/"),
    XIAOHONGSHU("xiaohongshu", "https://www.xiaohongshu.com/"),
    /**
     * bilibili serves an app-open mobile flow to Android WebView UAs (which
     * aborts navigation via an external-browser fallback) and its media CDN
     * 403s the mobile UA. Page loads and downloads for this site use a
     * desktop Chrome UA instead.
     */
    BILIBILI("bilibili", "https://www.bilibili.com/", DESKTOP_CHROME_UA),
    /** YouTube via dedicated extractor; googlevideo likes a desktop UA too. */
    YOUTUBE("youtube", "https://www.youtube.com/", DESKTOP_CHROME_UA),
    /** Weibo media CDNs require a weibo Referer. */
    WEIBO("weibo", "https://weibo.com/"),
    /** AcFun images live on aixifan/tx-free CDNs. */
    ACFUN("acfun", "https://www.acfun.cn/"),
    ;

    /** Human/store label used for Telegram platform tags and error copy. */
    val displayName: String
        get() = when (this) {
            DOUYIN -> "抖音"
            XIAOHONGSHU -> "小红书"
            BILIBILI -> "Bilibili"
            YOUTUBE -> "YouTube"
            WEIBO -> "微博"
            ACFUN -> "AcFun"
        }

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

            url.contains("ytimg.com") || url.contains("googlevideo.com") ||
                url.contains("youtube.com") || url.contains("ggpht.com") -> YOUTUBE.referer

            url.contains("sinaimg.cn") || url.contains("weibocdn.com") ||
                url.contains("weibo.cn") || url.contains("weibo.com") -> WEIBO.referer

            url.contains("aixifan.com") || url.contains("acfun.cn") -> ACFUN.referer

            else -> DOUYIN.referer
        }
    }
}
