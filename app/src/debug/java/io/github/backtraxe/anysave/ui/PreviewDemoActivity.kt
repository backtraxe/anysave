package io.github.backtraxe.anysave.ui

import android.app.Activity
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import android.widget.FrameLayout
import io.github.backtraxe.anysave.R
import io.github.backtraxe.anysave.model.MediaItem
import io.github.backtraxe.anysave.model.MediaKind
import io.github.backtraxe.anysave.model.PostMedia
import io.github.backtraxe.anysave.model.SaveState

/**
 * DEBUG-ONLY visual harness for [PreviewPanel] (debug source set, never shipped).
 *
 * Launch with:
 *   adb shell am start -n io.github.backtraxe.anysave/.ui.PreviewDemoActivity
 *   adb shell am start -n io.github.backtraxe.anysave/.ui.PreviewDemoActivity --es state empty
 *   adb shell am start -n io.github.backtraxe.anysave/.ui.PreviewDemoActivity --es state images-only
 *   adb shell am start -n io.github.backtraxe.anysave/.ui.PreviewDemoActivity --es state videos-only
 *   adb shell am start -n io.github.backtraxe.anysave/.ui.PreviewDemoActivity --es state many
 *
 * Save-flow states (orthogonal to the post state above):
 *   adb shell am start -n io.github.backtraxe.anysave/.ui.PreviewDemoActivity --es save idle
 *   adb shell am start -n io.github.backtraxe.anysave/.ui.PreviewDemoActivity --es save running
 *   adb shell am start -n io.github.backtraxe.anysave/.ui.PreviewDemoActivity --es save finished
 *   adb shell am start -n io.github.backtraxe.anysave/.ui.PreviewDemoActivity --es save failed
 */
class PreviewDemoActivity : Activity() {

    private var panel: PreviewPanel? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val root = findViewById<FrameLayout>(R.id.previewRoot)
        val post = demoPost(intent?.getStringExtra(EXTRA_STATE))
        panel = PreviewPanel(this, root) { finish() }.also { preview ->
            preview.setSaveActions(
                object : PreviewPanel.SaveActions {
                    override fun onSaveRequested(items: List<MediaItem>) {
                        Log.i(TAG, "demo onSaveRequested ids=" + items.joinToString(",") { it.id + "#" + it.index })
                    }

                    override fun onOpenAlbum() {
                        Log.i(TAG, "demo onOpenAlbum")
                    }
                },
            )
            preview.show(post)
            preview.renderSaveState(demoSaveState(intent?.getStringExtra(EXTRA_SAVE), post))
        }
    }

    private fun demoSaveState(save: String?, post: PostMedia): SaveState {
        val total = post.items.size.coerceAtLeast(1)
        return when (save) {
            SAVE_RUNNING -> SaveState.Running(total = total, done = 7, failed = 1)
            SAVE_FINISHED -> SaveState.Finished(
                total = total,
                saved = total,
                failed = 0,
                albumName = "AnySave",
                firstUri = "content://media/external/images/media/42",
            )

            SAVE_FAILED -> SaveState.Finished(
                total = total,
                saved = (total - 2).coerceAtLeast(0),
                failed = 2,
                albumName = "AnySave",
                firstUri = "content://media/external/images/media/42",
            )

            else -> SaveState.Idle
        }
    }

    private fun demoPost(state: String?): PostMedia {
        val all = demoItems()
        val items = when (state) {
            STATE_EMPTY -> emptyList()
            STATE_IMAGES_ONLY -> all.filter { it.kind == MediaKind.IMAGE }
            STATE_VIDEOS_ONLY -> all.filter { it.kind == MediaKind.VIDEO }
            STATE_MANY -> manyItems()
            else -> all
        }
        return PostMedia(
            awemeId = "7412345678901234567",
            author = "晚风收集者",
            desc = "洱海边的日落只有十分钟，风把云吹成了橘色。这一段原片都放在这里了，" +
                "喜欢哪一张记得告诉我，也可以直接全部保存。#旅行 #日落 #胶片感",
            sourceUrl = "https://www.douyin.com/video/7412345678901234567",
            items = items,
        )
    }

    private fun demoItems(): List<MediaItem> {
        val items = ArrayList<MediaItem>()

        fun add(
            kind: MediaKind,
            previewUrl: String,
            width: Int = 600,
            height: Int = 800,
            durationMs: Long = 0L,
        ) {
            val ordinal = items.size + 1
            items += MediaItem(
                id = "demo-$ordinal",
                kind = kind,
                url = if (kind == MediaKind.VIDEO) {
                    "https://v3-web.douyinvod.com/demo-$ordinal.mp4"
                } else {
                    previewUrl
                },
                previewUrl = previewUrl,
                width = width,
                height = height,
                durationMs = durationMs,
                index = ordinal,
            )
        }

        fun photo(seed: String, width: Int = 600, height: Int = 800) {
            add(MediaKind.IMAGE, "https://picsum.photos/seed/$seed/$width/$height", width, height)
        }

        photo("anysave-01")
        photo("anysave-02")
        add(MediaKind.VIDEO, "https://picsum.photos/seed/anysave-03/600/800", durationMs = 15_000L)
        photo("anysave-04")
        photo("anysave-05")
        add(MediaKind.VIDEO, previewUrl = "", durationMs = 63_000L)
        // Protocol-relative URL: ThumbnailLoader must upgrade it to https.
        add(MediaKind.IMAGE, "//picsum.photos/seed/anysave-07/600/800")
        // Unresolvable host: card must fall back to a placeholder, never crash.
        add(MediaKind.IMAGE, "https://images.invalid.invalid/missing.jpg")
        photo("anysave-09", width = 800, height = 600)
        add(MediaKind.VIDEO, "https://picsum.photos/seed/anysave-10/600/800", durationMs = 3_723_000L)
        photo("anysave-11", width = 720, height = 900)
        photo("anysave-12")
        photo("anysave-13")
        photo("anysave-14")
        return items
    }

    /** 40 cards: scroll + request-cancellation stress state. */
    private fun manyItems(): List<MediaItem> = (1..40).map { ordinal ->
        val isVideo = ordinal % 7 == 0
        val cover = "https://picsum.photos/seed/anysave-many-$ordinal/600/800"
        MediaItem(
            id = "demo-many-$ordinal",
            kind = if (isVideo) MediaKind.VIDEO else MediaKind.IMAGE,
            url = if (isVideo) "https://v3-web.douyinvod.com/demo-many-$ordinal.mp4" else cover,
            previewUrl = if (isVideo && ordinal % 11 == 0) "" else cover,
            width = 600,
            height = 800,
            durationMs = if (isVideo) 20_000L + ordinal * 1_300L else 0L,
            index = ordinal,
        )
    }

    private companion object {
        private const val TAG = "PreviewDemo"
        private const val EXTRA_STATE = "state"
        private const val EXTRA_SAVE = "save"
        private const val SAVE_RUNNING = "running"
        private const val SAVE_FINISHED = "finished"
        private const val SAVE_FAILED = "failed"
        private const val STATE_EMPTY = "empty"
        private const val STATE_IMAGES_ONLY = "images-only"
        private const val STATE_VIDEOS_ONLY = "videos-only"
        private const val STATE_MANY = "many"
    }
}
