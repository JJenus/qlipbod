package dev.qlipbod.sync.engine

import dev.qlipbod.sync.DeferredSink
import dev.qlipbod.sync.InMemoryHistoryStorage
import dev.qlipbod.sync.InMemoryTrustStorage
import dev.qlipbod.sync.RecordingListener
import dev.qlipbod.sync.TestIdentity
import dev.qlipbod.sync.crypto.Fingerprint
import dev.qlipbod.sync.crypto.Sha256
import dev.qlipbod.sync.engine.InMemoryMonotonicClock
import dev.qlipbod.sync.history.ClipHistory
import dev.qlipbod.sync.identity.DeviceId
import dev.qlipbod.sync.protocol.SyncEvent
import dev.qlipbod.sync.trust.TrustedPeer
import dev.qlipbod.sync.trust.TrustStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Behavior contract for the sync engine (plan §7, §9, §10):
 * - monotonic per-device sequences (never wall clock)
 * - sensitive clips never leave the device
 * - loop prevention: origin tagging + from-network flag, no bouncing
 * - deterministic conflict resolution ordered by (origin, sequence); loser kept in history
 * - untrusted peers rejected at the message layer
 */
class EngineTest {

    private val trustStorage = InMemoryTrustStorage()
    private val trustStore = TrustStore(trustStorage)

    private class Rig(
        val identity: TestIdentity,
        val engine: SyncEngine,
        val listener: RecordingListener,
        val sink: DeferredSink,
    )

    private fun rig(name: String): Rig {
        val identity = TestIdentity(DeviceId(name))
        val listener = RecordingListener()
        val sink = DeferredSink()
        val engine = SyncEngine(
            identity = identity,
            trustStore = trustStore,
            clock = InMemoryMonotonicClock(),
            history = ClipHistory(capacity = 50, storage = InMemoryHistoryStorage()),
            eventSink = sink,
            listener = listener,
        )
        return Rig(identity, engine, listener, sink)
    }

    /** Trusts both fingerprints and wires the two engines together end to end. */
    private fun link(a: Rig, b: Rig) {
        trustStore.add(a.identity.fingerprint, "device-${a.identity.deviceId.value}")
        trustStore.add(b.identity.fingerprint, "device-${b.identity.deviceId.value}")
        a.sink.trustedAs = TrustedPeer(a.identity.fingerprint, a.identity.deviceId.value, addedAtEpochMillis = 0)
        b.sink.trustedAs = TrustedPeer(b.identity.fingerprint, b.identity.deviceId.value, addedAtEpochMillis = 0)
        a.sink.target = b.engine
        b.sink.target = a.engine
    }

    @Test
    fun `local copies get monotonic sequences`() {
        val rig = rig("alpha")
        rig.engine.onLocalClipChanged("one")
        rig.engine.onLocalClipChanged("two")

        assertEquals(listOf("one", "two"), rig.listener.broadcasted.map { it.payload })
        assertEquals(listOf(1L, 2L), rig.listener.broadcasted.map { it.sequence })
        assertEquals(DeviceId("alpha"), rig.listener.broadcasted[0].origin)
    }

    @Test
    fun `sensitive clip is never broadcast or stored`() {
        val rig = rig("alpha")
        rig.engine.onLocalClipChanged("hunter2", sensitive = true)
        assertEquals(emptyList(), rig.sink.sent)
        assertEquals(emptyList(), rig.listener.broadcasted)
        assertEquals(0, rig.engine.history.size())
    }

    @Test
    fun `clip received from network is applied but not rebroadcast`() {
        val a = rig("alpha")
        val b = rig("beta")
        link(a, b)

        a.engine.onLocalClipChanged("from alpha")

        assertEquals(1, b.listener.applied.size, "B applies A's clip")
        assertEquals("from alpha", b.listener.applied[0].payload)
        assertEquals(1, a.sink.sent.size, "A sent exactly its own clip")
        assertEquals(emptyList(), b.sink.sent, "B must not echo A's clip back")
    }

    @Test
    fun `host re-applying a network clip fromNetwork is ignored`() {
        val a = rig("alpha")
        val b = rig("beta")
        link(a, b)

        a.engine.onLocalClipChanged("hello")
        assertEquals(1, b.listener.applied.size)

        // B's clipboard hook fires after the engine wrote the network clip back to the clipboard.
        b.engine.onLocalClipChanged("hello", fromNetwork = true)

        assertEquals(emptyList(), b.sink.sent, "network-applied clip must never be rebroadcast")
        assertEquals(1, b.listener.applied.size, "must not re-apply its own applied clip")
        assertEquals(1, a.sink.sent.size, "A must not have received any bounce")
    }

    @Test
    fun `our own clip returning from a peer is ignored as an echo`() {
        val a = rig("alpha")
        val b = rig("beta")
        link(a, b)

        a.engine.onPeerMessage(peer = trustStore.all()[0], event = SyncEvent(origin = a.identity.deviceId, sequence = 99, payload = "my own clip"))

        assertEquals(emptyList(), a.listener.applied, "echo must not be re-applied")
        assertEquals(emptyList(), a.sink.sent, "echo must not be rebroadcast")
    }

    @Test
    fun `untrusted peer is rejected and nothing is applied`() {
        val a = rig("alpha")
        val strangerFp = Fingerprint.ofDigest(Sha256.digest("stranger".encodeToByteArray()))
        val untrusted = TrustedPeer(strangerFp, "stranger", addedAtEpochMillis = 0)

        a.engine.onPeerMessage(peer = untrusted, event = SyncEvent(origin = DeviceId("stranger"), sequence = 1, payload = "pwn"))

        assertEquals(1, a.listener.rejected.size)
        assertEquals(emptyList(), a.listener.applied)
        assertEquals(emptyList(), a.sink.sent)
    }

    @Test
    fun `conflicts resolve deterministically and converge on both devices`() {
        val a = rig("alpha")
        val b = rig("beta")
        link(a, b)

        // Near-simultaneous copies on both devices.
        a.engine.onLocalClipChanged("aaa")   // (alpha, 1)
        b.engine.onLocalClipChanged("bbb")   // (beta, 1)

        // Total order (origin string, then sequence): "beta" > "alpha", so beta's clip wins on both.
        assertEquals("bbb", a.engine.appliedWinner?.payload, "A converges on beta's clip as canonical winner")
        assertEquals("bbb", b.engine.appliedWinner?.payload, "B keeps its own clip as canonical winner")

        // The loser remains in both histories so nothing is silently lost (plan §7).
        assertTrue(a.engine.history.contains(DeviceId("beta"), 1))
        assertTrue(b.engine.history.contains(DeviceId("beta"), 1))
        assertTrue(b.engine.history.contains(DeviceId("alpha"), 1))
    }

    @Test
    fun `stale event from a known origin does not overwrite the newer winner`() {
        val a = rig("alpha")
        val b = rig("beta")
        link(a, b)

        a.engine.onLocalClipChanged("newer")  // (alpha, 1)
        a.engine.onLocalClipChanged("older")  // (alpha, 2)
        assertEquals(2, b.listener.applied.size)

        // Deliver a stale event with a lower sequence for the same origin.
        b.engine.onPeerMessage(
            peer = trustStore.all()[0],
            event = SyncEvent(origin = a.identity.deviceId, sequence = 1, payload = "newer"),
        )

        assertEquals(2, b.listener.applied.size, "stale event must not re-apply")
        assertTrue(b.engine.history.contains(DeviceId("alpha"), 1), "stale event still recorded in history")
    }

    @Test
    fun `bidirectional sync end to end`() {
        val a = rig("alpha")
        val b = rig("beta")
        link(a, b)

        a.engine.onLocalClipChanged("from laptop")
        b.engine.onLocalClipChanged("from phone")

        assertEquals("from phone", a.listener.applied.last().payload, "A receives B's clip")
        assertEquals("from laptop", b.listener.applied.last().payload, "B receives A's clip")
        assertEquals(1, a.sink.sent.size, "each device sent exactly its own clip: no loops")
        assertEquals(1, b.sink.sent.size)
    }
}