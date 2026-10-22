package io.github.backtraxe.anysave.reader

import io.github.backtraxe.anysave.model.MediaKind
import io.github.backtraxe.anysave.model.MediaSite
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AcfunParserTest {

    @Test
    fun `parses real acfun article`() {
        val post = AcfunParser.parse(
            resource("acfun/acfun-article-real.json"),
            "https://www.acfun.cn/a/ac48924768",
        )!!

        assertEquals("ac48924768", post.awemeId)
        assertEquals("AcFun活动", post.author)
        assertEquals(MediaSite.ACFUN, post.site)
        assertTrue(post.desc.contains("UP创作大赛"))
        assertTrue(post.items.size >= 2)
        assertTrue(post.items.all { it.kind == MediaKind.IMAGE })
        assertTrue(post.items.all { it.url.startsWith("https://") })
        assertTrue(post.items.any { it.url.contains("imgs.aixifan.com") })
        assertEquals(post.items.first().url, post.items.first().previewUrl)
        assertEquals(1, post.items.first().index)
    }

    @Test
    fun `accepts compact payload and normalizes urls`() {
        val body = """{"articleId":"123","title":"T &amp; t","author":"u",
            "cover":"//imgs.aixifan.com/a.png",
            "parts":["<p><img src=\"http://imgs.aixifan.com/b.png\"></p>"]}"""
        val post = AcfunParser.parse(body, "https://www.acfun.cn/a/ac123")!!
        assertEquals("ac123", post.awemeId)
        assertEquals("T & t", post.desc)
        assertEquals(
            listOf("https://imgs.aixifan.com/b.png", "https://imgs.aixifan.com/a.png"),
            post.items.map { it.url },
        )
    }

    @Test
    fun `falls back to the article id in the url and rejects empty input`() {
        val body = """{"title":"T","parts":["<img src=\"https://imgs.aixifan.com/x.jpg\">"]}"""
        assertEquals(
            "ac48924768",
            AcfunParser.parse(body, "https://www.acfun.cn/a/ac48924768")!!.awemeId,
        )

        assertNull(AcfunParser.parse("", "https://www.acfun.cn/a/ac1"))
        assertNull(AcfunParser.parse("not json", "https://www.acfun.cn/a/ac1"))
        assertNull(AcfunParser.parse("""{"title":"T"}""", "https://www.acfun.cn/a/ac1"))
    }

    private fun resource(path: String): String =
        javaClass.classLoader!!.getResourceAsStream(path)!!
            .bufferedReader()
            .use { it.readText() }
}
