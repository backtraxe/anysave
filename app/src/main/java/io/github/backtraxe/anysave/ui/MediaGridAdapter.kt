package io.github.backtraxe.anysave.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.OvershootInterpolator
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import io.github.backtraxe.anysave.R
import io.github.backtraxe.anysave.model.MediaItem
import io.github.backtraxe.anysave.model.MediaKind
import java.util.Locale
import kotlin.math.roundToInt

/**
 * 3-column preview grid. The adapter only owns the *visible* slice (the panel
 * re-submits when the filter changes), while selection lives in the panel and
 * is mirrored here for card chrome and accessibility state.
 */
internal class MediaGridAdapter(
    private val onCardClick: (MediaItem) -> Unit,
    private val onCardLongClick: (MediaItem) -> Unit,
) : RecyclerView.Adapter<MediaGridAdapter.MediaViewHolder>() {

    private val items = ArrayList<MediaItem>()
    private val selectedIds = HashSet<String>()
    private var ordinals: Map<String, Int> = emptyMap()
    private var totalCount = 0
    private var animateChipId: String? = null
    private var cellWidthPx = 0
    private var cellHeightPx = 0

    fun submit(
        visibleItems: List<MediaItem>,
        selected: Set<String>,
        ordinalMap: Map<String, Int>,
        total: Int,
    ) {
        items.clear()
        items.addAll(visibleItems)
        selectedIds.clear()
        selectedIds.addAll(selected)
        ordinals = ordinalMap
        totalCount = total
        notifyDataSetChanged()
    }

    /** Re-renders card chrome after a selection change; [animateForId] pops the chip. */
    fun updateSelection(selected: Set<String>, animateForId: String? = null) {
        selectedIds.clear()
        selectedIds.addAll(selected)
        animateChipId = animateForId
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MediaViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_media, parent, false)
        if (cellWidthPx <= 0) {
            val metrics = parent.resources.displayMetrics
            val nonCellSpacePx = (GRID_SIDE_SPACE_DP * metrics.density).roundToInt()
            val available = (parent.width.takeIf { it > 0 } ?: metrics.widthPixels) - nonCellSpacePx
            cellWidthPx = (available / 3f).roundToInt()
                .coerceAtLeast((MIN_CELL_DP * metrics.density).roundToInt())
            cellHeightPx = (cellWidthPx * 4f / 3f).roundToInt()
        }
        return MediaViewHolder(view)
    }

    override fun onBindViewHolder(holder: MediaViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun onViewRecycled(holder: MediaViewHolder) {
        holder.recycle()
    }

    inner class MediaViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {

        private val thumb: ImageView = itemView.findViewById(R.id.mediaThumb)
        private val placeholderIcon: ImageView = itemView.findViewById(R.id.mediaPlaceholderIcon)
        private val playGlyph: View = itemView.findViewById(R.id.mediaPlayGlyph)
        private val duration: TextView = itemView.findViewById(R.id.mediaDuration)
        private val selectionBorder: View = itemView.findViewById(R.id.mediaSelectionBorder)
        private val checkChip: View = itemView.findViewById(R.id.mediaCheckChip)

        private var token: Any? = null
        private var boundItem: MediaItem? = null

        init {
            itemView.setOnClickListener { boundItem?.let(onCardClick) }
            itemView.setOnLongClickListener {
                val item = boundItem
                if (item != null) {
                    onCardLongClick(item)
                    true
                } else {
                    false
                }
            }
        }

        fun bind(item: MediaItem) {
            boundItem = item
            val isVideo = item.kind == MediaKind.VIDEO
            val selected = item.id in selectedIds
            val requestToken = Any()
            token = requestToken

            // Reset everything first: a recycled view must never flash stale art.
            thumb.setImageDrawable(null)
            thumb.visibility = View.INVISIBLE
            placeholderIcon.visibility = View.GONE
            duration.visibility = View.GONE
            checkChip.animate().cancel()
            checkChip.scaleX = 1f
            checkChip.scaleY = 1f
            checkChip.alpha = 1f
            playGlyph.visibility = if (isVideo) View.VISIBLE else View.GONE

            if (isVideo && item.durationMs > 0L) {
                duration.text = formatDurationMs(item.durationMs)
                duration.visibility = View.VISIBLE
            }

            val popChip = animateChipId == item.id
            if (popChip) animateChipId = null
            applySelection(selected, animate = selected && popChip)

            val coverUrl = when {
                item.previewUrl.isNotBlank() -> item.previewUrl
                !isVideo -> item.url
                else -> ""
            }
            if (coverUrl.isBlank()) {
                if (!isVideo) placeholderIcon.visibility = View.VISIBLE
            } else {
                ThumbnailLoader.load(coverUrl, cellWidthPx, cellHeightPx) { bitmap ->
                    if (token !== requestToken || boundItem?.id != item.id) return@load
                    if (bitmap != null) {
                        thumb.setImageBitmap(bitmap)
                        thumb.visibility = View.VISIBLE
                    } else {
                        thumb.visibility = View.INVISIBLE
                        if (!isVideo) placeholderIcon.visibility = View.VISIBLE
                    }
                }
            }

            itemView.contentDescription = describe(item, selected)
        }

        fun recycle() {
            token = null
            boundItem = null
            checkChip.animate().cancel()
        }

        private fun applySelection(selected: Boolean, animate: Boolean) {
            itemView.isSelected = selected
            selectionBorder.visibility = if (selected) View.VISIBLE else View.INVISIBLE
            checkChip.visibility = if (selected) View.VISIBLE else View.INVISIBLE
            if (selected && animate) {
                checkChip.scaleX = 0.6f
                checkChip.scaleY = 0.6f
                checkChip.alpha = 0f
                checkChip.animate()
                    .scaleX(1f)
                    .scaleY(1f)
                    .alpha(1f)
                    .setDuration(CHIP_ANIM_MS)
                    .setInterpolator(OvershootInterpolator(2.5f))
                    .start()
            }
        }

        private fun describe(item: MediaItem, selected: Boolean): String {
            val context = itemView.context
            val ordinal = ordinals[item.id] ?: 0
            val base = when {
                item.kind == MediaKind.VIDEO && ordinal > 0 && item.durationMs > 0L ->
                    context.getString(
                        R.string.preview_item_description_video,
                        ordinal,
                        totalCount,
                        formatDurationMs(item.durationMs),
                    )

                item.kind == MediaKind.VIDEO && ordinal > 0 ->
                    context.getString(
                        R.string.preview_item_description_video_no_duration,
                        ordinal,
                        totalCount,
                    )

                item.kind == MediaKind.IMAGE && ordinal > 0 ->
                    context.getString(R.string.preview_item_description_image, ordinal, totalCount)

                item.kind == MediaKind.VIDEO -> context.getString(R.string.preview_item_video_generic)
                else -> context.getString(R.string.preview_item_image_generic)
            }
            val state = context.getString(
                if (selected) R.string.preview_item_selected else R.string.preview_item_not_selected,
            )
            return "$base, $state"
        }
    }

    private companion object {
        /** Grid horizontal padding (12 x 2) + card margins (4 x 2 per cell x 3). */
        private const val GRID_SIDE_SPACE_DP = 48f
        private const val MIN_CELL_DP = 72f
        private const val CHIP_ANIM_MS = 140L
    }
}

/** `00:15`, `03:05`, `1:02:03`. */
internal fun formatDurationMs(durationMs: Long): String {
    val totalSeconds = (durationMs / 1000L).coerceAtLeast(0L)
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.US, "%02d:%02d", minutes, seconds)
    }
}
