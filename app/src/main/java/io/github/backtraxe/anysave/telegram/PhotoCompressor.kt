package io.github.backtraxe.anysave.telegram

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream
import kotlin.math.max

/**
 * Downscales/re-encodes an oversized photo so the Bot API photo limit does not
 * silently drop it from an album. Videos are not re-encoded here.
 *
 * Only used for images above `TelegramProtocol.MAX_PHOTO_BYTES`; normal images
 * are uploaded byte-for-byte.
 */
object PhotoCompressor {

    private const val MAX_DIMENSION = 2560
    private const val MIN_DIMENSION = 640

    /** @return JPEG bytes within [limitBytes], or null when it cannot be encoded. */
    fun compressToJpeg(bytes: ByteArray, limitBytes: Long): ByteArray? {
        return try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / sample > MAX_DIMENSION) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return null
        try {
            var bitmap = decoded
            var quality = 92
            while (true) {
                val out = ByteArrayOutputStream()
                bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
                if (out.size() <= limitBytes) return out.toByteArray()
                if (quality > 50) {
                    quality -= 8
                    continue
                }
                val newWidth = (bitmap.width * 3 / 4).coerceAtLeast(MIN_DIMENSION)
                val newHeight = (bitmap.height * 3 / 4).coerceAtLeast(MIN_DIMENSION)
                if (newWidth >= bitmap.width && newHeight >= bitmap.height) return null
                val scaled = Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
                if (scaled !== bitmap) {
                    if (bitmap !== decoded) bitmap.recycle()
                    bitmap = scaled
                }
                quality = 85
            }
            @Suppress("UNREACHABLE_CODE")
            null
        } finally {
            if (decoded !== null && !decoded.isRecycled) decoded.recycle()
        }
        } catch (t: Throwable) {
            null
        }
    }
}
