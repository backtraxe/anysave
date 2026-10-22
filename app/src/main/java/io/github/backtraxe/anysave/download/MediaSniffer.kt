package io.github.backtraxe.anysave.download

/**
 * Detects the container type of downloaded media from its first bytes.
 *
 * Sites do not always serve the extension their URL suggests (xiaohongshu's
 * original-asset URLs have no extension at all, and display copies are webp).
 * Sniffing lets [MediaSaver] publish files with an honest name + MIME type.
 */
object MediaSniffer {

    /** @return `jpg`, `png`, `webp`, `gif`, `mp4`, `mov` or null when unknown. */
    fun detect(header: ByteArray): String? {
        if (header.size >= 3 &&
            header[0] == 0xFF.toByte() &&
            header[1] == 0xD8.toByte() &&
            header[2] == 0xFF.toByte()
        ) {
            return "jpg"
        }
        if (header.size >= 8 &&
            header[0] == 0x89.toByte() && header[1] == 0x50.toByte() &&
            header[2] == 0x4E.toByte() && header[3] == 0x47.toByte()
        ) {
            return "png"
        }
        if (header.size >= 3 &&
            header[0] == 0x47.toByte() && header[1] == 0x49.toByte() && header[2] == 0x46.toByte()
        ) {
            return "gif"
        }
        if (header.size >= 12 &&
            header[0] == 0x52.toByte() && header[1] == 0x49.toByte() &&
            header[2] == 0x46.toByte() && header[3] == 0x46.toByte() &&
            header[8] == 0x57.toByte() && header[9] == 0x45.toByte() &&
            header[10] == 0x42.toByte() && header[11] == 0x50.toByte()
        ) {
            return "webp"
        }
        if (header.size >= 12 &&
            header[4] == 0x66.toByte() && header[5] == 0x74.toByte() &&
            header[6] == 0x79.toByte() && header[7] == 0x70.toByte()
        ) {
            val brand = String(header, 8, 4, Charsets.US_ASCII)
            return if (brand.startsWith("qt")) "mov" else "mp4"
        }
        return null
    }
}
