package io.github.backtraxe.anysave.reader

import io.github.backtraxe.anysave.model.MediaKind
import io.github.backtraxe.anysave.model.MediaSite
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WeiboParserTest {

    @Test
    fun `parses real image post`() {
        val post = WeiboParser.parse(
            resource("weibo/weibo-image-status-real.json"),
            "https://weibo.com/1912476847/RnMJ9E9RR",
        )!!

        assertEquals("RnMJ9E9RR", post.awemeId)
        assertEquals("杨伊琳10", post.author)
        assertEquals(MediaSite.WEIBO, post.site)
        assertEquals(3, post.items.size)
        assertEquals(3, post.imageCount)
        assertEquals(0, post.videoCount)

        val first = post.items.first()
        assertEquals(MediaKind.IMAGE, first.kind)
        assertTrue(first.url.startsWith("https://wx2.sinaimg.cn/"))
        assertEquals(first.url, first.previewUrl)
        assertEquals(1440, first.width)
        assertEquals(1080, first.height)

        // HTML text is cleaned: emoticon alts preserved, tags stripped.
        assertFalse(post.desc.contains("<span"))
        assertTrue(post.desc.contains("你们好棒"))
        assertTrue(post.desc.contains("[淚]"))
    }

    @Test
    fun `parses real video post`() {
        val post = WeiboParser.parse(
            resource("weibo/weibo-video-status-real.json"),
            "https://weibo.com/7516679696/RnPXb1X4T",
        )!!

        assertEquals("RnPXb1X4T", post.awemeId)
        assertEquals("九派新闻", post.author)
        assertEquals(1, post.items.size)
        assertEquals(1, post.videoCount)

        val video = post.items.single()
        assertEquals(MediaKind.VIDEO, video.kind)
        assertTrue(video.url.startsWith("https://f.video.weibocdn.com/"))
        assertTrue(video.url.contains("mp4_720p"))
        assertTrue(video.previewUrl.startsWith("https://wx3.sinaimg.cn/"))
        assertEquals(13_722L, video.durationMs)
    }

    @Test
    fun `accepts the compact payload returned by the reader script`() {
        val compact = JSONObject()
            .put("bid", "RnMJ9E9RR")
            .put("text", "hello<br />world")
            .put("user", JSONObject().put("screenName", "someone"))
            .put(
                "pics",
                org.json.JSONArray().put(
                    JSONObject()
                        .put("url", "http://wx2.sinaimg.cn/a.jpg")
                        .put("width", 100)
                        .put("height", 200),
                ),
            )

        val post = WeiboParser.parse(compact.toString(), "https://weibo.com/1/RnMJ9E9RR")!!
        assertEquals("RnMJ9E9RR", post.awemeId)
        assertEquals("someone", post.author)
        assertEquals("hello\nworld", post.desc)
        assertEquals("https://wx2.sinaimg.cn/a.jpg", post.items.single().url)
        assertEquals(100, post.items.single().width)
    }

    @Test
    fun `falls back to the bid in the page url and rejects empty payloads`() {
        val compact = JSONObject()
            .put("text", "no bid")
            .put("pics", org.json.JSONArray().put(JSONObject().put("url", "https://wx1.sinaimg.cn/a.jpg")))
        val post = WeiboParser.parse(compact.toString(), "https://weibo.com/123456/RnMJ9E9RR")!!
        assertEquals("RnMJ9E9RR", post.awemeId)

        assertNull(WeiboParser.parse("", "https://weibo.com/1/RnMJ9E9RR"))
        assertNull(WeiboParser.parse("not json", "https://weibo.com/1/RnMJ9E9RR"))
        assertNull(WeiboParser.parse("""{"bid":"x"}""", "https://weibo.com/1/x"))
    }

    @Test
    fun `strips html and decodes entities`() {
        val text = WeiboParser.cleanText("A &amp; B<br/><b>bold</b> &#39;q&#39;")
        assertEquals("A & B\nbold 'q'", text)
    }

    private fun resource(path: String): String =
        javaClass.classLoader!!.getResourceAsStream(path)!!
            .bufferedReader()
            .use { it.readText() }
}
