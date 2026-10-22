package io.github.backtraxe.anysave.download

import io.github.backtraxe.anysave.model.MediaItem
import io.github.backtraxe.anysave.model.MediaKind
import io.github.backtraxe.anysave.model.MediaSite
import io.github.backtraxe.anysave.model.PostMedia
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SaveJobCodecTest {

    @Test
    fun `round trips a post with image and video items`() {
        val job = SaveJob(POST, ITEMS)
        val decoded = SaveJobCodec.decode(SaveJobCodec.encode(job))!!

        assertEquals(POST.awemeId, decoded.post.awemeId)
        assertEquals(POST.author, decoded.post.author)
        assertEquals(POST.desc, decoded.post.desc)
        assertEquals(POST.sourceUrl, decoded.post.sourceUrl)
        assertEquals(MediaSite.XIAOHONGSHU, decoded.post.site)
        assertEquals(2, decoded.items.size)

        val image = decoded.items[0]
        assertEquals("img01", image.id)
        assertEquals(MediaKind.IMAGE, image.kind)
        assertEquals("https://p3-pc-sign.douyinpic.com/a.jpeg", image.url)
        assertEquals(2160, image.width)
        assertEquals(3240, image.height)
        assertEquals(1, image.index)
        assertEquals("https://sns-webpic-qc.xhscdn.com/display.webp", image.fallbackUrl)

        val video = decoded.items[1]
        assertEquals(MediaKind.VIDEO, video.kind)
        assertEquals(7_000L, video.durationMs)
        assertEquals("https://example.com/audio.m4s", video.audioUrl)
        assertEquals(2, video.index)
    }

    @Test
    fun `rejects malformed payloads`() {
        assertNull(SaveJobCodec.decode(null))
        assertNull(SaveJobCodec.decode(""))
        assertNull(SaveJobCodec.decode("not json"))
        assertNull(SaveJobCodec.decode("""{"post":{}}"""))
        assertNull(SaveJobCodec.decode("""{"items":[]}"""))
        assertNull(SaveJobCodec.decode("""{"post":{},"items":[]}"""))
    }

    @Test
    fun `skips items without a url`() {
        val raw = """{"post":{"awemeId":"1"},"items":[{"id":"a","kind":"IMAGE","url":""}]}"""
        assertNull(SaveJobCodec.decode(raw))
    }

    @Test
    fun `encoded payload stays small enough for an intent extra`() {
        val encoded = SaveJobCodec.encode(SaveJob(POST, ITEMS))
        assertTrue("encoded job was ${encoded.length} chars", encoded.length < 8_000)
    }

    private companion object {
        private val ITEMS = listOf(
            MediaItem(
                id = "img01",
                kind = MediaKind.IMAGE,
                url = "https://p3-pc-sign.douyinpic.com/a.jpeg",
                previewUrl = "https://p3-pc-sign.douyinpic.com/a.jpeg",
                fallbackUrl = "https://sns-webpic-qc.xhscdn.com/display.webp",
                width = 2160,
                height = 3240,
                index = 1,
            ),
            MediaItem(
                id = "video02",
                kind = MediaKind.VIDEO,
                url = "https://v26-web.douyinvod.com/v.mp4",
                previewUrl = "https://p3-pc-sign.douyinpic.com/cover.jpeg",
                width = 1080,
                height = 1920,
                durationMs = 7_000,
                index = 2,
                audioUrl = "https://example.com/audio.m4s",
            ),
        )

        private val POST = PostMedia(
            awemeId = "7665000000000000001",
            author = "TANG",
            desc = "Autumn in NewZealand",
            sourceUrl = "https://www.douyin.com/note/7665000000000000001",
            items = ITEMS,
            site = MediaSite.XIAOHONGSHU,
        )
    }
}
