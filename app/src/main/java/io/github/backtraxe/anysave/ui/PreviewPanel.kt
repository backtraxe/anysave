package io.github.backtraxe.anysave.ui

import android.app.Activity
import android.graphics.Color
import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.view.WindowInsets
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import io.github.backtraxe.anysave.R
import io.github.backtraxe.anysave.model.MediaItem
import io.github.backtraxe.anysave.model.MediaKind
import io.github.backtraxe.anysave.model.PostMedia
import io.github.backtraxe.anysave.model.SaveState
import kotlin.math.roundToInt

/** Convenience alias so hosts can reference the nested interface unqualified. */
typealias SaveActions = PreviewPanel.SaveActions

/**
 * Preview overlay: post header, media grid with thumbnails, filtering,
 * multi-select and the M2/M3 save flow (idle CTA -> gallery progress ->
 * optional Telegram upload -> result).
 *
 * The panel inflates itself into [root] (the full-screen `previewRoot`
 * FrameLayout owned by the activity) and stays hidden until [show] is called.
 * Selection is owned here so switching filters never loses what the user
 * picked; the host drives the save UI through [setSaveActions] and
 * [renderSaveState], and must not change the save state itself.
 */
class PreviewPanel(
    private val activity: Activity,
    private val root: FrameLayout,
    private val onClose: () -> Unit,
) {

    /** Host callbacks for the save flow. Implemented by the activity/service glue. */
    interface SaveActions {
        /** Save the selected items, in post order (never filter order). */
        fun onSaveRequested(items: List<MediaItem>)

        /** Open the system gallery at the first saved item. */
        fun onOpenAlbum()
    }

    private enum class Filter { ALL, IMAGES, VIDEOS }

    private val panel: View =
        LayoutInflater.from(activity).inflate(R.layout.preview_panel, root, false)
    private val content: View = panel.findViewById(R.id.previewPanelContent)
    private val topBar: View = panel.findViewById(R.id.panelTopBar)
    private val footerBody: View = panel.findViewById(R.id.footerBody)
    private val backButton: View = panel.findViewById(R.id.panelBack)
    private val subtitle: TextView = panel.findViewById(R.id.panelSubtitle)
    private val avatar: TextView = panel.findViewById(R.id.postAvatar)
    private val author: TextView = panel.findViewById(R.id.postAuthor)
    private val source: TextView = panel.findViewById(R.id.postSource)
    private val description: TextView = panel.findViewById(R.id.postDesc)
    private val filterRow: View = panel.findViewById(R.id.filterRow)
    private val filterPills: Map<Filter, TextView> = mapOf(
        Filter.ALL to panel.findViewById(R.id.filterAll),
        Filter.IMAGES to panel.findViewById(R.id.filterImages),
        Filter.VIDEOS to panel.findViewById(R.id.filterVideos),
    )
    private val grid: RecyclerView = panel.findViewById(R.id.mediaGrid)
    private val emptyState: View = panel.findViewById(R.id.emptyState)
    private val emptyIcon: ImageView = panel.findViewById(R.id.emptyIcon)
    private val emptyTitle: TextView = panel.findViewById(R.id.emptyTitle)
    private val emptyBody: TextView = panel.findViewById(R.id.emptyBody)
    private val emptyAction: TextView = panel.findViewById(R.id.emptyAction)

    private val footer: View = panel.findViewById(R.id.panelFooter)
    private val footerCount: TextView = panel.findViewById(R.id.footerCount)
    private val footerSecondary: TextView = panel.findViewById(R.id.footerSecondary)
    private val saveButton: TextView = panel.findViewById(R.id.saveButton)
    private val saveProgress: ProgressBar = panel.findViewById(R.id.saveProgress)
    private val saveRunningText: TextView = panel.findViewById(R.id.saveRunningText)
    private val saveFinishedSummary: TextView = panel.findViewById(R.id.saveFinishedSummary)
    private val saveFinishedFailed: TextView = panel.findViewById(R.id.saveFinishedFailed)
    private val telegramUploadedText: TextView = panel.findViewById(R.id.telegramUploadedText)
    private val telegramUploadFailedText: TextView = panel.findViewById(R.id.telegramUploadFailedText)
    private val footerFinishedActions: View = panel.findViewById(R.id.footerFinishedActions)
    private val saveDoneButton: TextView = panel.findViewById(R.id.saveDoneButton)
    private val saveOpenAlbumButton: TextView = panel.findViewById(R.id.saveOpenAlbumButton)

    private val adapter = MediaGridAdapter(::onCardClick, ::onCardLongClick)
    private val selectedIds = LinkedHashSet<String>()

    private var post: PostMedia? = null
    private var filter = Filter.ALL
    private var ordinals: Map<String, Int> = emptyMap()

    private var saveActions: SaveActions? = null
    private var saveState: SaveState = SaveState.Idle

    /** Guards against an exit animation ending after a new [show] started. */
    private var stateToken = 0

    // Captured so window insets can be added without losing the XML padding.
    private val topBarBasePadding = intArrayOf(
        topBar.paddingLeft,
        topBar.paddingTop,
        topBar.paddingRight,
        topBar.paddingBottom,
    )
    private val footerBodyBasePadding = intArrayOf(
        footerBody.paddingLeft,
        footerBody.paddingTop,
        footerBody.paddingRight,
        footerBody.paddingBottom,
    )

    private val brandColor: Int = activity.getColor(R.color.brand)
    private val idlePillColor: Int = activity.getColor(R.color.preview_text_secondary)

    var isShowing: Boolean = false
        private set

    init {
        root.addView(panel)
        panel.visibility = View.GONE

        grid.layoutManager = GridLayoutManager(activity, GRID_COLUMNS)
        grid.adapter = adapter
        grid.setHasFixedSize(true)
        grid.itemAnimator = null

        backButton.setOnClickListener { close() }
        filterPills.forEach { (targetFilter, pill) ->
            pill.setOnClickListener { setFilter(targetFilter) }
        }
        footerSecondary.setOnClickListener { toggleSelectAllVisible() }
        saveButton.setOnClickListener { onSaveClicked() }
        saveDoneButton.setOnClickListener { dismissSaveResult() }
        saveOpenAlbumButton.setOnClickListener { saveActions?.onOpenAlbum() }

        // Android 15 enforces edge-to-edge for targetSdk 35, so the overlay
        // must stay clear of the status bar and the gesture / button nav bar.
        panel.setOnApplyWindowInsetsListener { _, insets ->
            applySafeArea(insets)
            insets
        }
    }

    // ------------------------------------------------------------ public API

    /** Host callbacks for the save flow. Call once, right after construction. */
    fun setSaveActions(actions: SaveActions) {
        saveActions = actions
    }

    /**
     * Renders the save flow. The host calls this on the main thread for every
     * state change; the panel never advances the state on its own.
     */
    fun renderSaveState(state: SaveState) {
        val previous = saveState
        saveState = state
        renderFooter()
        if (state is SaveState.Finished && previous !is SaveState.Finished && isShowing) {
            panel.announceForAccessibility(finishedAnnouncement(state))
        }
    }

    /** Binds [post] and plays the entry transition. Safe to call repeatedly. */
    fun show(post: PostMedia) {
        this.post = post
        selectedIds.clear()
        ordinals = buildOrdinals(post)
        filter = Filter.ALL
        saveState = SaveState.Idle

        bindHeader(post)
        val hasItems = post.items.isNotEmpty()
        filterRow.visibility = if (hasItems) View.VISIBLE else View.GONE
        footer.visibility = if (hasItems) View.VISIBLE else View.GONE
        updateFilterPills()
        renderContent()
        renderFooter()

        stateToken++
        content.animate().cancel()
        if (!isShowing) {
            isShowing = true
            panel.visibility = View.VISIBLE
            panel.requestApplyInsets()
            content.alpha = 0f
            content.translationY = dp(ENTRY_OFFSET_DP).toFloat()
            content.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(ENTRY_DURATION_MS)
                .setInterpolator(DecelerateInterpolator(1.6f))
                .start()
        } else {
            content.alpha = 1f
            content.translationY = 0f
        }
    }

    /**
     * Animates out and hides the overlay. Idempotent: calling it again while
     * the exit animation runs (e.g. the host also hides on [onClose]) is a
     * no-op, so the animation is never cut short.
     */
    fun hide() {
        if (!isShowing) return
        isShowing = false
        val token = ++stateToken
        content.animate().cancel()
        content.animate()
            .alpha(0f)
            .translationY(dp(EXIT_OFFSET_DP).toFloat())
            .setDuration(EXIT_DURATION_MS)
            .setInterpolator(AccelerateInterpolator())
            .withEndAction {
                if (token != stateToken) return@withEndAction
                panel.visibility = View.GONE
                content.alpha = 1f
                content.translationY = 0f
            }
            .start()
    }

    // ---------------------------------------------------------------- header

    private fun bindHeader(post: PostMedia) {
        subtitle.text = activity.resources.getQuantityString(
            R.plurals.preview_item_count,
            post.items.size,
            post.items.size,
        )

        val name = post.author.trim()
        author.text = if (name.isBlank()) {
            activity.getString(R.string.preview_author_unknown)
        } else {
            name
        }
        avatar.text = name.firstOrNull()?.uppercaseChar()?.toString()?.ifBlank { "?" } ?: "?"

        val domain = domainOf(post.sourceUrl)
        if (domain == null) {
            source.visibility = View.GONE
        } else {
            source.text = domain
            source.visibility = View.VISIBLE
        }

        val caption = post.desc.trim()
        if (caption.isBlank()) {
            description.visibility = View.GONE
        } else {
            description.text = caption
            description.visibility = View.VISIBLE
        }
    }

    private fun domainOf(url: String): String? {
        if (url.isBlank()) return null
        val host = try {
            Uri.parse(url).host
        } catch (t: Throwable) {
            null
        }
        return host?.removePrefix("www.")?.takeIf { it.isNotBlank() }
    }

    private fun buildOrdinals(post: PostMedia): Map<String, Int> {
        val map = HashMap<String, Int>(post.items.size)
        post.items.forEachIndexed { position, item ->
            map[item.id] = if (item.index > 0) item.index else position + 1
        }
        return map
    }

    // --------------------------------------------------------------- filters

    private fun updateFilterPills() {
        val post = this.post ?: return
        val counts = mapOf(
            Filter.ALL to post.items.size,
            Filter.IMAGES to post.imageCount,
            Filter.VIDEOS to post.videoCount,
        )
        filterPills.forEach { (targetFilter, pill) ->
            val count = counts[targetFilter] ?: 0
            pill.text = when (targetFilter) {
                Filter.ALL -> activity.getString(R.string.preview_filter_all, count)
                Filter.IMAGES -> activity.getString(R.string.preview_filter_images, count)
                Filter.VIDEOS -> activity.getString(R.string.preview_filter_videos, count)
            }
            val selected = targetFilter == filter
            pill.isSelected = selected
            pill.setTextColor(if (selected) brandColor else idlePillColor)
        }
    }

    private fun setFilter(targetFilter: Filter) {
        if (targetFilter == filter) return
        filter = targetFilter
        updateFilterPills()
        renderContent()
        renderFooter()
        if (grid.visibility == View.VISIBLE) grid.scrollToPosition(0)
    }

    private fun visibleItems(): List<MediaItem> {
        val post = this.post ?: return emptyList()
        return when (filter) {
            Filter.ALL -> post.items
            Filter.IMAGES -> post.items.filter { it.kind == MediaKind.IMAGE }
            Filter.VIDEOS -> post.items.filter { it.kind == MediaKind.VIDEO }
        }
    }

    // ------------------------------------------------------------------ grid

    private fun renderContent() {
        val post = this.post ?: return
        val visible = visibleItems()
        adapter.submit(visible, selectedIds, ordinals, post.items.size)

        val isEmpty = visible.isEmpty()
        grid.visibility = if (isEmpty) View.GONE else View.VISIBLE
        emptyState.visibility = if (isEmpty) View.VISIBLE else View.GONE
        if (isEmpty) bindEmptyState(postIsEmpty = post.items.isEmpty())
    }

    private fun bindEmptyState(postIsEmpty: Boolean) {
        if (postIsEmpty) {
            renderEmpty(
                iconRes = R.drawable.ic_photo_library,
                titleRes = R.string.preview_empty_post_title,
                bodyText = activity.getString(R.string.preview_empty_post_body),
                actionText = activity.getString(R.string.preview_back),
            ) { close() }
            return
        }
        when (filter) {
            Filter.IMAGES -> renderEmpty(
                iconRes = R.drawable.ic_photo_library,
                titleRes = R.string.preview_empty_images_title,
                bodyText = activity.getString(R.string.preview_empty_filter_body),
                actionText = activity.getString(R.string.preview_empty_show_all),
            ) { showAll() }

            Filter.VIDEOS -> renderEmpty(
                iconRes = R.drawable.ic_videocam,
                titleRes = R.string.preview_empty_videos_title,
                bodyText = activity.getString(R.string.preview_empty_filter_body),
                actionText = activity.getString(R.string.preview_empty_show_all),
            ) { showAll() }

            Filter.ALL -> renderEmpty(
                iconRes = R.drawable.ic_image,
                titleRes = R.string.preview_empty_all_title,
                bodyText = activity.getString(R.string.preview_empty_filter_body),
                actionText = activity.getString(R.string.preview_empty_show_all),
            ) { showAll() }
        }
    }

    private fun renderEmpty(
        iconRes: Int,
        titleRes: Int,
        bodyText: String,
        actionText: String,
        onAction: () -> Unit,
    ) {
        emptyIcon.setImageResource(iconRes)
        emptyTitle.setText(titleRes)
        emptyBody.text = bodyText
        emptyAction.text = actionText
        emptyAction.setOnClickListener { onAction() }
    }

    private fun showAll() {
        filter = Filter.ALL
        updateFilterPills()
        renderContent()
        renderFooter()
    }

    // ------------------------------------------------------------- selection

    private fun onCardClick(item: MediaItem) {
        val nowSelected = if (selectedIds.contains(item.id)) {
            selectedIds.remove(item.id)
            false
        } else {
            selectedIds.add(item.id)
            true
        }
        adapter.updateSelection(selectedIds, animateForId = if (nowSelected) item.id else null)
        onSelectionChanged()
    }

    private fun onCardLongClick(item: MediaItem) {
        val post = this.post ?: return
        val ordinal = ordinals[item.id] ?: 0
        MediaViewerDialog(activity, item, ordinal, post.items.size).show()
    }

    private fun toggleSelectAllVisible() {
        val visible = visibleItems()
        if (visible.isEmpty()) return
        if (visible.all { it.id in selectedIds }) {
            visible.forEach { selectedIds.remove(it.id) }
        } else {
            selectedIds.addAll(visible.map { it.id })
        }
        adapter.updateSelection(selectedIds)
        onSelectionChanged()
    }

    /**
     * Changing what is selected after a run finished starts a fresh save
     * session (the result footer belongs to the previous selection).
     */
    private fun onSelectionChanged() {
        if (saveState is SaveState.Finished) {
            saveState = SaveState.Idle
        }
        renderFooter()
    }

    // -------------------------------------------------------------- save flow

    private fun onSaveClicked() {
        // Ignore taps while a run is in flight or while the result is shown.
        if (saveState !is SaveState.Idle) return
        val post = this.post ?: return
        if (selectedIds.isEmpty()) return
        val selected = post.items.filter { it.id in selectedIds }
        if (selected.isEmpty()) return
        saveActions?.onSaveRequested(selected)
    }

    private fun dismissSaveResult() {
        if (saveState is SaveState.Idle) return
        saveState = SaveState.Idle
        renderFooter()
    }

    private fun renderFooter() {
        val post = this.post ?: return
        val total = post.items.size
        val selectedCount = selectedIds.size
        val visible = visibleItems()
        val allVisibleSelected = visible.isNotEmpty() && visible.all { it.id in selectedIds }
        val state = saveState

        val idle = state is SaveState.Idle
        // Downloading to the gallery and uploading to Telegram are both busy
        // states: progress edge + status line + disabled primary.
        val busy = state is SaveState.Running || state is SaveState.Uploading
        val finished = state is SaveState.Finished

        footerCount.visibility = if (idle) View.VISIBLE else View.GONE
        saveRunningText.visibility = if (busy) View.VISIBLE else View.GONE
        saveFinishedSummary.visibility = if (finished) View.VISIBLE else View.GONE
        saveProgress.visibility = if (busy) View.VISIBLE else View.GONE
        footerFinishedActions.visibility = if (finished) View.VISIBLE else View.GONE
        footerSecondary.visibility = if (idle) View.VISIBLE else View.GONE
        saveButton.visibility = if (idle || busy) View.VISIBLE else View.GONE
        saveFinishedFailed.visibility = View.GONE
        saveOpenAlbumButton.visibility = View.GONE
        telegramUploadedText.visibility = View.GONE
        telegramUploadFailedText.visibility = View.GONE

        if (state is SaveState.Idle) {
            footerCount.text = activity.getString(R.string.preview_selected, selectedCount, total)
            footerSecondary.text = activity.getString(
                if (allVisibleSelected) {
                    R.string.preview_clear_selection
                } else {
                    R.string.preview_select_all
                },
            )
            // Nothing visible in the current filter -> nothing to select.
            footerSecondary.isEnabled = visible.isNotEmpty()
            footerSecondary.alpha = if (visible.isNotEmpty()) 1f else 0.4f
            bindPrimarySaveButton(
                enabled = selectedCount > 0,
                label = activity.getString(R.string.preview_save_button, selectedCount),
            )
        }

        if (state is SaveState.Running) {
            saveProgress.progress = (state.progress * 100f).roundToInt().coerceIn(0, 100)
            saveRunningText.text = activity.getString(
                R.string.preview_saving,
                state.finished,
                state.total,
            )
            bindPrimarySaveButton(
                enabled = false,
                label = activity.getString(R.string.preview_saving_short),
            )
        }

        if (state is SaveState.Uploading) {
            saveProgress.progress = (state.progress * 100f).roundToInt().coerceIn(0, 100)
            saveRunningText.text = activity.getString(
                R.string.preview_uploading,
                state.finished,
                state.total,
            )
            bindPrimarySaveButton(
                enabled = false,
                label = activity.getString(R.string.preview_uploading_short),
            )
        }

        if (state is SaveState.Finished) {
            val album = state.albumName.ifBlank { activity.getString(R.string.app_name) }
            saveFinishedSummary.text = activity.resources.getQuantityString(
                R.plurals.preview_saved_summary,
                state.saved,
                state.saved,
                album,
            )
            if (state.failed > 0) {
                saveFinishedFailed.visibility = View.VISIBLE
                saveFinishedFailed.text = activity.resources.getQuantityString(
                    R.plurals.preview_save_failed,
                    state.failed,
                    state.failed,
                )
            }
            if (state.uploaded > 0) {
                telegramUploadedText.visibility = View.VISIBLE
                telegramUploadedText.text = activity.resources.getQuantityString(
                    R.plurals.preview_uploaded,
                    state.uploaded,
                    state.uploaded,
                )
            }
            if (state.uploadFailed > 0) {
                telegramUploadFailedText.visibility = View.VISIBLE
                telegramUploadFailedText.text = activity.getString(
                    R.string.preview_upload_failed,
                    state.uploadFailed,
                )
            }
            if (!state.firstUri.isNullOrBlank()) {
                saveOpenAlbumButton.visibility = View.VISIBLE
            }
        }
    }

    private fun bindPrimarySaveButton(enabled: Boolean, label: String) {
        saveButton.isEnabled = enabled
        saveButton.text = label
        saveButton.setTextColor(
            if (enabled) {
                Color.WHITE
            } else {
                activity.getColor(R.color.preview_placeholder_icon)
            },
        )
    }

    private fun finishedAnnouncement(state: SaveState.Finished): String {
        val album = state.albumName.ifBlank { activity.getString(R.string.app_name) }
        val separator = activity.getString(R.string.preview_announce_separator)
        val parts = ArrayList<String>(3)
        parts += activity.resources.getQuantityString(
            R.plurals.preview_saved_summary,
            state.saved,
            state.saved,
            album,
        )
        if (state.failed > 0) {
            parts += activity.resources.getQuantityString(
                R.plurals.preview_save_failed,
                state.failed,
                state.failed,
            )
        }
        if (state.uploaded > 0) {
            parts += activity.resources.getQuantityString(
                R.plurals.preview_uploaded,
                state.uploaded,
                state.uploaded,
            )
        }
        if (state.uploadFailed > 0) {
            parts += activity.getString(R.string.preview_upload_failed, state.uploadFailed)
        }
        return parts.joinToString(separator)
    }

    // --------------------------------------------------------------- helpers

    private fun close() {
        onClose()
        hide()
    }

    private fun applySafeArea(insets: WindowInsets) {
        @Suppress("DEPRECATION")
        val insetTop = insets.systemWindowInsetTop
        @Suppress("DEPRECATION")
        val insetBottom = insets.systemWindowInsetBottom

        val wantedTop = topBarBasePadding[1] + insetTop
        if (topBar.paddingTop != wantedTop) {
            topBar.setPadding(
                topBarBasePadding[0],
                wantedTop,
                topBarBasePadding[2],
                topBarBasePadding[3],
            )
        }
        val wantedBottom = footerBodyBasePadding[3] + insetBottom
        if (footerBody.paddingBottom != wantedBottom) {
            footerBody.setPadding(
                footerBodyBasePadding[0],
                footerBodyBasePadding[1],
                footerBodyBasePadding[2],
                wantedBottom,
            )
        }
    }

    private fun dp(value: Int): Int =
        (value * activity.resources.displayMetrics.density).roundToInt()

    private companion object {
        private const val GRID_COLUMNS = 3
        private const val ENTRY_DURATION_MS = 210L
        private const val EXIT_DURATION_MS = 160L
        private const val ENTRY_OFFSET_DP = 24
        private const val EXIT_OFFSET_DP = 16
    }
}
