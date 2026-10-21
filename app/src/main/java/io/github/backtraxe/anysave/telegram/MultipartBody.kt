package io.github.backtraxe.anysave.telegram

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets

/**
 * Minimal multipart/form-data encoder for the Bot API.
 *
 * Pure JVM so the exact wire format can be asserted in unit tests (field names,
 * file parts, closing boundary) without a network.
 */
object MultipartBody {

    /** One form part; [fileName] == null means a plain text field. */
    data class Part(
        val name: String,
        val value: String? = null,
        val bytes: ByteArray? = null,
        val fileName: String? = null,
        val contentType: String? = null,
    )

    fun boundary(): String = "----AnySave${System.nanoTime().toString(36)}"

    fun build(boundary: String, parts: List<Part>): ByteArray {
        val out = ByteArrayOutputStream(64 * 1024)
        val crlf = "\r\n".toByteArray(StandardCharsets.UTF_8)
        for (part in parts) {
            out.write("--$boundary".toByteArray(StandardCharsets.UTF_8))
            out.write(crlf)
            val disposition = buildString {
                append("Content-Disposition: form-data; name=\"").append(part.name).append('"')
                if (part.fileName != null) {
                    append("; filename=\"").append(part.fileName).append('"')
                }
            }
            out.write(disposition.toByteArray(StandardCharsets.UTF_8))
            out.write(crlf)
            if (part.fileName != null) {
                out.write(
                    "Content-Type: ${part.contentType ?: "application/octet-stream"}"
                        .toByteArray(StandardCharsets.UTF_8),
                )
                out.write(crlf)
            }
            out.write(crlf)
            val body = part.bytes ?: part.value.orEmpty().toByteArray(StandardCharsets.UTF_8)
            out.write(body)
            out.write(crlf)
        }
        out.write("--$boundary--".toByteArray(StandardCharsets.UTF_8))
        out.write(crlf)
        return out.toByteArray()
    }
}
