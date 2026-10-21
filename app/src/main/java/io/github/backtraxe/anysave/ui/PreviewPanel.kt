package io.github.backtraxe.anysave.ui

import android.app.Activity
import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.view.WindowInsets
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import io.github.backtraxe.anysave.R
import io.github.backtraxe.anysave.model.MediaItem
import io.github.backtraxe.anysave.model.MediaKind
import io.github.backtraxe.anysave.model.PostMedia
import kotlin.math.roundToInt

/**
 * M1 preview overlay: post header, media grid with thumbnails, filtering and
 * multi-select. The panel inflates itself into [root] (the full-screen
 * `previewRoot` FrameLayout owned by the activity) and stays hidden until
 * [show] is called.
 *
 * The panel is the single owner of selection state so switching filters never
 * loses what the user picked. Selection is reported to the host only through
 * the UI for now; M2 will read it to start downloads.
 */
class PreviewPanel(
    private val activity: Activity,
    private val root: FrameLayout,
    private val onClose: () -> Unit,
) {

    private enum class Filter { ALL, IMAGES, VIDEOS }

    private val panel: View =
        LayoutInflater.from(activity).inflate(R.layout.preview_panel, root, false)
    private val content: View = panel.findViewById(R.id.previewPanelContent)
    private val topBar: View = panel.findViewById(R.id.panelTopBar)
    private val footerRow: View = panel.findViewById(R.id.footerRow)
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
    private val footerAction: TextView = panel.findViewById(R.id.footerAction)

    private val adapter = MediaGridAdapter(::onCardClick, ::onCardLongClick)
    private val selectedIds = LinkedHashSet<String>()

    private var post: PostMedia? = null
    private var filter = Filter.ALL
    private var ordinals: Map<String, Int> = emptyMap()

    /** Guards against an exit animation ending after a new [show] started. */
    private var stateToken = 0

    // Captured so window insets can be added without losing the XML padding.
    private val topBarBasePadding = intArrayOf(
        topBar.paddingLeft,
        topBar.paddingTop,
        topBar.paddingRight,
        topBar.paddingBottom,
    )
    private val footerRowBasePadding = intArrayOf(
        footerRow.paddingLeft,
        footerRow.paddingTop,
        footerRow.paddingRight,
        footerRow.paddingBottom,
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
        footerAction.setOnClickListener { toggleSelectAllVisible() }

        // Android 15 enforces edge-to-edge for targetSdk 35, so the overlay
        // must stay clear of the status bar and the gesture / button nav bar.
        panel.setOnApplyWindowInsetsListener { _, insets ->
            applySafeArea(insets)
            insets
        }
    }

    /** Binds [post] and plays the entry transition. Safe to call repeatedly. */
    fun show(post: PostMedia) {
        this.post = post
        selectedIds.clear()
        ordinals = buildOrdinals(post)
        filter = Filter.ALL

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
        renderFooter()
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
        renderFooter()
    }

    private fun renderFooter() {
        val post = this.post ?: return
        footerCount.text = activity.getString(
            R.string.preview_selected,
            selectedIds.size,
            post.items.size,
        )
        val visible = visibleItems()
        val allVisibleSelected = visible.isNotEmpty() && visible.all { it.id in selectedIds }
        footerAction.setText(
            if (allVisibleSelected) R.string.preview_clear_selection else R.string.preview_select_all,
        )
        // Nothing visible in the current filter -> nothing to select.
        footerAction.isEnabled = visible.isNotEmpty()
        footerAction.alpha = if (visible.isNotEmpty()) 1f else 0.4f
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
        val wantedBottom = footerRowBasePadding[3] + insetBottom
        if (footerRow.paddingBottom != wantedBottom) {
            footerRow.setPadding(
                footerRowBasePadding[0],
                footerRowBasePadding[1],
                footerRowBasePadding[2],
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
