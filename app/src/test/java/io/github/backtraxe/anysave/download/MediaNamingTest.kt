package io.github.backtraxe.anysave.download

import io.github.backtraxe.anysave.model.MediaItem
import io.github.backtraxe.anysave.model.MediaKind
import io.github.backtraxe.anysave.model.PostMedia
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaNamingTest {

    @Test
    fun `image target is a jpeg inside the pictures album`() {
        val target = MediaNaming.target(POST, image(1, "https://p3-pc-sign.douyinpic.com/a/b~q75.jpeg?x-expires=1"))

        assertEquals("douyin_7665000000000000001_01.jpg", target.displayName)
        assertEquals("image/jpeg", target.mimeType)
        assertEquals("Pictures/AnySave", target.relativeDir)
        assertFalse(target.isVideo)
    }

    @Test
    fun `webp image keeps its extension and mime`() {
        val target = MediaNaming.target(POST, image(2, "https://p9-pc-sign.douyinpic.com/obj/x.webp?lk3s=1"))

        assertEquals("douyin_7665000000000000001_02.webp", target.displayName)
        assertEquals("image/webp", target.mimeType)
    }

    @Test
    fun `protocol relative and unknown extensions fall back to jpg`() {
        assertEquals("jpg", MediaNaming.extensionOf("//p3-pc-sign.douyinpic.com/obj/img", MediaKind.IMAGE))
        assertEquals("jpg", MediaNaming.extensionOf("https://example.com/img?format=jpeg", MediaKind.IMAGE))
        assertEquals("jpg", MediaNaming.extensionOf("https://example.com/img.jpeg", MediaKind.IMAGE))
    }

    @Test
    fun `video target goes to the movies album`() {
        val video = MediaItem(
            id = "v1",
            kind = MediaKind.VIDEO,
            url = "https://v26-web.douyinvod.com/path/video.mp4?a=6383",
            previewUrl = "https://p3-pc-sign.douyinpic.com/cover.jpeg",
            durationMs = 7000,
            index = 2,
        )
        val target = MediaNaming.target(POST, video)

        assertEquals("douyin_7665000000000000001_video.mp4", target.displayName)
        assertEquals("video/mp4", target.mimeType)
        assertEquals("Movies/AnySave", target.relativeDir)
        assertTrue(target.isVideo)
    }

    @Test
    fun `video without extension falls back to mp4`() {
        val video = MediaItem(
            id = "v2",
            kind = MediaKind.VIDEO,
            url = "https://v26-web.douyinvod.com/aweme/v1/play/?video_id=abc",
            previewUrl = "",
            index = 1,
        )
        val target = MediaNaming.target(POST, video)

        assertEquals("douyin_7665000000000000001_video.mp4", target.displayName)
        assertEquals("video/mp4", target.mimeType)
    }

    @Test
    fun `blank aweme id still produces a usable name`() {
        val target = MediaNaming.target(POST.copy(awemeId = ""), image(1, "https://x/y.jpeg"))
        assertEquals("douyin_post_01.jpg", target.displayName)
    }

    private fun image(index: Int, url: String) = MediaItem(
        id = "img$index",
        kind = MediaKind.IMAGE,
        url = url,
        previewUrl = url,
        width = 2160,
        height = 3240,
        index = index,
    )

    private companion object {
        private val POST = PostMedia(
            awemeId = "7665000000000000001",
            author = "TANG",
            desc = "autumn",
            sourceUrl = "https://www.douyin.com/note/7665000000000000001",
            items = emptyList(),
        )
    }
}
