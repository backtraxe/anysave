package io.github.backtraxe.anysave.download

import io.github.backtraxe.anysave.model.MediaItem
import io.github.backtraxe.anysave.model.MediaKind
import io.github.backtraxe.anysave.model.PostMedia

/**
 * Pure naming/placement rules for saved media. No Android APIs, so it is unit
 * tested on the JVM.
 *
 * Layout on the device:
 *   Pictures/AnySave/<site>_<postId>_01.jpg
 *   Movies/AnySave/<site>_<postId>_video.mp4
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
        val extension: String,
    )

    fun target(post: PostMedia, item: MediaItem): Target = targetFor(post, item, item.url)

    /** Same as [target] but derives the extension from [url] (primary or fallback). */
    fun targetFor(post: PostMedia, item: MediaItem, url: String): Target {
        val isVideo = item.kind == MediaKind.VIDEO
        val extension = extensionOf(url, item.kind)
        val ordinal = if (item.index > 0) item.index else 1
        val suffix = if (isVideo) "video" else ordinal.toString().padStart(2, '0')
        val postId = post.awemeId.ifBlank { "post" }
        val siteId = post.site.id
        return Target(
            displayName = "${siteId}_${postId}_$suffix.$extension",
            mimeType = mimeOf(extension, item.kind),
            relativeDir = if (isVideo) MOVIES_DIR else PICTURES_DIR,
            isVideo = isVideo,
            extension = extension,
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
