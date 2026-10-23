package io.github.backtraxe.anysave.reader

import android.content.Context
import io.github.backtraxe.anysave.log.AppLog
import java.io.File

/**
 * Process-wide reader overrides loaded from a persisted reader pack.
 *
 * Only the page-side script and response patterns can be overridden; the
 * compiled parsers and all network behaviour stay in the APK.
 */
object ReaderOverrides {
    private const val TAG = "AnySave"
    private const val FILE_NAME = "reader-pack.json"

    @Volatile
    private var overrides: Map<String, ReaderOverride> = emptyMap()

    /** Loads the persisted pack (if any). Safe to call repeatedly. */
    fun load(context: Context) {
        val file = packFile(context)
        if (!file.exists()) {
            overrides = emptyMap()
            return
        }
        overrides = try {
            val pack = ReaderPackParser.parse(file.readText())
            pack.readers.associateBy { it.readerId }
        } catch (t: Throwable) {
            AppLog.w(TAG, "ignoring invalid reader pack: ${t.message}")
            emptyMap()
        }
    }

    /**
     * Verifies and applies a pack, persisting the raw JSON for the next launch.
     *
     * @return number of applied overrides.
     * @throws IllegalArgumentException when the pack is invalid (old pack stays active).
     */
    fun apply(context: Context, packJson: String): Int {
        val pack = ReaderPackParser.parse(packJson)
        val file = packFile(context)
        file.parentFile?.mkdirs()
        file.writeText(packJson)
        overrides = pack.readers.associateBy { it.readerId }
        AppLog.i(TAG, "reader pack applied: ${pack.readers.map { it.readerId }}")
        return pack.readers.size
    }

    fun clear(context: Context) {
        packFile(context).delete()
        overrides = emptyMap()
    }

    fun patterns(reader: SiteReader): List<String> =
        overrides[reader.id]?.responsePatterns ?: reader.responsePatterns

    fun embeddedScript(reader: SiteReader): String? =
        overrides[reader.id]?.embeddedPayloadScript ?: reader.embeddedPayloadScript

    fun activeIds(): List<String> = overrides.keys.sorted()

    private fun packFile(context: Context): File =
        File(context.applicationContext.filesDir, FILE_NAME)
}
