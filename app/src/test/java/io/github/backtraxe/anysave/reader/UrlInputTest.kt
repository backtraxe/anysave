package io.github.backtraxe.anysave.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UrlInputTest {

    @Test
    fun `bare url is kept`() {
        assertEquals("https://xhslink.cn/m/abc123", UrlInput.extract("https://xhslink.cn/m/abc123"))
        assertEquals(
            "http://xhslink.com/a/abc123",
            UrlInput.extract("http://xhslink.com/a/abc123"),
        )
    }

    @Test
    fun `url inside xiaohongshu share text is extracted and trimmed`() {
        val share = "看看这个笔记 https://xhslink.cn/m/abc123，复制本条信息，打开小红书查看"
        assertEquals("https://xhslink.cn/m/abc123", UrlInput.extract(share))
    }

    @Test
    fun `bare short host gets https scheme`() {
        assertEquals("https://xhslink.cn/a/abc", UrlInput.extract("xhslink.cn/a/abc"))
        assertEquals("https://xhslink.com/a/abc", UrlInput.extract("xhslink.com/a/abc"))
    }

    @Test
    fun `plain text is rejected`() {
        assertNull(UrlInput.extract("hello world"))
        assertNull(UrlInput.extract(""))
        assertNull(UrlInput.extract("打开小红书"))
    }
}
