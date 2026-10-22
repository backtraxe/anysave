package io.github.backtraxe.anysave.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.schabi.newpipe.extractor.MediaFormat

class YouTubePlanPickerTest {

    @Test
    fun `prefers avc mp4 video plus aac audio for muxing`() {
        val plan = YouTubePlanPicker.decide(
            videoOnly = listOf(
                video("v1080", height = 1080, codec = "avc1.640028", bitrate = 4_000_000),
                video("v720", height = 720, codec = "avc1.4D401F", bitrate = 2_000_000),
                video("v720-vp9", height = 720, codec = "vp9", format = MediaFormat.WEBM.name),
            ),
            progressive = listOf(progressive("p360", height = 360, bitrate = 800_000)),
            audio = listOf(
                audio("a-opus", format = MediaFormat.WEBMA_OPUS.name, codec = "opus", bitrate = 160_000),
                audio("a-mp4a-40-2", format = MediaFormat.M4A.name, codec = "mp4a.40.2", bitrate = 128_000),
                audio("a-mp4a-40-5", format = MediaFormat.M4A.name, codec = "mp4a.40.5", bitrate = 64_000),
            ),
        )!!

        assertEquals("https://example.com/v720", plan.videoUrl)
        assertEquals("https://example.com/a-mp4a-40-2", plan.audioUrl)
        assertEquals(720, plan.height)
        assertTrue(!plan.progressive)
    }

    @Test
    fun `falls back to progressive mp4 when no muxable tracks`() {
        val plan = YouTubePlanPicker.decide(
            videoOnly = listOf(video("vp9", height = 720, codec = "vp9", format = MediaFormat.WEBM.name)),
            progressive = listOf(
                progressive("p360", height = 360, bitrate = 800_000),
                progressive("p720", height = 720, bitrate = 1_600_000),
            ),
            audio = listOf(audio("opus", format = MediaFormat.WEBMA_OPUS.name, codec = "opus", bitrate = 160_000)),
        )!!

        assertEquals("https://example.com/p720", plan.videoUrl)
        assertNull(plan.audioUrl)
        assertTrue(plan.progressive)
    }

    @Test
    fun `uses high avc even above 720p when that is all there is`() {
        val plan = YouTubePlanPicker.decide(
            videoOnly = listOf(video("v1080", height = 1080, codec = "avc1.640028", bitrate = 4_000_000)),
            progressive = emptyList(),
            audio = listOf(audio("aac", format = MediaFormat.M4A.name, codec = "mp4a.40.2", bitrate = 128_000)),
        )!!
        assertEquals("https://example.com/v1080", plan.videoUrl)
        assertEquals(1080, plan.height)
    }

    @Test
    fun `returns null when nothing is playable`() {
        assertNull(YouTubePlanPicker.decide(emptyList(), emptyList(), emptyList()))
        assertNull(
            YouTubePlanPicker.decide(
                videoOnly = emptyList(),
                progressive = listOf(video("webm", height = 360, codec = "vp9", format = MediaFormat.WEBM.name)),
                audio = emptyList(),
            ),
        )
    }

    @Test
    fun `matches youtube url forms`() {
        assertTrue(YouTubeExtractor.matches("https://www.youtube.com/watch?v=jNQXAC9IVRw"))
        assertTrue(YouTubeExtractor.matches("https://youtu.be/jNQXAC9IVRw?t=1"))
        assertTrue(YouTubeExtractor.matches("https://www.youtube.com/shorts/jNQXAC9IVRw"))
        assertTrue(!YouTubeExtractor.matches("https://example.com/watch?v=jNQXAC9IVRw"))
    }

    private fun video(
        id: String,
        height: Int,
        codec: String = "avc1.4D401F",
        format: String = MediaFormat.MPEG_4.name,
        bitrate: Int = 1_000_000,
    ) = StreamCandidate(
        url = "https://example.com/$id",
        format = format,
        codec = codec,
        width = height * 16 / 9,
        height = height,
        bitrate = bitrate,
        isVideoOnly = true,
    )

    private fun progressive(id: String, height: Int, bitrate: Int) = StreamCandidate(
        url = "https://example.com/$id",
        format = MediaFormat.MPEG_4.name,
        codec = "avc1.42001E, mp4a.40.2",
        width = height * 16 / 9,
        height = height,
        bitrate = bitrate,
    )

    private fun audio(
        id: String,
        format: String,
        codec: String,
        bitrate: Int,
    ) = StreamCandidate(
        url = "https://example.com/$id",
        format = format,
        codec = codec,
        bitrate = bitrate,
        audioQuality = "AUDIO_QUALITY_MEDIUM",
    )
}
