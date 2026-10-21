package io.github.backtraxe.anysave.telegram

import java.nio.charset.StandardCharsets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MultipartBodyTest {

    @Test
    fun `encodes text and file parts with a closing boundary`() {
        val boundary = "test-boundary"
        val fileBytes = byteArrayOf(1, 2, 3, 4, 5)
        val body = MultipartBody.build(
            boundary = boundary,
            parts = listOf(
                MultipartBody.Part(name = "chat_id", value = "42"),
                MultipartBody.Part(
                    name = "file0",
                    bytes = fileBytes,
                    fileName = "a.jpg",
                    contentType = "image/jpeg",
                ),
            ),
        )
        val text = String(body, StandardCharsets.ISO_8859_1)

        assertTrue(text.startsWith("--$boundary\r\n"))
        assertTrue(
            text.contains(
                "Content-Disposition: form-data; name=\"chat_id\"\r\n\r\n42\r\n",
            ),
        )
        assertTrue(
            text.contains(
                "Content-Disposition: form-data; name=\"file0\"; filename=\"a.jpg\"\r\n" +
                    "Content-Type: image/jpeg\r\n\r\n",
            ),
        )
        assertTrue(text.endsWith("--$boundary--\r\n"))

        // The raw payload must be embedded verbatim.
        val marker = "Content-Type: image/jpeg\r\n\r\n".toByteArray(StandardCharsets.ISO_8859_1)
        val start = text.indexOf("\r\n\r\n", text.indexOf("filename=\"a.jpg\"")) + 4
        assertTrue(text.length > start)
        val actual = body.copyOfRange(start, start + fileBytes.size)
        assertEquals(fileBytes.toList(), actual.toList())

        // Expected total size sanity check (no accidental truncation).
        assertTrue(body.size > marker.size + fileBytes.size)
    }

    @Test
    fun `filename and content type are optional`() {
        val body = MultipartBody.build(
            boundary = "b",
            parts = listOf(MultipartBody.Part(name = "media", value = "[]")),
        )
        val text = String(body, StandardCharsets.ISO_8859_1)
        assertTrue(text.contains("name=\"media\""))
        assertTrue(!text.contains("filename="))
        assertTrue(!text.contains("Content-Type:"))
        assertTrue(text.endsWith("--b--\r\n"))
    }
}
