package io.github.backtraxe.anysave.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MediaSnifferTest {

    @Test
    fun `detects jpeg png gif webp`() {
        assertEquals("jpg", MediaSniffer.detect(bytes(0xFF, 0xD8, 0xFF, 0xE0, 0x00, 0x10)))
        assertEquals(
            "png",
            MediaSniffer.detect(bytes(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)),
        )
        assertEquals("gif", MediaSniffer.detect("GIF89a".toByteArray(Charsets.US_ASCII)))
        assertEquals(
            "webp",
            MediaSniffer.detect(
                bytes(0x52, 0x49, 0x46, 0x46, 1, 2, 3, 4, 0x57, 0x45, 0x42, 0x50),
            ),
        )
    }

    @Test
    fun `detects mp4 and mov brands`() {
        val mp4 = "....ftypisom....".toByteArray(Charsets.US_ASCII)
        val mov = "....ftypqt  ....".toByteArray(Charsets.US_ASCII)
        assertEquals("mp4", MediaSniffer.detect(mp4))
        assertEquals("mov", MediaSniffer.detect(mov))
    }

    @Test
    fun `returns null for unknown or short payloads`() {
        assertNull(MediaSniffer.detect(ByteArray(0)))
        assertNull(MediaSniffer.detect(bytes(0x00, 0x01)))
        assertNull(MediaSniffer.detect("hello world".toByteArray(Charsets.US_ASCII)))
    }

    private fun bytes(vararg values: Int): ByteArray = ByteArray(values.size) { values[it].toByte() }
}
