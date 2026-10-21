package io.github.backtraxe.anysave.ui

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.ImageView
import android.widget.TextView
import io.github.backtraxe.anysave.R
import io.github.backtraxe.anysave.model.MediaItem
import io.github.backtraxe.anysave.model.MediaKind

/**
 * Full-screen long-press preview: dark scrim, fit-center artwork, tap or back
 * to dismiss. Videos show their cover here; M1 does not play video playback.
 */
internal class MediaViewerDialog(
    activity: Activity,
    private val item: MediaItem,
    private val ordinal: Int,
    private val total: Int,
) : Dialog(activity, android.R.style.Theme_Black_NoTitleBar_Fullscreen) {

    private val root: View =
        LayoutInflater.from(activity).inflate(R.layout.preview_viewer, null)
    private val image: ImageView = root.findViewById(R.id.viewerImage)
    private val messageGroup: View = root.findViewById(R.id.viewerMessage)
    private val messageIcon: ImageView = root.findViewById(R.id.viewerMessageIcon)
    private val messageText: TextView = root.findViewById(R.id.viewerMessageText)

    init {
        setContentView(root)
        window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            // Keep the dark scrim edge-to-edge: white system chrome around a
            // full-screen image reads like a bug.
            statusBarColor = Color.BLACK
            navigationBarColor = Color.BLACK
            isNavigationBarContrastEnforced = false
            @Suppress("DEPRECATION")
            decorView.systemUiVisibility = decorView.systemUiVisibility and
                (View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or
                    View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR).inv()
        }
        root.alpha = 0f
        root.setOnClickListener { dismiss() }
        root.findViewById<View>(R.id.viewerClose).setOnClickListener { dismiss() }
        root.findViewById<TextView>(R.id.viewerCounter).text =
            activity.getString(R.string.preview_viewer_counter, ordinal, total)
        bindMedia()
    }

    override fun onStart() {
        super.onStart()
        window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        root.animate()
            .alpha(1f)
            .setDuration(FADE_IN_MS)
            .setInterpolator(DecelerateInterpolator())
            .start()
    }

    private fun bindMedia() {
        val context = context
        val isVideo = item.kind == MediaKind.VIDEO
        val url = when {
            item.previewUrl.isNotBlank() -> item.previewUrl
            !isVideo -> item.url
            else -> ""
        }
        image.contentDescription = describe()

        if (url.isBlank()) {
            showMessage(
                context.getString(
                    if (isVideo) {
                        R.string.preview_viewer_video_cover_missing
                    } else {
                        R.string.preview_image_unavailable
                    },
                ),
                R.drawable.ic_broken_image,
            )
            return
        }

        val metrics = context.resources.displayMetrics
        ThumbnailLoader.load(url, metrics.widthPixels, metrics.heightPixels) { bitmap ->
            if (!isShowing) return@load
            if (bitmap != null) {
                image.setImageBitmap(bitmap)
                image.visibility = View.VISIBLE
                messageGroup.visibility = View.GONE
            } else {
                showMessage(
                    context.getString(R.string.preview_viewer_load_failed),
                    R.drawable.ic_broken_image,
                )
            }
        }
    }

    private fun showMessage(text: String, iconRes: Int) {
        messageGroup.visibility = View.VISIBLE
        messageIcon.setImageResource(iconRes)
        messageText.text = text
    }

    private fun describe(): String {
        val context = context
        return when {
            item.kind == MediaKind.VIDEO && item.durationMs > 0L -> context.getString(
                R.string.preview_item_description_video,
                ordinal,
                total,
                formatDurationMs(item.durationMs),
            )

            item.kind == MediaKind.VIDEO -> context.getString(
                R.string.preview_item_description_video_no_duration,
                ordinal,
                total,
            )

            else -> context.getString(R.string.preview_item_description_image, ordinal, total)
        }
    }

    private companion object {
        private const val FADE_IN_MS = 160L
    }
}
