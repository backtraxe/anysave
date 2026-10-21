package io.github.backtraxe.anysave.reader

/**
 * Douyin reader (M0).
 *
 * No a_bogus / msToken / x-secsdk-web-signature is computed here. The page
 * ships bdms.js, which signs every /aweme/v1/web/aweme/detail/ request made
 * from its own context. We only capture the response; see
 * docs/verification.md for the controlled experiment that proved this.
 *
 * Known limitation for M0: only www.douyin.com pages are handled. Short links
 * (v.douyin.com) need to be resolved before injection.
 */
object DouyinReader : SiteReader {
    override val id = "douyin"
    override val origin = "https://www.douyin.com"
    override val responsePatterns = listOf("/aweme/v1/web/aweme/detail/")

    override fun matches(url: String): Boolean =
        url.contains("www.douyin.com") || url.contains("://douyin.com")
}
