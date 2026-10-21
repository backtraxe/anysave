package io.github.backtraxe.anysave.telegram

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TelegramProtocolTest {

    @Test
    fun `accepts well formed bot tokens`() {
        assertTrue(TelegramProtocol.isValidToken("123456789:AAHdqTcvCH1vGWJxfSeofSAs0K5PALDsaw"))
        assertTrue(TelegramProtocol.isValidToken(" 987654321:AAHdqTcvCH1vGWJxfSeofSAs0K5PALDsaw "))
    }

    @Test
    fun `rejects malformed bot tokens`() {
        assertFalse(TelegramProtocol.isValidToken(""))
        assertFalse(TelegramProtocol.isValidToken("123456789"))
        assertFalse(TelegramProtocol.isValidToken("123:short"))
        assertFalse(TelegramProtocol.isValidToken("123456789:has space in token value"))
        assertFalse(TelegramProtocol.isValidToken("abc:AAHdqTcvCH1vGWJxfSeofSAs0K5PALDsaw"))
    }

    @Test
    fun `accepts numeric negative and at usernames for chat id`() {
        assertTrue(TelegramProtocol.isValidChatId("123456789"))
        assertTrue(TelegramProtocol.isValidChatId("-1001234567890"))
        assertTrue(TelegramProtocol.isValidChatId("@anysave_channel"))
        assertFalse(TelegramProtocol.isValidChatId(""))
        assertFalse(TelegramProtocol.isValidChatId("abc"))
        assertFalse(TelegramProtocol.isValidChatId("not a chat"))
    }

    @Test
    fun `caption contains description author and source`() {
        val caption = TelegramProtocol.buildCaption(
            desc = "Autumn in NewZealand",
            author = "TANG",
            sourceUrl = "https://www.douyin.com/note/7630244974127660645",
        )
        assertEquals(
            "Autumn in NewZealand\n\nTANG · https://www.douyin.com/note/7630244974127660645",
            caption,
        )
    }

    @Test
    fun `caption falls back gracefully when fields are empty`() {
        assertEquals("TANG", TelegramProtocol.buildCaption("", "TANG", ""))
        assertEquals("https://example.com", TelegramProtocol.buildCaption("", "", "https://example.com"))
        assertEquals("", TelegramProtocol.buildCaption("", "", ""))
    }

    @Test
    fun `caption is trimmed to the bot api limit`() {
        val caption = TelegramProtocol.buildCaption("x".repeat(5000), "TANG", "https://e.x")
        assertTrue(caption.length <= TelegramProtocol.MAX_CAPTION_LENGTH)
        assertTrue(caption.endsWith("…"))
        assertTrue(caption.startsWith("xxx"))
    }

    @Test
    fun `groups attachments into albums of ten`() {
        assertTrue(TelegramProtocol.group(emptyList()).isEmpty())

        val one = listOf(photo("a"))
        assertEquals(listOf(1), TelegramProtocol.group(one).map { it.size })

        val ten = (1..10).map { photo("p$it") }
        assertEquals(listOf(10), TelegramProtocol.group(ten).map { it.size })

        val eleven = (1..11).map { photo("p$it") }
        assertEquals(listOf(10, 1), TelegramProtocol.group(eleven).map { it.size })

        val twentyThree = (1..23).map { photo("p$it") }
        assertEquals(listOf(10, 10, 3), TelegramProtocol.group(twentyThree).map { it.size })
    }

    @Test
    fun `media json uses attach refs and a caption on the first item only`() {
        val group = listOf(photo("a.jpg"), video("b.mp4"), photo("c.jpg"))
        val array = JSONArray(TelegramProtocol.mediaJson(group, "hello"))

        assertEquals(3, array.length())
        assertEquals("photo", array.getJSONObject(0).getString("type"))
        assertEquals("attach://file0", array.getJSONObject(0).getString("media"))
        assertEquals("hello", array.getJSONObject(0).getString("caption"))
        assertEquals("video", array.getJSONObject(1).getString("type"))
        assertEquals("attach://file1", array.getJSONObject(1).getString("media"))
        assertFalse(array.getJSONObject(1).has("caption"))
        assertFalse(array.getJSONObject(2).has("caption"))
    }

    @Test
    fun `single attachment maps to sendPhoto or sendVideo`() {
        assertEquals("sendPhoto" to "photo", TelegramProtocol.singleSend(photo("a.jpg")))
        assertEquals("sendVideo" to "video", TelegramProtocol.singleSend(video("a.mp4")))
    }

    @Test
    fun `size limits match bot api`() {
        assertTrue(TelegramProtocol.isWithinLimits(photo("a", bytes = 1)))
        assertTrue(TelegramProtocol.isWithinLimits(photo("a", bytes = 10 * 1024 * 1024)))
        assertFalse(TelegramProtocol.isWithinLimits(photo("a", bytes = 10 * 1024 * 1024 + 1)))
        assertTrue(TelegramProtocol.isWithinLimits(video("a.mp4", bytes = 50 * 1024 * 1024)))
        assertFalse(TelegramProtocol.isWithinLimits(video("a.mp4", bytes = 50 * 1024 * 1024 + 1)))
        assertFalse(TelegramProtocol.isWithinLimits(photo("a", bytes = 0)))
    }

    private fun photo(name: String, bytes: Int = 1_000) =
        TelegramProtocol.Attachment(name, "image/jpeg", bytes, isVideo = false)

    private fun video(name: String, bytes: Int = 1_000) =
        TelegramProtocol.Attachment(name, "video/mp4", bytes, isVideo = true)
}
