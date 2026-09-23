package dev.qlipbod.sync.engine

import java.util.concurrent.atomic.AtomicLong

/**
 * Production monotonic clock for the daemon: a per-process counter, never wall time —
 * sequence ordering must not depend on the clock (plan §7). A future slice can re-base
 * the counter from persisted history so sequences continue cleanly across restarts.
 */
class SystemMonotonicClock(private val nextValue: AtomicLong = AtomicLong(1L)) : MonotonicClock {
    override fun next(): Long = nextValue.getAndIncrement()
}