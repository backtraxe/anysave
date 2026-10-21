package io.github.backtraxe.anysave.download

import io.github.backtraxe.anysave.model.MediaItem
import io.github.backtraxe.anysave.model.MediaKind
import io.github.backtraxe.anysave.model.PostMedia

/**
 * Pure naming/placement rules for saved media. No Android APIs, so it is unit
 * tested on the JVM.
 *
 * Layout on the device:
 *   Pictures/AnySave/douyin_<awemeId>_01.jpg
 *   Movies/AnySave/douyin_<awemeId>_video.mp4
 */
object MediaNaming {
    const val ALBUM = "AnySave"
    const val PICTURES_DIR = "Pictures/$ALBUM"
    const val MOVIES_DIR = "Movies/$ALBUM"

    data class Target(
        val displayName: String,
        val mimeType: String,
        val relativeDir: String,
        val isVideo: Boolean,
    )

    fun target(post: PostMedia, item: MediaItem): Target {
        val isVideo = item.kind == MediaKind.VIDEO
        val extension = extensionOf(item.url, item.kind)
        val ordinal = if (item.index > 0) item.index else 1
        val suffix = if (isVideo) "video" else ordinal.toString().padStart(2, '0')
        val awemeId = post.awemeId.ifBlank { "post" }
        return Target(
            displayName = "douyin_${awemeId}_$suffix.$extension",
            mimeType = mimeOf(extension, item.kind),
            relativeDir = if (isVideo) MOVIES_DIR else PICTURES_DIR,
            isVideo = isVideo,
        )
    }

    /** Best-effort extension from the CDN path; signed query strings are ignored. */
    fun extensionOf(url: String, kind: MediaKind): String {
        val path = url.substringBefore('?').substringBefore('#').lowercase()
        val candidate = path.substringAfterLast('.', "")
        return when {
            candidate == "jpg" || candidate == "jpeg" -> "jpg"
            candidate == "webp" -> "webp"
            candidate == "png" -> "png"
            candidate == "gif" -> "gif"
            candidate == "mp4" -> "mp4"
            candidate == "mov" -> "mov"
            kind == MediaKind.VIDEO -> "mp4"
            else -> "jpg"
        }
    }

    fun mimeOf(extension: String, kind: MediaKind): String = when (extension) {
        "jpg" -> "image/jpeg"
        "webp" -> "image/webp"
        "png" -> "image/png"
        "gif" -> "image/gif"
        "mp4" -> "video/mp4"
        "mov" -> "video/quicktime"
        else -> if (kind == MediaKind.VIDEO) "video/mp4" else "image/jpeg"
    }
}
