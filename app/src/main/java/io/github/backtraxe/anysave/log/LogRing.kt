package io.github.backtraxe.anysave.log

/**
 * Bounded in-memory log history shown by the in-app diagnostics screen.
 *
 * Pure JVM so the eviction rules are unit tested; [AppLog] is the Android
 * facade that also writes to logcat.
 */
class LogRing(private val maxLines: Int) {

    private val lines = ArrayDeque<String>()

    init {
        require(maxLines > 0) { "maxLines must be positive" }
    }

    @Synchronized
    fun add(line: String) {
        lines.addLast(line)
        while (lines.size > maxLines) lines.removeFirst()
    }

    @Synchronized
    fun snapshot(): List<String> = lines.toList()

    @Synchronized
    fun clear() = lines.clear()

    @Synchronized
    fun size(): Int = lines.size
}
