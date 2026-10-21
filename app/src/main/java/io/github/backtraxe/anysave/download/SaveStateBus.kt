package io.github.backtraxe.anysave.download

import android.os.Handler
import android.os.Looper
import io.github.backtraxe.anysave.model.SaveState
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Process-wide save state, delivered on the main thread.
 *
 * [SaveService] owns the writes; the activity subscribes here to keep the
 * preview panel in sync even if it was recreated while a save was running.
 */
object SaveStateBus {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArrayList<(SaveState) -> Unit>()

    @Volatile
    private var current: SaveState = SaveState.Idle

    fun state(): SaveState = current

    fun publish(state: SaveState) {
        current = state
        mainHandler.post {
            listeners.toList().forEach { listener ->
                runCatching { listener(state) }
            }
        }
    }

    /** Immediately replays the current state to the new listener (on main thread). */
    fun addListener(listener: (SaveState) -> Unit) {
        listeners.add(listener)
        val snapshot = current
        mainHandler.post {
            if (listeners.contains(listener)) {
                runCatching { listener(snapshot) }
            }
        }
    }

    fun removeListener(listener: (SaveState) -> Unit) {
        listeners.remove(listener)
    }

    /** Only used by tests / app teardown paths that need a clean slate. */
    fun reset() {
        publish(SaveState.Idle)
    }
}
