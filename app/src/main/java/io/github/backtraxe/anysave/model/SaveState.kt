package io.github.backtraxe.anysave.model

/**
 * UI-facing state of a save (download to album) run.
 *
 * Owned by the download layer, rendered by the preview panel. Kept in the model
 * package so the panel and the extractor do not depend on service internals.
 */
sealed interface SaveState {
    /** Nothing has been saved this session yet. */
    data object Idle : SaveState

    /** A save run is in flight. Items are counted as soon as they succeed or fail. */
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
     * The run ended. [firstUri] points at the first saved item (content://) so
     * the UI can offer "open album"; null when nothing was saved.
     */
    data class Finished(
        val total: Int,
        val saved: Int,
        val failed: Int,
        val albumName: String,
        val firstUri: String?,
    ) : SaveState
}
