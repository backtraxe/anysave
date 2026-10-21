package io.github.backtraxe.anysave.ui

import android.content.Context
import android.util.AttributeSet
import android.widget.FrameLayout

/**
 * A [FrameLayout] that keeps every card on the 3:4 grid rhythm regardless of
 * the source image aspect ratio (covers are center-cropped).
 */
class AspectRatioFrameLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr) {

    /** width / height. Douyin images are usually 3:4. */
    var aspectRatio: Float = DEFAULT_ASPECT_RATIO

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        if (width > 0 && aspectRatio > 0f) {
            val height = (width / aspectRatio).toInt()
            super.onMeasure(
                MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY),
            )
        } else {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        }
    }

    private companion object {
        private const val DEFAULT_ASPECT_RATIO = 0.75f
    }
}
