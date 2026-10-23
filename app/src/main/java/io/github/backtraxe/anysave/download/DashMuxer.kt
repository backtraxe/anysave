package io.github.backtraxe.anysave.download

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import io.github.backtraxe.anysave.log.AppLog
import java.io.File
import java.nio.ByteBuffer

/**
 * Remuxes bilibili's separate DASH tracks into one playable MP4.
 *
 * Both `.m4s` files are fragmented MP4; [MediaExtractor] reads their samples and
 * [MediaMuxer] writes an ordinary MP4 with one video and one audio track. This
 * is a container remux only — no transcoding, so it is fast and lossless.
 */
object DashMuxer {
    private const val TAG = "AnySave"

    /** @return true when [outputFile] contains both tracks and is non-empty. */
    fun mux(videoFile: File, audioFile: File, outputFile: File): Boolean {
        var videoExtractor: MediaExtractor? = null
        var audioExtractor: MediaExtractor? = null
        var muxer: MediaMuxer? = null
        return try {
            videoExtractor = MediaExtractor().apply { setDataSource(videoFile.absolutePath) }
            audioExtractor = MediaExtractor().apply { setDataSource(audioFile.absolutePath) }

            val videoTrack = findTrack(videoExtractor, "video/")
            val audioTrack = findTrack(audioExtractor, "audio/")
            if (videoTrack < 0 || audioTrack < 0) {
                AppLog.w(TAG, "mux skipped: videoTrack=$videoTrack audioTrack=$audioTrack")
                return false
            }

            videoExtractor.selectTrack(videoTrack)
            audioExtractor.selectTrack(audioTrack)
            val videoFormat = videoExtractor.getTrackFormat(videoTrack)
            val audioFormat = audioExtractor.getTrackFormat(audioTrack)

            muxer = MediaMuxer(
                outputFile.absolutePath,
                MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4,
            )
            val videoOut = muxer.addTrack(videoFormat)
            val audioOut = muxer.addTrack(audioFormat)
            muxer.start()
            val videoBytes = copyTrack(videoExtractor, videoFormat, muxer, videoOut)
            val audioBytes = copyTrack(audioExtractor, audioFormat, muxer, audioOut)
            muxer.stop()
            AppLog.i(
                TAG,
                "muxed video=${videoBytes}B audio=${audioBytes}B -> ${outputFile.length()}B",
            )
            outputFile.length() > 0
        } catch (t: Throwable) {
            AppLog.w(TAG, "dash mux failed: ${t.message}", t)
            false
        } finally {
            try {
                muxer?.release()
            } catch (_: Throwable) {
            }
            try {
                videoExtractor?.release()
            } catch (_: Throwable) {
            }
            try {
                audioExtractor?.release()
            } catch (_: Throwable) {
            }
        }
    }

    private fun findTrack(extractor: MediaExtractor, mimePrefix: String): Int {
        for (i in 0 until extractor.trackCount) {
            val mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME).orEmpty()
            if (mime.startsWith(mimePrefix)) return i
        }
        return -1
    }

    private fun copyTrack(
        extractor: MediaExtractor,
        format: MediaFormat,
        muxer: MediaMuxer,
        outputTrack: Int,
    ): Long {
        val maxInputSize = format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE)
            .takeIf { it > 0 } ?: (2 * 1024 * 1024)
        val buffer = ByteBuffer.allocateDirect(maxOf(maxInputSize, 1024 * 1024))
        val info = MediaCodec.BufferInfo()
        var written = 0L
        while (true) {
            val size = extractor.readSampleData(buffer, 0)
            if (size < 0) break
            buffer.position(0)
            buffer.limit(size)
            info.offset = 0
            info.size = size
            info.presentationTimeUs = extractor.sampleTime
            info.flags = if (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) {
                MediaCodec.BUFFER_FLAG_KEY_FRAME
            } else {
                0
            }
            muxer.writeSampleData(outputTrack, buffer, info)
            written += size
            extractor.advance()
        }
        return written
    }
}
