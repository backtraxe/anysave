package io.github.backtraxe.anysave.reader

import io.github.backtraxe.anysave.model.MediaKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DouyinParserTest {

    @Test
    fun `parses embedded SSR image note payload`() {
        // Trimmed from the camelCase detail douyin embeds in the note page's
        // RSC flight data (window.__pace_f): aweme.detail, playAddr is a list.
        val body = javaClass.classLoader!!
            .getResourceAsStream("douyin/note-embedded-real.json")!!
            .bufferedReader()
            .use { it.readText() }

        val post = DouyinParser.parse(body, "https://www.douyin.com/note/7630244974127660645")!!

        assertEquals("7630244974127660645", post.awemeId)
        assertEquals("TANG", post.author)
        assertEquals(15, post.items.size)
        assertEquals(15, post.imageCount)
        assertEquals(0, post.videoCount)
        assertEquals(firstUrlOf("note-embedded-real.json"), post.items.first().url)
    }

    private fun firstUrlOf(resource: String): String {
        val body = javaClass.classLoader!!
            .getResourceAsStream("douyin/$resource")!!
            .bufferedReader()
            .use { it.readText() }
        val detail = org.json.JSONObject(body).getJSONObject("aweme").getJSONObject("detail")
        val image = detail.getJSONArray("images").getJSONObject(0)
        // Clean jpegs are preferred over webp inside urlList.
        return image.getJSONArray("urlList").let { list ->
            (0 until list.length()).map { list.getString(it) }.first { it.contains(".jpeg") }
        }
    }

    @Test
    fun `parses real captured image note payload and skips placeholder video`() {
        // Trimmed from a real note capture: 15 images plus a duration-0 `video`
        // object that douyin puts on 图文 posts (not a real clip).
        val body = javaClass.classLoader!!
            .getResourceAsStream("douyin/detail-images-real.json")!!
            .bufferedReader()
            .use { it.readText() }

        val post = DouyinParser.parse(body, "https://www.douyin.com/note/7630244974127660645")!!

        assertEquals("7630244974127660645", post.awemeId)
        assertEquals("TANG", post.author)
        assertEquals(15, post.items.size)
        assertEquals(15, post.imageCount)
        assertEquals(0, post.videoCount)

        val first = post.items.first()
        assertEquals(MediaKind.IMAGE, first.kind)
        assertEquals(2160, first.width)
        assertEquals(3240, first.height)
        // Watermarked `download_url_list` entries are skipped; the clean jpeg wins.
        assertTrue(first.url.startsWith("https://p9-pc-sign.douyinpic.com/"))
        assertTrue(first.url.substringBefore('?').endsWith(".jpeg"))
        assertFalse(first.url.contains("dy-water"))
        assertEquals(first.url, first.previewUrl)
    }

    @Test
    fun `parses real captured video payload`() {
        // Trimmed from a real /aweme/v1/web/aweme/detail/ capture (see docs/verification.md).
        val body = javaClass.classLoader!!
            .getResourceAsStream("douyin/detail-video-real.json")!!
            .bufferedReader()
            .use { it.readText() }

        val post = DouyinParser.parse(body, PAGE_URL)!!

        assertEquals("7691296880559540623", post.awemeId)
        assertEquals("讨厌晚上", post.author)
        assertEquals("我去！好美", post.desc)
        assertEquals(1, post.items.size)

        val video = post.items.single()
        assertEquals(MediaKind.VIDEO, video.kind)
        assertTrue(video.url.startsWith("https://v26-web.douyinvod.com/"))
        assertFalse(video.url.contains("playwm"))
        assertTrue(video.previewUrl.startsWith("https://p9-pc-sign.douyinpic.com/"))
        assertEquals(7_000L, video.durationMs)
        assertEquals(1080, video.width)
        assertEquals(1920, video.height)
    }

    @Test
    fun `parses video post with clean play url and cover`() {
        val post = DouyinParser.parse(VIDEO_BODY, PAGE_URL)!!

        assertEquals("7691296880559540623", post.awemeId)
        assertEquals("旅行的猫", post.author)
        assertEquals("海边的日落，随手一拍 #风景", post.desc)
        assertEquals(1, post.items.size)
        assertEquals(1, post.videoCount)
        assertEquals(0, post.imageCount)

        val video = post.items.first()
        assertEquals(MediaKind.VIDEO, video.kind)
        assertEquals("https://v26-web.douyinvod.com/abc/video.mp4?watermark=0&a=6383", video.url)
        assertEquals("https://p3-pc-sign.douyinpic.com/obj/cover.jpeg?x-expires=1", video.previewUrl)
        assertEquals(15_400L, video.durationMs)
        assertEquals(1080, video.width)
        assertEquals(1920, video.height)
        assertTrue(video.hasDimensions)
    }

    @Test
    fun `skips playwm urls and falls back to highest bitrate tier`() {
        val post = DouyinParser.parse(VIDEO_PLAYWm_BODY, PAGE_URL)!!
        val video = post.items.single()

        assertEquals(MediaKind.VIDEO, video.kind)
        assertEquals("https://v26-web.douyinvod.com/hd/video.mp4", video.url)
        // origin_cover is used when cover is empty.
        assertEquals("https://p3-pc-sign.douyinpic.com/obj/origin-cover.jpeg", video.previewUrl)
    }

    @Test
    fun `parses image post and prefers clean jpeg over watermarked webp`() {
        val post = DouyinParser.parse(IMAGES_BODY, PAGE_URL)!!

        assertEquals("7665000000000000001", post.awemeId)
        assertEquals("九寨行者", post.author)
        assertEquals(3, post.items.size)
        assertEquals(3, post.imageCount)

        val first = post.items[0]
        assertEquals(MediaKind.IMAGE, first.kind)
        assertEquals(
            "https://p3-pc-sign.douyinpic.com/obj/img1.jpeg?x-expires=1",
            first.url,
        )
        assertEquals(first.url, first.previewUrl)
        assertEquals(1, first.index)

        // No jpeg available: the clean webp wins over the watermarked jpeg.
        val second = post.items[1]
        assertEquals("https://p3-pc-sign.douyinpic.com/obj/img2.webp", second.url)

        // Protocol-relative URL is normalized to https.
        val third = post.items[2]
        assertEquals("https://p3-pc-sign.douyinpic.com/obj/img3.jpeg", third.url)
    }

    @Test
    fun `image post with note level video yields both kinds`() {
        val post = DouyinParser.parse(IMAGES_WITH_VIDEO_BODY, PAGE_URL)!!

        assertEquals(3, post.items.size)
        assertEquals(2, post.imageCount)
        assertEquals(1, post.videoCount)
        assertEquals(MediaKind.VIDEO, post.items.last().kind)
    }

    @Test
    fun `supports camelCase payload variant`() {
        val post = DouyinParser.parse(CAMEL_BODY, PAGE_URL)!!

        assertEquals("7665000000000000009", post.awemeId)
        assertEquals("camel author", post.author)
        assertEquals(2, post.items.size)
        assertEquals("https://p3-pc-sign.douyinpic.com/obj/c1.jpeg", post.items[0].url)
        assertEquals(MediaKind.VIDEO, post.items[1].kind)
        assertEquals("https://v26-web.douyinvod.com/camel/video.mp4", post.items[1].url)
    }

    @Test
    fun `falls back to aweme id from page url`() {
        val post = DouyinParser.parse(VIDEO_WITHOUT_ID_BODY, PAGE_URL)!!
        assertEquals("7691296880559540623", post.awemeId)
    }

    @Test
    fun `returns null for non post payloads`() {
        assertNull(DouyinParser.parse("", PAGE_URL))
        assertNull(DouyinParser.parse("   ", PAGE_URL))
        assertNull(DouyinParser.parse("<html>challenge</html>", PAGE_URL))
        assertNull(DouyinParser.parse("""{"status_code":0,"aweme_detail":null}""", PAGE_URL))
        assertNull(DouyinParser.parse("""{"status_code":0,"aweme_detail":{}}""", PAGE_URL))
        // Detail present but no playable media.
        assertNull(
            DouyinParser.parse(
                """{"aweme_detail":{"aweme_id":"1","desc":"x","images":[],"video":null}}""",
                PAGE_URL,
            ),
        )
    }

    @Test
    fun `keeps watermarked url when no clean alternative exists`() {
        val post = DouyinParser.parse(WATERMARK_ONLY_BODY, PAGE_URL)!!
        assertEquals(
            "https://p3-pc-sign.douyinpic.com/obj/only~tplv-dy-water.jpeg",
            post.items.single().url,
        )
    }

    private companion object {
        private const val PAGE_URL = "https://www.douyin.com/video/7691296880559540623"

        private val VIDEO_BODY = """
            {
              "status_code": 0,
              "aweme_detail": {
                "aweme_id": "7691296880559540623",
                "desc": "海边的日落，随手一拍 #风景",
                "aweme_type": 0,
                "author": { "nickname": "旅行的猫", "unique_id": "cat_travel" },
                "video": {
                  "duration": 15400,
                  "width": 1080,
                  "height": 1920,
                  "play_addr": {
                    "url_list": [
                      "https://v26-web.douyinvod.com/abc/video.mp4?watermark=0&a=6383",
                      "https://v3-web.douyinvod.com/abc/video.mp4?watermark=0"
                    ]
                  },
                  "bit_rate": [
                    {
                      "bit_rate": 2500,
                      "play_addr": { "url_list": ["https://v26-web.douyinvod.com/hd/video.mp4"] }
                    }
                  ],
                  "cover": {
                    "url_list": ["https://p3-pc-sign.douyinpic.com/obj/cover.jpeg?x-expires=1"]
                  },
                  "origin_cover": {
                    "url_list": ["https://p3-pc-sign.douyinpic.com/obj/origin-cover.jpeg"]
                  }
                }
              }
            }
        """.trimIndent()

        private val VIDEO_PLAYWm_BODY = """
            {
              "aweme_detail": {
                "aweme_id": "7691296880559540624",
                "desc": "水印兜底",
                "author": { "nickname": "fallback" },
                "video": {
                  "duration": 8000,
                  "play_addr": {
                    "url_list": [
                      "https://v26-web.douyinvod.com/abc/playwm/video.mp4"
                    ]
                  },
                  "bit_rate": [
                    {
                      "bit_rate": 1200,
                      "play_addr": { "url_list": ["https://v26-web.douyinvod.com/sd/video.mp4"] }
                    },
                    {
                      "bit_rate": 2500,
                      "play_addr": { "url_list": ["https://v26-web.douyinvod.com/hd/video.mp4"] }
                    }
                  ],
                  "cover": { "url_list": [] },
                  "origin_cover": {
                    "url_list": ["https://p3-pc-sign.douyinpic.com/obj/origin-cover.jpeg"]
                  }
                }
              }
            }
        """.trimIndent()

        private val IMAGES_BODY = """
            {
              "status_code": 0,
              "aweme_detail": {
                "aweme_id": "7665000000000000001",
                "desc": "秋天的九寨沟，九张图",
                "aweme_type": 68,
                "authorInfo": { "nickname": "九寨行者" },
                "images": [
                  {
                    "width": 1080,
                    "height": 1440,
                    "url_list": [
                      "https://p3-pc-sign.douyinpic.com/obj/img1.jpeg?x-expires=1",
                      "https://p3-pc-sign.douyinpic.com/obj/img1.webp"
                    ],
                    "download_url_list": [
                      "https://p3-pc-sign.douyinpic.com/obj/img1~tplv-dy-water.jpeg"
                    ]
                  },
                  {
                    "width": 1080,
                    "height": 1440,
                    "url_list": ["https://p3-pc-sign.douyinpic.com/obj/img2.webp"],
                    "download_url_list": [
                      "https://p3-pc-sign.douyinpic.com/obj/img2~tplv-dy-water.jpeg"
                    ]
                  },
                  {
                    "width": 1080,
                    "height": 1440,
                    "url_list": ["//p3-pc-sign.douyinpic.com/obj/img3.jpeg"],
                    "download_url_list": []
                  },
                  {
                    "width": 0,
                    "height": 0,
                    "url_list": [],
                    "download_url_list": []
                  }
                ],
                "video": null
              }
            }
        """.trimIndent()

        private val IMAGES_WITH_VIDEO_BODY = """
            {
              "aweme_detail": {
                "aweme_id": "7665000000000000002",
                "desc": "图文 + 视频",
                "author": { "nickname": "mixed" },
                "images": [
                  { "url_list": ["https://p3-pc-sign.douyinpic.com/obj/m1.jpeg"] },
                  { "url_list": ["https://p3-pc-sign.douyinpic.com/obj/m2.jpeg"] }
                ],
                "video": {
                  "duration": 4200,
                  "play_addr": { "url_list": ["https://v26-web.douyinvod.com/mixed/video.mp4"] },
                  "cover": { "url_list": ["https://p3-pc-sign.douyinpic.com/obj/mixed-cover.jpeg"] }
                }
              }
            }
        """.trimIndent()

        private val CAMEL_BODY = """
            {
              "aweme_detail": {
                "awemeId": "7665000000000000009",
                "desc": "camel case payload",
                "authorInfo": { "nickname": "camel author" },
                "images": [
                  {
                    "urlList": ["https://p3-pc-sign.douyinpic.com/obj/c1.jpeg"],
                    "downloadUrlList": [
                      "https://p3-pc-sign.douyinpic.com/obj/c1~tplv-dy-water.jpeg"
                    ]
                  }
                ],
                "video": {
                  "duration": 4200,
                  "width": 720,
                  "height": 1280,
                  "playAddr": [{ "src": "https://v26-web.douyinvod.com/camel/video.mp4" }],
                  "cover": "https://p3-pc-sign.douyinpic.com/obj/camel-cover.jpeg",
                  "bitRateList": []
                }
              }
            }
        """.trimIndent()

        private val VIDEO_WITHOUT_ID_BODY = """
            {
              "aweme_detail": {
                "desc": "no id in payload",
                "author": { "nickname": "no-id" },
                "video": {
                  "play_addr": { "url_list": ["https://v26-web.douyinvod.com/noid/video.mp4"] },
                  "cover": { "url_list": ["https://p3-pc-sign.douyinpic.com/obj/noid.jpeg"] }
                }
              }
            }
        """.trimIndent()

        private val WATERMARK_ONLY_BODY = """
            {
              "aweme_detail": {
                "aweme_id": "7665000000000000003",
                "desc": "watermark only",
                "author": { "nickname": "wm" },
                "images": [
                  {
                    "url_list": [
                      "https://p3-pc-sign.douyinpic.com/obj/only~tplv-dy-water.jpeg"
                    ]
                  }
                ]
              }
            }
        """.trimIndent()
    }
}
