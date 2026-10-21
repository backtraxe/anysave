package io.github.backtraxe.anysave.model

/** One observed network response that matched a reader's pattern. */
data class MediaHit(
    val via: String,
    val url: String,
    val status: Int,
    val length: Int,
)

/** Snapshot of one extraction attempt. */
data class ExtractionSnapshot(
    val pageUrl: String,
    val title: String,
    val patched: Boolean,
    val hits: List<MediaHit>,
    val elapsedMs: Long,
)
