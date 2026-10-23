package io.github.backtraxe.anysave.telegram

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.min

/**
 * Makes a saved photo acceptable to the Bot API before upload.
 *
 * Telegram rejects photos that are >10 MB, have `width + height > 10000`, or an
 * aspect ratio above 20:1 — and some clients also reject WebP/AVIF payloads.
 * Photos are only re-encoded when one of those applies; normal JPEG/PNG images
 * keep their exact bytes.
 */
object PhotoCompressor {

    data class Prepared(val bytes: ByteArray)

    private const val MAX_DIMENSION = 2560
    private const val MAX_DIMENSION_SUM = 9000
    private const val MAX_ASPECT_RATIO = 18.0
    private const val MIN_SIDE = 320

    /** True when [bytes] must be decoded/re-encoded before sendPhoto/sendMediaGroup. */
    fun needsPreparation(bytes: ByteArray, mimeType: String, limitBytes: Long): Boolean {
        val mime = mimeType.lowercase()
        if (mime != "image/jpeg" && mime != "image/png") return true
        if (bytes.size.toLong() > limitBytes) return true
        val bounds = bounds(bytes) ?: return false
        if (bounds.first <= 0 || bounds.second <= 0) return true
        return violatesDimensions(bounds.first, bounds.second)
    }

    /** @return JPEG bytes ready for Telegram, or null when the image cannot be decoded. */
    fun prepare(bytes: ByteArray, limitBytes: Long): Prepared? {
        return try {
            val (srcWidth, srcHeight) = bounds(bytes) ?: return null
            if (srcWidth <= 0 || srcHeight <= 0) return null

            var sample = 1
            while (max(srcWidth, srcHeight) / sample > MAX_DIMENSION * 2) sample *= 2
            val decoded = BitmapFactory.decodeByteArray(
                bytes,
                0,
                bytes.size,
                BitmapFactory.Options().apply { inSampleSize = sample },
            ) ?: return null

            try {
                var bitmap = scaleToLimits(decoded)
                try {
                    var quality = 92
                    while (true) {
                        val out = ByteArrayOutputStream()
                        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
                        if (out.size().toLong() <= limitBytes) return Prepared(out.toByteArray())
                        if (quality > 50) {
                            quality -= 8
                            continue
                        }
                        val scaled = Bitmap.createScaledBitmap(
                            bitmap,
                            (bitmap.width * 3 / 4).coerceAtLeast(MIN_SIDE),
                            (bitmap.height * 3 / 4).coerceAtLeast(MIN_SIDE),
                            true,
                        )
                        if (scaled.width >= bitmap.width && scaled.height >= bitmap.height) {
                            return null
                        }
                        if (scaled !== bitmap) {
                            bitmap.recycle()
                            bitmap = scaled
                        }
                        quality = 85
                    }
                    @Suppress("UNREACHABLE_CODE")
                    null
                } finally {
                    if (!bitmap.isRecycled) bitmap.recycle()
                }
            } finally {
                if (!decoded.isRecycled) decoded.recycle()
            }
        } catch (t: Throwable) {
            null
        }
    }

    private fun scaleToLimits(source: Bitmap): Bitmap {
        var bitmap = source
        val width = bitmap.width
        val height = bitmap.height
        val longest = max(width, height)
        val target = min(
            min(1.0, MAX_DIMENSION.toDouble() / longest),
            min(1.0, MAX_DIMENSION_SUM.toDouble() / (width + height)),
        )
        if (target < 1.0) {
            val scaled = Bitmap.createScaledBitmap(
                bitmap,
                (width * target).toInt().coerceAtLeast(MIN_SIDE),
                (height * target).toInt().coerceAtLeast(MIN_SIDE),
                true,
            )
            if (scaled !== bitmap) {
                bitmap.recycle()
                bitmap = scaled
            }
        }

        // Uniform scaling cannot fix an extreme aspect ratio; crop the long
        // side instead so the Bot API accepts the photo.
        val w = bitmap.width
        val h = bitmap.height
        val ratio = max(w, h).toDouble() / max(1, min(w, h))
        if (ratio > MAX_ASPECT_RATIO) {
            val newW = if (w >= h) (h * MAX_ASPECT_RATIO).toInt() else w
            val newH = if (h > w) (w * MAX_ASPECT_RATIO).toInt() else h
            val cropped = Bitmap.createBitmap(bitmap, (w - newW) / 2, (h - newH) / 2, newW, newH)
            if (cropped !== bitmap) {
                bitmap.recycle()
                bitmap = cropped
            }
        }
        return bitmap
    }

    private fun violatesDimensions(width: Int, height: Int): Boolean {
        if (width + height > MAX_DIMENSION_SUM) return true
        val ratio = max(width, height).toDouble() / max(1, min(width, height))
        return ratio > MAX_ASPECT_RATIO
    }

    private fun bounds(bytes: ByteArray): Pair<Int, Int>? {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        if (options.outWidth <= 0 || options.outHeight <= 0) return null
        return options.outWidth to options.outHeight
    }
}
