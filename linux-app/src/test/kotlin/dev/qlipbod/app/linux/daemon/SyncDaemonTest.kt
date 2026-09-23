package dev.qlipbod.app.linux.daemon

import dev.qlipbod.sync.identity.DeviceId
import dev.qlipbod.sync.protocol.SyncEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Daemon clipboard-plane contract (plan §8, §9) — no sockets here:
 * - a new user copy surfaced by [SyncDaemon.poll] reaches the engine exactly once
 * - boot-time clipboard content is never re-synced
 * - a network-applied clip is written to the clipboard, and the resulting
 *   OS-level clipboard echo is suppressed (never rebroadcast)
 * - clipboard read failures degrade to "no change" instead of crashing the daemon
 */
class SyncDaemonTest {

    @Test
    fun `poll broadcasts a new user copy exactly once`() {
        val clipboard = FakeClipboard()
        val daemon = newTestDaemon("alpha", clipboard, trustFor("alpha"))

        clipboard.value = "hello"

        assertTrue(daemon.poll(), "first poll must surface the new copy")
        assertEquals(listOf("hello"), daemon.broadcastLog.map { it.payload })
        assertEquals(1L, daemon.broadcastLog[0].sequence)

        assertFalse(daemon.poll(), "unchanged clipboard must stay silent")
        assertEquals(1, daemon.broadcastLog.size)
        assertEquals("hello", daemon.lastSeen)
        daemon.close()
    }

    @Test
    fun `clipboard content present at boot is not re-synced`() {
        val clipboard = FakeClipboard("pre-existing")
        val daemon = newTestDaemon("alpha", clipboard, trustFor("alpha"))

        assertTrue(daemon.broadcastLog.isEmpty(), "boot content must not be broadcast")
        assertFalse(daemon.poll(), "boot content must not be broadcast on the first poll either")
        assertEquals("pre-existing", daemon.lastSeen)
        daemon.close()
    }

    @Test
    fun `network-applied clip is written to the clipboard and never rebroadcast`() {
        val clipboard = FakeClipboard()
        val daemon = newTestDaemon("alpha", clipboard, trustFor("alpha", "beta"))
        clipboard.value = "from user"
        assertTrue(daemon.poll())
        assertEquals(1, daemon.broadcastLog.size)

        val peer = trustedPeer("beta")
        daemon.engine.onPeerMessage(peer, SyncEvent(origin = DeviceId("beta"), sequence = 1, payload = "from phone"))

        assertEquals("from phone", clipboard.value, "applied clip must reach the OS clipboard")
        assertEquals(1, clipboard.writes)
        assertEquals(1, daemon.broadcastLog.size, "peer events are never broadcast")

        // The OS clipboard now reports the value we wrote ourselves — must not re-enter the engine.
        assertFalse(daemon.poll(), "echo of our own write must be suppressed")
        assertEquals(1, daemon.broadcastLog.size, "no rebroadcast of the network-applied clip")
        assertEquals(2, daemon.engine.history.size(), "local copy + applied clip both recorded")
        daemon.close()
    }

    @Test
    fun `clipboard read failure degrades to no change`() {
        val daemon = newTestDaemon("alpha", ThrowingClipboard(), trustFor("alpha"))

        assertFalse(daemon.poll(), "a failed read must not crash the daemon")
        assertEquals(0, daemon.engine.history.size())
        daemon.close()
    }
}