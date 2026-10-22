package io.github.backtraxe.anysave.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CookieImportTest {

    @Test
    fun `parses a cookie request header`() {
        val cookies = CookieImport.parse("Cookie: SESSDATA=abc; bili_jct=def=ghi; buvid3=x")
        assertEquals(3, cookies.size)
        assertEquals("SESSDATA", cookies[0].name)
        assertEquals("abc", cookies[0].value)
        assertEquals(null, cookies[0].domain)
        assertEquals("def=ghi", cookies[1].value)
    }

    @Test
    fun `parses netscape including HttpOnly lines`() {
        val text = """
            # Netscape HTTP Cookie File
            .bilibili.com	TRUE	/	TRUE	1893456000	SESSDATA	abc123
            #HttpOnly_.bilibili.com	TRUE	/	FALSE	0	bili_jct	xyz
        """.trimIndent()
        val cookies = CookieImport.parse(text)
        assertEquals(2, cookies.size)
        assertEquals(".bilibili.com", cookies[0].domain)
        assertTrue(cookies[0].secure)
        assertEquals(1_893_456_000L, cookies[0].expires)
        assertTrue(cookies[1].httpOnly)
        assertEquals(null, cookies[1].expires)
    }

    @Test
    fun `parses cookie-editor json array and wrapper`() {
        val array = """
            [{"name":"web_session","value":"abc","domain":".xiaohongshu.com","path":"/",
              "secure":true,"httpOnly":true,"expirationDate":1893456000.5}]
        """.trimIndent()
        val cookies = CookieImport.parse(array)
        assertEquals(1, cookies.size)
        assertEquals(".xiaohongshu.com", cookies[0].domain)
        assertTrue(cookies[0].httpOnly)
        assertEquals(1_893_456_000L, cookies[0].expires)

        val wrapped = """{"cookies":[{"name":"a1","value":"v","domain":".xiaohongshu.com"}]}"""
        assertEquals(1, CookieImport.parse(wrapped).size)
    }

    @Test
    fun `rejects empty or unparseable input`() {
        assertTrue(runCatching { CookieImport.parse("") }.isFailure)
        assertTrue(runCatching { CookieImport.parse("hello world") }.isFailure)
        assertTrue(runCatching { CookieImport.parse("[]") }.isFailure)
    }
}
