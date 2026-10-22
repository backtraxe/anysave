package io.github.backtraxe.anysave.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SiteReaderTest {

    @Test
    fun `xiaohongshu share short links map to the xiaohongshu reader`() {
        val urls = listOf(
            "https://xhslink.cn/m/abc123",
            "http://xhslink.com/a/abc123",
            "https://www.xiaohongshu.com/explore/abc123?xsec_token=X",
            "https://www.xiaohongshu.com/discovery/item/abc123?xsec_token=X",
        )
        for (url in urls) {
            val reader = SiteReader.forUrl(url)
            assertEquals("reader for $url", XiaohongshuReader, reader)
        }
    }

    @Test
    fun `xiaohongshu reader also hooks the short-link redirect target`() {
        assertTrue(
            XiaohongshuReader.originAliases.any {
                it == "https://www.xiaohongshu.com"
            },
        )
    }

    @Test
    fun `unrelated hosts are still unsupported`() {
        assertNull(SiteReader.forUrl("https://example.com/watch?v=1"))
        assertNull(SiteReader.forUrl("https://xhslink.example.com/a/1"))
    }
}
