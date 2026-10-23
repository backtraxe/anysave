package io.github.backtraxe.anysave.reader

import io.github.backtraxe.anysave.log.AppLog
import io.github.backtraxe.anysave.model.MediaItem
import io.github.backtraxe.anysave.model.MediaKind
import io.github.backtraxe.anysave.model.MediaSite
import io.github.backtraxe.anysave.model.PostMedia
import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.localization.Localization
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.VideoStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * YouTube support via NewPipeExtractor (M8).
 *
 * YouTube's page data only contains cipher-protected / ABR stream references, so
 * the WebView-observation approach cannot resolve playable URLs. The roadmap
 * explicitly allows a dedicated extractor as a fallback; NewPipeExtractor does
 * the player-protocol work. This is the only place in the app that talks to a
 * site's API directly instead of observing the page.
 *
 * Stream plan: prefer AVC MP4 video-only (≤720p) + AAC M4A audio and mux them
 * locally with [io.github.backtraxe.anysave.download.DashMuxer]; fall back to a
 * progressive MP4 (video+audio in one file) when muxable tracks are missing.
 */
object YouTubeExtractor : DirectExtractor {
    override val id = "youtube"

    private val URL_PATTERN = Regex(
        """(?:youtube\.com/(?:watch\?[^#]*v=|shorts/|live/)|youtu\.be/)([0-9A-Za-z_-]{11})""",
    )

    @Volatile
    private var initialized = false

    override fun matches(url: String): Boolean = URL_PATTERN.containsMatchIn(url)

    override fun extract(url: String): PostMedia? {
        if (!matches(url)) return null
        return try {
            ensureInitialized()
            val info = StreamInfo.getInfo(ServiceList.YouTube, url)
            toPost(info, url)
        } catch (t: Throwable) {
            AppLog.w(TAG, "youtube extraction failed: ${t.message}")
            null
        }
    }

    internal fun toPost(info: StreamInfo, sourceUrl: String): PostMedia? {
        val videoOnly = info.videoOnlyStreams.mapNotNull { it.toCandidate() }
        val progressive = info.videoStreams.mapNotNull { it.toCandidate() }
        val audio = info.audioStreams.mapNotNull { it.toCandidate() }
        val plan = YouTubePlanPicker.decide(videoOnly, progressive, audio) ?: return null

        val cover = info.thumbnails
            .maxByOrNull { it.width.toLong() * it.height }
            ?.url
            .orEmpty()
        val durationMs = (info.duration.coerceAtLeast(0L)) * 1000L
        val item = MediaItem(
            id = "${info.id}-video-1",
            kind = MediaKind.VIDEO,
            url = plan.videoUrl,
            previewUrl = cover,
            audioUrl = plan.audioUrl.orEmpty(),
            width = plan.width,
            height = plan.height,
            durationMs = durationMs,
            index = 1,
        )
        return PostMedia(
            awemeId = info.id,
            author = info.uploaderName.orEmpty(),
            desc = info.name.orEmpty(),
            sourceUrl = sourceUrl,
            items = listOf(item),
            site = MediaSite.YOUTUBE,
        )
    }

    private fun ensureInitialized() {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            NewPipe.init(HttpDownloader, Localization("en", "US"))
            initialized = true
        }
    }

    private fun VideoStream.toCandidate(): StreamCandidate? {
        val mediaUrl = url ?: return null
        val formatName = format?.name ?: ""
        val heightPx = getHeight().takeIf { it > 0 } ?: resolutionHeight(resolution)
        val widthPx = getWidth().takeIf { it > 0 } ?: (heightPx * 16 / 9)
        return StreamCandidate(
            url = mediaUrl,
            format = formatName,
            codec = codec.orEmpty(),
            width = widthPx,
            height = heightPx,
            bitrate = getBitrate(),
            isVideoOnly = isVideoOnly,
        )
    }

    private fun AudioStream.toCandidate(): StreamCandidate? {
        val mediaUrl = url ?: return null
        return StreamCandidate(
            url = mediaUrl,
            format = format?.name ?: "",
            codec = codec.orEmpty(),
            bitrate = getAverageBitrate().takeIf { it > 0 } ?: getBitrate(),
            audioQuality = quality.orEmpty(),
        )
    }

    private fun resolutionHeight(resolution: String?): Int =
        resolution?.removeSuffix("p")?.toIntOrNull() ?: 0

    /** Minimal NewPipe Downloader over HttpURLConnection (no extra HTTP dep). */
    private object HttpDownloader : Downloader() {
        override fun execute(request: Request): Response {
            val connection = URL(request.url()).openConnection() as HttpURLConnection
            connection.requestMethod = request.httpMethod()
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.instanceFollowRedirects = true
            request.headers().forEach { (name, values) ->
                values.forEach { connection.addRequestProperty(name, it) }
            }
            val data = request.dataToSend()
            if (data != null && data.isNotEmpty()) {
                connection.doOutput = true
                connection.outputStream.use { it.write(data) }
            }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            val headers = connection.headerFields
                .filterKeys { it != null }
                .mapValues { entry -> entry.value ?: emptyList() }
            return Response(code, connection.responseMessage, headers, body, connection.url.toString())
        }
    }

    private const val TAG = "AnySave"
    private const val CONNECT_TIMEOUT_MS = 20_000
    private const val READ_TIMEOUT_MS = 40_000
}

/** One playable track candidate, decoupled from NewPipe types for unit tests. */
internal data class StreamCandidate(
    val url: String,
    val format: String,
    val codec: String,
    val width: Int = 0,
    val height: Int = 0,
    val bitrate: Int = 0,
    val audioQuality: String = "",
    val isVideoOnly: Boolean = false,
)

internal data class StreamPlan(
    val videoUrl: String,
    val audioUrl: String?,
    val width: Int,
    val height: Int,
    val progressive: Boolean,
)

/** Pure stream-selection rules (unit tested). */
internal object YouTubePlanPicker {
    const val MAX_MUX_HEIGHT = 720

    fun decide(
        videoOnly: List<StreamCandidate>,
        progressive: List<StreamCandidate>,
        audio: List<StreamCandidate>,
    ): StreamPlan? {
        val muxableVideo = videoOnly
            .filter { it.format == MediaFormat.MPEG_4.name && it.codec.startsWith("avc") }
            .let { avc ->
                avc.filter { it.height in 1..MAX_MUX_HEIGHT }.ifEmpty { avc }
            }
            .maxWithOrNull(compareBy({ it.height }, { it.bitrate }))

        val bestAudio = audio
            .filter { it.format == MediaFormat.M4A.name && it.codec.contains("mp4a.40.2") }
            .ifEmpty { audio.filter { it.format == MediaFormat.M4A.name } }
            .maxWithOrNull(compareBy({ it.bitrate }, { it.audioQuality.length }))

        if (muxableVideo != null && bestAudio != null) {
            return StreamPlan(
                videoUrl = muxableVideo.url,
                audioUrl = bestAudio.url,
                width = muxableVideo.width,
                height = muxableVideo.height,
                progressive = false,
            )
        }

        val singleFile = progressive
            .filter { it.format == MediaFormat.MPEG_4.name }
            .maxWithOrNull(compareBy({ it.height }, { it.bitrate }))
            ?: return null
        return StreamPlan(
            videoUrl = singleFile.url,
            audioUrl = null,
            width = singleFile.width,
            height = singleFile.height,
            progressive = true,
        )
    }
}
