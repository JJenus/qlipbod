package dev.qlipbod.sync.engine

import dev.qlipbod.sync.history.ClipHistory
import dev.qlipbod.sync.identity.LocalIdentity
import dev.qlipbod.sync.protocol.SyncEvent
import dev.qlipbod.sync.trust.TrustedPeer
import dev.qlipbod.sync.trust.TrustStore

/**
 * Platform-independent sync logic: pairing trust, event origin, loop prevention,
 * conflict resolution (plan §7, §9, §10).
 *
 * Invariants:
 * - Only locally originated clips are broadcast; received clips are never rebroadcast.
 * - A returning event whose [SyncEvent.origin] is us is an echo: ignored.
 * - Sensitive clips never leave the device.
 * - Conflicts resolve by total order on (origin, sequence); the loser stays in history.
 * - Untrusted peers are refused before any state changes.
 *
 * The host drives this class with platform events (clipboard change, peer message);
 * transport, discovery, and clipboard hooks live outside it so the exact same logic
 * runs on Linux and Android.
 */
class SyncEngine(
    val identity: LocalIdentity,
    private val trustStore: TrustStore,
    private val clock: MonotonicClock,
    val history: ClipHistory,
    private val eventSink: EventSink,
    private val listener: EngineListener? = null,
) {

    /** Canonical winner of all seen clips under the (origin, sequence) total order. */
    var appliedWinner: SyncEvent? = null
        private set

    /**
     * Local clipboard changed on this device.
     *
     * @param fromNetwork true when the host is applying a clip the engine itself delivered;
     *   such events must never be rebroadcast (loop prevention, plan §7).
     * @param sensitive true for password-manager/OTP content: excluded from sync by default.
     */
    fun onLocalClipChanged(content: String, sensitive: Boolean = false, fromNetwork: Boolean = false) {
        if (fromNetwork) return // already recorded when it arrived from the peer
        if (sensitive) return   // sync policy: sensitive never leaves the device (§10.4)

        val event = SyncEvent(origin = identity.deviceId, sequence = clock.next(), payload = content)
        history.record(event)
        appliedWinner = maxByTotalOrder(event, appliedWinner)
        listener?.onBroadcast(event)
        eventSink.send(event)
    }

    /** A framed [event] arrived over a connection authenticated as [peer]. */
    fun onPeerMessage(peer: TrustedPeer, event: SyncEvent) {
        if (!trustStore.isTrusted(peer.fingerprint)) {
            listener?.onRejected(peer, "untrusted fingerprint ${peer.fingerprint.hex.take(12)}…")
            return
        }
        if (event.origin == identity.deviceId) return // our own clip bounced back: ignore
        if (history.contains(event.origin, event.sequence)) return // already known: dedupe

        history.record(event)
        if (appliedWinner == null || event.totalOrderKey() > appliedWinner!!.totalOrderKey()) {
            appliedWinner = event
            listener?.onClipApplied(event)
        }
    }

    private fun maxByTotalOrder(a: SyncEvent, b: SyncEvent?): SyncEvent =
        if (b == null || a.totalOrderKey() > b.totalOrderKey()) a else b
}