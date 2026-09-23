package dev.qlipbod.sync.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The daemon's outbound sequence source must be strictly monotonic, never wall-clock (§7). */
class SystemMonotonicClockTest {

    @Test
    fun `sequences increase by one for every call`() {
        val clock = SystemMonotonicClock()
        val values = (0 until 1000).map { clock.next() }
        assertEquals(1L, values.first())
        assertEquals(values.size, values.toSet().size, "no repeats")
        assertEquals(setOf(1L), values.zipWithNext().map { (a, b) -> b - a }.toSet(), "increments of exactly one")
        assertTrue(values.sorted() == values, "strictly increasing")
    }

    @Test
    fun `two clocks are independent`() {
        val a = SystemMonotonicClock()
        val b = SystemMonotonicClock()
        assertEquals(1L, a.next())
        assertEquals(1L, b.next(), "the other clock must not advance this one")
        assertEquals(2L, a.next())
        assertEquals(2L, b.next())
    }

    @Test
    fun `a pre-seeded clock continues from the seed`() {
        val clock = SystemMonotonicClock(java.util.concurrent.atomic.AtomicLong(41L))
        assertEquals(41L, clock.next())
        assertEquals(42L, clock.next())
        assertTrue(clock.next() > 42L)
    }
}