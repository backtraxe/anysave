package io.github.backtraxe.anysave.reader

import io.github.backtraxe.anysave.model.MediaKind
import io.github.backtraxe.anysave.model.MediaSite
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BilibiliParserTest {

    @Test
    fun `parses real bilibili video payload into a dash video item`() {
        val body = resource("bilibili/bili-video-real.json")
        val post = BilibiliParser.parse(body, "https://www.bilibili.com/video/BV1GJ411x7h7")!!

        assertEquals("BV1GJ411x7h7", post.awemeId)
        assertEquals("索尼音乐中国", post.author)
        assertEquals("【官方 MV】Never Gonna Give You Up - Rick As", post.desc.take(40))
        assertEquals(MediaSite.BILIBILI, post.site)
        assertEquals(1, post.items.size)
        assertEquals(1, post.videoCount)

        val video = post.items.single()
        assertEquals(MediaKind.VIDEO, video.kind)
        // AVC rendition, 480P (id 32), highest bandwidth among avc1 tracks.
        assertTrue(video.url.startsWith("https://"))
        assertTrue(video.url.contains(".m4s"))
        assertEquals(852, video.width)
        assertEquals(480, video.height)
        assertEquals(212_393L, video.durationMs)
        assertTrue(video.audioUrl.startsWith("https://"))
        assertTrue(video.audioUrl.contains(".m4s"))
        assertTrue(video.fallbackUrl.startsWith("https://"))
        assertEquals("https://i1.hdslb.com/bfs/archive/5242750857121e05146d5d5b13a47a2a6dd36e98.jpg", video.previewUrl)
    }

    @Test
    fun `falls back to the page url for the bvid`() {
        val body = resource("bilibili/bili-video-real.json")
            .replace("\"bvid\":\"BV1GJ411x7h7\",", "\"bvid\":\"\",")
        val post = BilibiliParser.parse(body, "https://www.bilibili.com/video/BV1GJ411x7h7?p=1")!!
        assertEquals("BV1GJ411x7h7", post.awemeId)
    }

    @Test
    fun `returns null without playinfo or tracks`() {
        assertNull(BilibiliParser.parse("", "https://www.bilibili.com/video/BV1"))
        assertNull(BilibiliParser.parse("not json", "https://www.bilibili.com/video/BV1"))
        assertNull(
            BilibiliParser.parse(
                """{"videoData":{"bvid":"BV1","duration":10}}""",
                "https://www.bilibili.com/video/BV1",
            ),
        )
        assertNull(
            BilibiliParser.parse(
                """{"videoData":{"bvid":"BV1"},"playinfo":{"dash":{"video":[],"audio":[]}}}""",
                "https://www.bilibili.com/video/BV1",
            ),
        )
    }

    private fun resource(path: String): String =
        javaClass.classLoader!!.getResourceAsStream(path)!!
            .bufferedReader()
            .use { it.readText() }
}
