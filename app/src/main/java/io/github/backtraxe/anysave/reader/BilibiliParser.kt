package io.github.backtraxe.anysave.reader

import io.github.backtraxe.anysave.model.MediaItem
import io.github.backtraxe.anysave.model.MediaKind
import io.github.backtraxe.anysave.model.MediaSite
import io.github.backtraxe.anysave.model.PostMedia
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * Turns a bilibili video page payload into a [PostMedia].
 *
 * The page embeds `__INITIAL_STATE__.videoData` (title/author/cover/duration)
 * and `__playinfo__` (DASH manifest: separate video and audio m4s tracks).
 * The app downloads both tracks and muxes them locally ([DashMuxer]) — no API
 * replay, no WBI signing.
 *
 * Rendition choice: prefer AVC (`codecid == 7`) for device compatibility, then
 * the highest quality id / bandwidth. Audio: highest quality id.
 */
object BilibiliParser {
    private const val AVC_CODEC_ID = 7

    fun parse(body: String, sourceUrl: String): PostMedia? {
        if (body.isBlank()) return null
        val root = try {
            JSONObject(body)
        } catch (t: JSONException) {
            return null
        }
        val videoData = root.optJSONObject("videoData") ?: return null
        val playinfo = root.optJSONObject("playinfo") ?: return null
        val dash = playinfo.optJSONObject("dash") ?: return null

        val bvid = videoData.optString("bvid").trim()
            .ifEmpty { BVID_IN_URL.find(sourceUrl)?.groupValues?.getOrNull(1).orEmpty() }
        if (bvid.isBlank()) return null

        val videoRenditions = dash.optJSONArray("video") ?: return null
        val audioRenditions = dash.optJSONArray("audio") ?: return null
        val video = pickVideo(videoRenditions) ?: return null
        val audio = pickAudio(audioRenditions) ?: return null
        val videoUrl = renditionUrl(video) ?: return null
        val audioUrl = renditionUrl(audio) ?: return null

        val durationMs = playinfo.optLong("timelength").takeIf { it > 0 }
            ?: videoData.optLong("duration").takeIf { it > 0 }?.times(1000)
            ?: dash.optLong("duration").takeIf { it > 0 }?.times(1000)
            ?: 0L

        val item = MediaItem(
            id = "$bvid-video-1",
            kind = MediaKind.VIDEO,
            url = videoUrl,
            previewUrl = coverUrl(videoData),
            fallbackUrl = backupUrl(video).orEmpty(),
            audioUrl = audioUrl,
            width = video.optInt("width"),
            height = video.optInt("height"),
            durationMs = durationMs,
            index = 1,
        )
        return PostMedia(
            awemeId = bvid,
            author = (videoData.optJSONObject("owner")?.optString("name") ?: "").trim(),
            desc = videoData.optString("title").trim(),
            sourceUrl = sourceUrl,
            items = listOf(item),
            site = MediaSite.BILIBILI,
        )
    }

    private fun pickVideo(renditions: JSONArray): JSONObject? {
        val all = (0 until renditions.length()).mapNotNull { renditions.optJSONObject(it) }
        if (all.isEmpty()) return null
        val avc = all.filter { it.optInt("codecid") == AVC_CODEC_ID }
        val pool = avc.ifEmpty { all }
        return pool.maxWithOrNull(
            compareBy({ it.optInt("id") }, { it.optLong("bandwidth") }),
        )
    }

    private fun pickAudio(renditions: JSONArray): JSONObject? {
        val all = (0 until renditions.length()).mapNotNull { renditions.optJSONObject(it) }
        return all.maxWithOrNull(compareBy({ it.optInt("id") }, { it.optLong("bandwidth") }))
    }

    private fun renditionUrl(rendition: JSONObject): String? {
        return normalizeUrl(rendition.optString("baseUrl"))
            ?: normalizeUrl(rendition.optString("base_url"))
    }

    private fun backupUrl(rendition: JSONObject): String? {
        val backups = rendition.optJSONArray("backupUrl")
            ?: rendition.optJSONArray("backup_url")
            ?: return null
        for (i in 0 until backups.length()) {
            normalizeUrl(backups.optString(i))?.let { return it }
        }
        return null
    }

    private fun coverUrl(videoData: JSONObject): String =
        normalizeUrl(videoData.optString("pic")).orEmpty()

    private fun normalizeUrl(raw: String?): String? {
        val url = raw?.trim().orEmpty()
        if (url.isEmpty()) return null
        return when {
            url.startsWith("//") -> "https:$url"
            url.startsWith("https://") -> url
            url.startsWith("http://") -> "https://${url.removePrefix("http://")}"
            else -> null
        }
    }

    private val BVID_IN_URL = Regex("/video/(BV[0-9A-Za-z]+)")
}
