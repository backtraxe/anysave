package io.github.backtraxe.anysave.model

import org.junit.Assert.assertEquals
import org.junit.Test

class MediaSiteTest {

    @Test
    fun `resolves site from page url`() {
        assertEquals(
            MediaSite.XIAOHONGSHU,
            MediaSite.forPageUrl("https://www.xiaohongshu.com/explore/abc?xsec_token=x"),
        )
        assertEquals(MediaSite.DOUYIN, MediaSite.forPageUrl("https://www.douyin.com/video/1"))
        assertEquals(MediaSite.BILIBILI, MediaSite.forPageUrl("https://www.bilibili.com/video/BV1"))
        assertEquals(MediaSite.DOUYIN, MediaSite.forPageUrl("https://example.com/x"))
    }

    @Test
    fun `resolves referer from media host`() {
        assertEquals(
            MediaSite.XIAOHONGSHU.referer,
            MediaSite.refererForMediaUrl("https://sns-webpic-qc.xhscdn.com/a.webp"),
        )
        assertEquals(
            MediaSite.XIAOHONGSHU.referer,
            MediaSite.refererForMediaUrl("https://ci.xiaohongshu.com/file"),
        )
        assertEquals(
            MediaSite.DOUYIN.referer,
            MediaSite.refererForMediaUrl("https://p3-pc-sign.douyinpic.com/a.jpeg"),
        )
        assertEquals(
            MediaSite.BILIBILI.referer,
            MediaSite.refererForMediaUrl("https://i0.hdslb.com/bfs/cover.jpg"),
        )
        assertEquals(
            MediaSite.BILIBILI.referer,
            MediaSite.refererForMediaUrl("https://upos-sz-mirror.bilivideo.com/a.m4s"),
        )
    }

    @Test
    fun `ids are stable filename prefixes`() {
        assertEquals("douyin", MediaSite.DOUYIN.id)
        assertEquals("xiaohongshu", MediaSite.XIAOHONGSHU.id)
        assertEquals("bilibili", MediaSite.BILIBILI.id)
        assertEquals(DESKTOP_CHROME_UA, MediaSite.BILIBILI.downloadUserAgent)
        assertEquals(null, MediaSite.DOUYIN.downloadUserAgent)
    }
}
