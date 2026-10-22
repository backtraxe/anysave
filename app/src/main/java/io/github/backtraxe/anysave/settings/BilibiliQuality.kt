package io.github.backtraxe.anysave.settings

import android.content.Context

/**
 * Preferred maximum height for the AVC rendition picked from bilibili's DASH
 * manifest (M14). 0 = Auto (highest available).
 *
 * The page only offers renditions the current session may play, so the cap is a
 * preference on top of that, not a way to unlock higher quality.
 */
object BilibiliQuality {
    const val AUTO = 0
    const val P1080 = 1080
    const val P720 = 720
    const val P480 = 480

    private const val PREFS = "anysave_quality"
    private const val KEY_MAX_HEIGHT = "bilibili_max_height"

    fun maxHeight(context: Context): Int =
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_MAX_HEIGHT, AUTO)
            .takeIf { it in setOf(AUTO, P1080, P720, P480) }
            ?: AUTO

    fun setMaxHeight(context: Context, height: Int) {
        val normalized = height.takeIf { it in setOf(AUTO, P1080, P720, P480) } ?: AUTO
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_MAX_HEIGHT, normalized)
            .apply()
        BilibiliQualityConfig.maxHeight = normalized
    }
}

/** Process-wide value read by [io.github.backtraxe.anysave.reader.BilibiliReader]. */
object BilibiliQualityConfig {
    @Volatile
    var maxHeight: Int = BilibiliQuality.AUTO
}
