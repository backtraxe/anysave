package io.github.backtraxe.anysave.log

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LogRingTest {

    @Test
    fun `keeps insertion order`() {
        val ring = LogRing(3)
        ring.add("a")
        ring.add("b")
        assertEquals(listOf("a", "b"), ring.snapshot())
    }

    @Test
    fun `evicts oldest lines beyond the limit`() {
        val ring = LogRing(3)
        (1..5).forEach { ring.add("line$it") }
        assertEquals(listOf("line3", "line4", "line5"), ring.snapshot())
        assertEquals(3, ring.size())
    }

    @Test
    fun `clear empties the buffer`() {
        val ring = LogRing(10)
        ring.add("x")
        ring.clear()
        assertTrue(ring.snapshot().isEmpty())
    }
}
