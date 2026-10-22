package io.github.backtraxe.anysave.download

import io.github.backtraxe.anysave.model.MediaItem
import io.github.backtraxe.anysave.model.MediaKind
import io.github.backtraxe.anysave.model.MediaSite
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

    @Test
    fun `xiaohongshu names use the site prefix and fallback extension`() {
        val post = POST.copy(site = MediaSite.XIAOHONGSHU, awemeId = "6ad8558d000000003303128c")
        val item = image(1, "https://ci.xiaohongshu.com/notes_pre_post/abc")

        val original = MediaNaming.target(post, item)
        assertEquals("xiaohongshu_6ad8558d000000003303128c_01.jpg", original.displayName)
        assertEquals("image/jpeg", original.mimeType)
        assertEquals("Pictures/AnySave", original.relativeDir)

        val display = MediaNaming.targetFor(
            post,
            item,
            "https://sns-webpic-qc.xhscdn.com/2026/frame/abc!nd_dft.webp",
        )
        assertEquals("xiaohongshu_6ad8558d000000003303128c_01.webp", display.displayName)
        assertEquals("image/webp", display.mimeType)
    }

    @Test
    fun `bilibili dash video is named after the bvid`() {
        val post = POST.copy(site = MediaSite.BILIBILI, awemeId = "BV1GJ411x7h7")
        val item = MediaItem(
            id = "BV1GJ411x7h7-video-1",
            kind = MediaKind.VIDEO,
            url = "https://cn-hbwh-cm-01-03.bilivideo.com/x/137649199.m4s?e=abc",
            previewUrl = "https://i1.hdslb.com/bfs/archive/pic.jpg",
            audioUrl = "https://cn-hbwh-cm-01-03.bilivideo.com/x/audio.m4s?e=abc",
            width = 852,
            height = 480,
            durationMs = 212_393,
            index = 1,
        )
        val target = MediaNaming.target(post, item)
        assertEquals("bilibili_BV1GJ411x7h7_video.mp4", target.displayName)
        assertEquals("video/mp4", target.mimeType)
        assertEquals("Movies/AnySave", target.relativeDir)
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
