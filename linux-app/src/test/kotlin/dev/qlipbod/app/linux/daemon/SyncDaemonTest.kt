package dev.qlipbod.app.linux.daemon

import dev.qlipbod.app.linux.clipboard.ClipboardAdapter
import dev.qlipbod.sync.crypto.Fingerprint
import dev.qlipbod.sync.crypto.Sha256
import dev.qlipbod.sync.engine.EventSink
import dev.qlipbod.sync.engine.InMemoryMonotonicClock
import dev.qlipbod.sync.history.ClipHistory
import dev.qlipbod.sync.history.HistoryStorage
import dev.qlipbod.sync.identity.DeviceId
import dev.qlipbod.sync.identity.LocalIdentity
import dev.qlipbod.sync.protocol.SyncEvent
import dev.qlipbod.sync.trust.TrustStorage
import dev.qlipbod.sync.trust.TrustStore
import dev.qlipbod.sync.trust.TrustedPeer
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Daemon clipboard-plane contract (plan §8, §9):
 * - a new user copy surfaced by [SyncDaemon.poll] reaches the engine exactly once
 * - boot-time clipboard content is never re-synced
 * - a network-applied clip is written to the clipboard, and the resulting
 *   OS-level clipboard echo is suppressed (never rebroadcast)
 * - clipboard read failures degrade to "no change" instead of crashing the daemon
 */
class SyncDaemonTest {

    private class FakeClipboard(var value: String? = null) : ClipboardAdapter {
        var writes = 0
            private set
        override fun read(): String? = value
        override fun write(text: String) {
            writes++
            value = text
        }
    }

    private class ThrowingClipboard : ClipboardAdapter {
        override fun read(): String? = throw IOException("no X server")
        override fun write(text: String) = throw IOException("no X server")
    }

    private class RecordingSink : EventSink {
        val sent = mutableListOf<SyncEvent>()
        override fun send(event: SyncEvent) { sent += event }
    }

    private class TestIdentity(seed: String) : LocalIdentity {
        override val deviceId = DeviceId(seed)
        override val fingerprint = Fingerprint.ofDigest(Sha256.digest("cert-$seed".encodeToByteArray()))
        override val certDer: ByteArray? = null
    }

    private class MemTrustStorage : TrustStorage {
        private var peers: List<TrustedPeer> = emptyList()
        override fun load(): List<TrustedPeer> = peers
        override fun save(newPeers: List<TrustedPeer>) { peers = newPeers }
    }

    private class MemHistoryStorage : HistoryStorage {
        private var events: List<SyncEvent> = emptyList()
        override fun load(): List<SyncEvent> = events
        override fun save(newEvents: List<SyncEvent>) { events = newEvents }
    }

    private class Rig(
        val identity: TestIdentity,
        val sink: RecordingSink,
        val clipboard: FakeClipboard,
        val trustStore: TrustStore,
        val daemon: SyncDaemon,
    )

    private fun rig(seed: String = "alpha", initialClipboard: String? = null): Rig {
        val identity = TestIdentity(seed)
        val sink = RecordingSink()
        val clipboard = FakeClipboard(initialClipboard)
        val trustStore = TrustStore(MemTrustStorage())
        val daemon = SyncDaemon(
            identity = identity,
            trustStore = trustStore,
            clock = InMemoryMonotonicClock(),
            history = ClipHistory(capacity = 10, storage = MemHistoryStorage()),
            eventSink = sink,
            clipboard = clipboard,
        )
        return Rig(identity, sink, clipboard, trustStore, daemon)
    }

    private fun trustedPeer(seed: String) =
        TrustedPeer(Fingerprint.ofDigest(Sha256.digest("cert-$seed".encodeToByteArray())), seed, addedAtEpochMillis = 0)

    @Test
    fun `poll broadcasts a new user copy exactly once`() {
        val rig = rig() // boots with empty clipboard
        rig.clipboard.value = "hello"

        assertTrue(rig.daemon.poll(), "first poll must surface the new copy")
        assertEquals(listOf("hello"), rig.sink.sent.map { it.payload })
        assertEquals(1, rig.sink.sent[0].sequence)

        assertFalse(rig.daemon.poll(), "unchanged clipboard must stay silent")
        assertEquals(1, rig.sink.sent.size)
        assertEquals("hello", rig.daemon.lastSeen)
    }

    @Test
    fun `clipboard content present at boot is not re-synced`() {
        val rig = rig(initialClipboard = "pre-existing")

        assertTrue(rig.sink.sent.isEmpty(), "boot content must not be broadcast")
        assertFalse(rig.daemon.poll(), "boot content must not be broadcast on the first poll either")
        assertEquals("pre-existing", rig.daemon.lastSeen)
    }

    @Test
    fun `network-applied clip is written to the clipboard and never rebroadcast`() {
        val rig = rig()
        rig.clipboard.value = "from user"
        assertTrue(rig.daemon.poll())
        assertEquals(1, rig.sink.sent.size)

        val peer = trustedPeer("beta")
        rig.trustStore.add(peer.fingerprint, "beta")
        rig.daemon.engine.onPeerMessage(peer, SyncEvent(origin = DeviceId("beta"), sequence = 1, payload = "from phone"))

        assertEquals("from phone", rig.clipboard.value, "applied clip must reach the OS clipboard")
        assertEquals(1, rig.clipboard.writes)
        assertEquals(1, rig.sink.sent.size, "peer events are never sent anywhere")

        // The OS clipboard now reports the value we wrote ourselves — must not re-enter the engine.
        assertFalse(rig.daemon.poll(), "echo of our own write must be suppressed")
        assertEquals(1, rig.sink.sent.size, "no rebroadcast of the network-applied clip")
        assertEquals(2, rig.daemon.engine.history.size(), "local copy + applied clip both recorded")
    }

    @Test
    fun `clipboard read failure degrades to no change`() {
        val daemon = SyncDaemon(
            identity = TestIdentity("alpha"),
            trustStore = TrustStore(MemTrustStorage()),
            clock = InMemoryMonotonicClock(),
            history = ClipHistory(capacity = 10, storage = MemHistoryStorage()),
            eventSink = RecordingSink(),
            clipboard = ThrowingClipboard(),
        )

        assertFalse(daemon.poll(), "a failed read must not crash the daemon")
        assertEquals(0, daemon.engine.history.size())
    }
}