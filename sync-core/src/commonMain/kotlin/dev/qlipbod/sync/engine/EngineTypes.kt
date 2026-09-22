package dev.qlipbod.sync.engine

import dev.qlipbod.sync.protocol.SyncEvent

/**
 * Per-device monotonic sequence source — the logical clock of the sync protocol.
 * Never wall clock: clock drift between a phone and a laptop must not misorder
 * near-simultaneous copies (plan §7).
 */
fun interface MonotonicClock {
    fun next(): Long
}

/** Simple in-memory counter; persists via the host if sequences must survive restarts. */
class InMemoryMonotonicClock(private var nextValue: Long = 1L) : MonotonicClock {
    override fun next(): Long = nextValue++
}

/** Outbound path for locally originated events; wired to the transport by the host. */
fun interface EventSink {
    fun send(event: SyncEvent)
}

/** Callbacks a host (daemon/UI) implements to observe engine decisions. */
interface EngineListener {
    /** A network clip should be written to the local clipboard. */
    fun onClipApplied(event: SyncEvent) {}

    /** A local clip was turned into an event and handed to the transport. */
    fun onBroadcast(event: SyncEvent) {}

    /** A peer message was refused (e.g. untrusted fingerprint). */
    fun onRejected(peer: dev.qlipbod.sync.trust.TrustedPeer, reason: String) {}
}