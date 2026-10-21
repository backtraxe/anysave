package io.github.backtraxe.anysave.model

/**
 * UI-facing state of a save (download to album, then optional Telegram upload) run.
 *
 * Owned by the download layer, rendered by the preview panel. Kept in the model
 * package so the panel and the extractor do not depend on service internals.
 */
sealed interface SaveState {
    /** Nothing has been saved this session yet. */
    data object Idle : SaveState

    /** Downloading the selection into the gallery. */
    data class Running(
        val total: Int,
        val done: Int,
        val failed: Int,
    ) : SaveState {
        val finished: Int get() = done + failed
        val progress: Float
            get() = if (total <= 0) 0f else (done + failed).toFloat() / total
    }

    /**
     * Everything is in the gallery; the same items are being sent to Telegram
     * (only when the user configured a bot and enabled auto upload).
     */
    data class Uploading(
        val total: Int,
        val uploaded: Int,
        val failed: Int,
    ) : SaveState {
        val finished: Int get() = uploaded + failed
        val progress: Float
            get() = if (total <= 0) 0f else (uploaded + failed).toFloat() / total
    }

    /**
     * The run ended. [firstUri] points at the first saved item (content://) so
     * the UI can offer "open album"; null when nothing was saved.
     *
     * [uploaded] / [uploadFailed] describe the optional Telegram step; both are
     * zero when no bot is configured or auto upload is off.
     */
    data class Finished(
        val total: Int,
        val saved: Int,
        val failed: Int,
        val albumName: String,
        val firstUri: String?,
        val uploaded: Int = 0,
        val uploadFailed: Int = 0,
    ) : SaveState {
        val telegramAttempted: Boolean get() = uploaded > 0 || uploadFailed > 0
    }
}
