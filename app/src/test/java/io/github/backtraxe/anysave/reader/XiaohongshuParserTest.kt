package io.github.backtraxe.anysave.reader

import io.github.backtraxe.anysave.model.MediaKind
import io.github.backtraxe.anysave.model.MediaSite
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class XiaohongshuParserTest {

    @Test
    fun `parses real image note payload`() {
        val body = resource("xiaohongshu/xhs-image-note-real.json")
        val post = XiaohongshuParser.parse(body, "https://www.xiaohongshu.com/explore/6ad8558d000000003303128c?xsec_token=X")!!

        assertEquals("6ad8558d000000003303128c", post.awemeId)
        assertEquals("你好小舒🐈", post.author)
        assertEquals(MediaSite.XIAOHONGSHU, post.site)
        assertEquals(12, post.items.size)
        assertEquals(12, post.imageCount)
        assertEquals(0, post.videoCount)
        assertTrue(post.desc.startsWith("小朋友的冬日干净系"))

        val first = post.items.first()
        assertEquals(MediaKind.IMAGE, first.kind)
        assertEquals(
            "https://ci.xiaohongshu.com/notes_pre_post/1040g3k0326iuiprc4q005pvfamf3jt6gjg2oido",
            first.url,
        )
        assertTrue(first.previewUrl.startsWith("https://sns-webpic-qc.xhscdn.com/"))
        assertEquals(first.previewUrl, first.fallbackUrl)
        assertEquals(2730, first.width)
        assertEquals(4096, first.height)
        assertEquals(1, first.index)
    }

    @Test
    fun `parses real video note payload`() {
        val body = resource("xiaohongshu/xhs-video-note-real.json")
        val post = XiaohongshuParser.parse(body, "https://www.xiaohongshu.com/explore/6ad73f52000000000c00a2f6?xsec_token=X")!!

        assertEquals("6ad73f52000000000c00a2f6", post.awemeId)
        assertEquals("大宁老师育儿", post.author)
        assertEquals(1, post.items.size)
        assertEquals(1, post.videoCount)

        val video = post.items.single()
        assertEquals(MediaKind.VIDEO, video.kind)
        assertTrue(video.url.startsWith("https://sns-video-qc.xhscdn.com/"))
        assertTrue(video.url.contains(".mp4"))
        assertTrue(video.previewUrl.startsWith("https://ci.xiaohongshu.com/frame/"))
        assertEquals(127_803L, video.durationMs)
        assertEquals(1440, video.width)
        assertEquals(2560, video.height)
    }

    @Test
    fun `accepts raw initial-state shape and resolves the note from the page url`() {
        val compact = JSONObject(resource("xiaohongshu/xhs-image-note-real.json"))
        val state = JSONObject()
            .put(
                "note",
                JSONObject().put(
                    "noteDetailMap",
                    JSONObject().put(
                        compact.getString("noteId"),
                        JSONObject().put("note", compact.getJSONObject("note")),
                    ),
                ),
            )

        val post = XiaohongshuParser.parse(
            state.toString(),
            "https://www.xiaohongshu.com/explore/6ad8558d000000003303128c?xsec_token=X",
        )!!

        assertEquals("6ad8558d000000003303128c", post.awemeId)
        assertEquals(12, post.items.size)
    }

    @Test
    fun `parses noteData data noteData state shape used by mobile pages`() {
        val compact = JSONObject(resource("xiaohongshu/xhs-image-note-real.json"))
        val state = JSONObject().put(
            "noteData",
            JSONObject().put(
                "data",
                JSONObject().put("noteData", compact.getJSONObject("note")),
            ),
        )

        val post = XiaohongshuParser.parse(
            state.toString(),
            "https://www.xiaohongshu.com/discovery/item/6ad8558d000000003303128c?xsec_token=X",
        )!!

        assertEquals("6ad8558d000000003303128c", post.awemeId)
        assertEquals(12, post.items.size)
        assertEquals("你好小舒🐈", post.author)
    }

    @Test
    fun `normalizes javascript undefined tokens before parsing`() {
        val body = """
            {"note":{"noteDetailMap":{"6ad8558d000000003303128c":{"note":{
              "title": undefined,
              "desc": "hello",
              "imageList":[{"fileId":"notes/abc","width":10,"height":20,"urlDefault":undefined}]
            }}}}}
        """.trimIndent()

        val post = XiaohongshuParser.parse(
            body,
            "https://www.xiaohongshu.com/explore/6ad8558d000000003303128c",
        )!!

        assertEquals("hello", post.desc)
        assertEquals(1, post.items.size)
        assertEquals("https://ci.xiaohongshu.com/notes/abc", post.items.single().url)
        assertEquals("", post.items.single().fallbackUrl)
    }

    @Test
    fun `returns null for non note payloads`() {
        assertNull(XiaohongshuParser.parse("", "https://www.xiaohongshu.com/explore/1"))
        assertNull(XiaohongshuParser.parse("not json", "https://www.xiaohongshu.com/explore/1"))
        assertNull(XiaohongshuParser.parse("""{"note":{}}""", "https://www.xiaohongshu.com/explore/1"))
        assertNull(
            XiaohongshuParser.parse(
                """{"noteId":"1","note":{"title":"x","imageList":[]}}""",
                "https://www.xiaohongshu.com/explore/1",
            ),
        )
    }

    private fun resource(path: String): String =
        javaClass.classLoader!!.getResourceAsStream(path)!!
            .bufferedReader()
            .use { it.readText() }

    @Test
    fun `parses desktop video note stream shape`() {
        val body = """
            {"noteId":"abc123","note":{"type":"video","title":"clip","desc":"d",
              "user":{"nickname":"u"},
              "imageList":[{"fileId":"cover-file"}],
              "video":{"media":{"video":{"duration":14},
                "stream":{"EF4":[
                  {"masterUrl":"http://sns-video.xhscdn.com/hd.mp4","backupUrls":["http://sns-bak.xhscdn.com/hd.mp4"],"width":960,"height":720,"videoBitrate":1200000,"videoDuration":13833,"qualityType":"HD"},
                  {"masterUrl":"http://sns-video.xhscdn.com/fhd.mp4","backupUrls":["http://sns-bak.xhscdn.com/fhd.mp4"],"width":1440,"height":1080,"videoBitrate":2200000,"videoDuration":14000,"qualityType":"FHD"}
                ]}},
               "image":{"firstFrameFileid":"frame-file"}}}}
        """.trimIndent()
        val post = XiaohongshuParser.parse(body, "https://www.xiaohongshu.com/explore/abc123")!!
        val item = post.items.single()
        assertEquals(MediaKind.VIDEO, item.kind)
        assertEquals("https://sns-video.xhscdn.com/fhd.mp4", item.url)
        assertEquals("https://sns-bak.xhscdn.com/fhd.mp4", item.fallbackUrl)
        assertEquals(1440, item.width)
        assertEquals(1080, item.height)
        assertEquals(14_000L, item.durationMs)
        assertTrue(item.previewUrl.startsWith("https://ci.xiaohongshu.com/"))
    }

}
