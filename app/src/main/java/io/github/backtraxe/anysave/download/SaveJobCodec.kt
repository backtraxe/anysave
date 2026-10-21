package io.github.backtraxe.anysave.download

import io.github.backtraxe.anysave.model.MediaItem
import io.github.backtraxe.anysave.model.MediaKind
import io.github.backtraxe.anysave.model.PostMedia
import org.json.JSONArray
import org.json.JSONObject

/** One save run: the post header plus the exact items the user picked, in order. */
data class SaveJob(
    val post: PostMedia,
    val items: List<MediaItem>,
)

/**
 * Serializes a [SaveJob] for the Intent that starts [SaveService].
 *
 * The service lives in the same process but an Intent extra must be a primitive,
 * so a compact JSON blob is the least ceremony (no Parcelable plugin needed).
 */
object SaveJobCodec {

    fun encode(job: SaveJob): String {
        val post = JSONObject()
            .put("awemeId", job.post.awemeId)
            .put("author", job.post.author)
            .put("desc", job.post.desc)
            .put("sourceUrl", job.post.sourceUrl)
        val items = JSONArray()
        job.items.forEach { item ->
            items.put(
                JSONObject()
                    .put("id", item.id)
                    .put("kind", item.kind.name)
                    .put("url", item.url)
                    .put("previewUrl", item.previewUrl)
                    .put("width", item.width)
                    .put("height", item.height)
                    .put("durationMs", item.durationMs)
                    .put("index", item.index),
            )
        }
        return JSONObject()
            .put("post", post)
            .put("items", items)
            .toString()
    }

    fun decode(raw: String?): SaveJob? {
        if (raw.isNullOrBlank()) return null
        return try {
            val root = JSONObject(raw)
            val postJson = root.optJSONObject("post") ?: return null
            val itemsJson = root.optJSONArray("items") ?: return null
            val items = ArrayList<MediaItem>(itemsJson.length())
            for (i in 0 until itemsJson.length()) {
                val obj = itemsJson.optJSONObject(i) ?: continue
                val url = obj.optString("url")
                if (url.isBlank()) continue
                items += MediaItem(
                    id = obj.optString("id"),
                    kind = if (obj.optString("kind") == MediaKind.VIDEO.name) {
                        MediaKind.VIDEO
                    } else {
                        MediaKind.IMAGE
                    },
                    url = url,
                    previewUrl = obj.optString("previewUrl"),
                    width = obj.optInt("width"),
                    height = obj.optInt("height"),
                    durationMs = obj.optLong("durationMs"),
                    index = obj.optInt("index"),
                )
            }
            if (items.isEmpty()) return null
            SaveJob(
                post = PostMedia(
                    awemeId = postJson.optString("awemeId"),
                    author = postJson.optString("author"),
                    desc = postJson.optString("desc"),
                    sourceUrl = postJson.optString("sourceUrl"),
                    items = items,
                ),
                items = items,
            )
        } catch (t: Throwable) {
            null
        }
    }
}
