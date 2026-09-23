package dev.qlipbod.app.linux.daemon

import dev.qlipbod.app.linux.transport.PeerConnection
import dev.qlipbod.sync.protocol.HandshakeException
import dev.qlipbod.sync.protocol.UnverifiedPeerException
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * End-to-end transport contract over real loopback TCP with the mutual certificate
 * handshake (plan §11.1: prove the trust and transport model):
 * - a user copy on A crosses the wire and lands on B's clipboard
 * - B's write of that clip is suppressed as an OS echo (no bounce)
 * - both directions deliver exactly one event per device: no loops
 * - identity is *proven*: an unpaired device is refused at the handshake, before any
 *   clip can be applied ("unpaired, not an error", plan §9)
 */
class SyncDaemonStreamTest {

    /** Polls until [condition] or fails after [timeoutMs]. */
    private fun await(timeoutMs: Long = 5_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "timed out awaiting condition" }
            Thread.sleep(10)
        }
    }

    /** Wires A (listener) and B (dialer) over loopback; both handshakes must resolve. */
    private fun link(daemonA: SyncDaemon, daemonB: SyncDaemon): PeerConnection {
        val server = ServerSocket(0)
        val accept = thread(name = "test-accept") { daemonA.acceptOn(server) }
        val connB = daemonB.connectTo("127.0.0.1", server.localPort)
        accept.join()
        return connB
    }

    @Test
    fun `copies sync both ways over loopback with no loops`() {
        val idA = wireIdentity("alpha")
        val idB = wireIdentity("beta")
        val aClip = FakeClipboard()
        val bClip = FakeClipboard()
        val daemonA = newWireDaemon(idA, aClip, wireTrust(idA, idB))
        val daemonB = newWireDaemon(idB, bClip, wireTrust(idA, idB))
        val connB = link(daemonA, daemonB)

        // The handshake verified identity — B knows exactly who it is connected to.
        assertEquals("alpha", connB.peer.label, "B verified A's certificate and resolved it")

        // A copies -> B applies.
        aClip.value = "hello from A"
        assertTrue(daemonA.poll())
        await { bClip.value == "hello from A" }
        assertEquals("hello from A", bClip.value)

        // B's OS echo must not bounce back to A.
        assertFalse(daemonB.poll(), "B's echo of the network-applied clip must be suppressed")
        assertEquals(1, daemonA.broadcastLog.size)

        // B copies -> A applies.
        bClip.value = "hello from B"
        assertTrue(daemonB.poll())
        await { aClip.value == "hello from B" }
        assertEquals("hello from B", aClip.value)
        assertFalse(daemonA.poll(), "A's echo must be suppressed too")

        // Exact one broadcast per device — no loops.
        assertEquals(1, daemonA.broadcastLog.size, "A broadcast exactly its own clip")
        assertEquals(1, daemonB.broadcastLog.size, "B broadcast exactly its own clip")

        connB.close()
        daemonA.close()
        daemonB.close()
    }

    @Test
    fun `an unpaired device is refused at the handshake before any clip can apply`() {
        val idA = wireIdentity("alpha")
        val idB = wireIdentity("beta")
        val aClip = FakeClipboard()
        val bClip = FakeClipboard()
        // Only A's own fingerprint is trusted on A; beta is unpaired.
        val daemonA = newWireDaemon(idA, aClip, wireTrust(idA))
        val daemonB = newWireDaemon(idB, bClip, wireTrust(idA, idB))

        val server = ServerSocket(0)
        val acceptErrors = CopyOnWriteArrayList<Throwable>()
        val accept = thread(name = "test-accept") {
            try {
                daemonA.acceptOn(server)
            } catch (e: Throwable) {
                acceptErrors.add(e)
            }
        }

        // B dials presenting a certificate A does not trust. A always refuses and closes;
        // B either sees the refusal or lands a connection A kills before any data flows.
        val connB = try {
            daemonB.connectTo("127.0.0.1", server.localPort)
        } catch (e: Exception) {
            null
        }
        accept.join()
        assertEquals(1, acceptErrors.size, "A must have refused at the handshake")
        assertTrue(
            acceptErrors[0] is UnverifiedPeerException,
            "A refuses the unpaired device, got: ${acceptErrors[0]}",
        )

        assertEquals(null, aClip.value, "an unpaired device's clip must never be applied")
        assertEquals(0, daemonA.broadcastLog.size)

        connB?.close()
        daemonA.close()
        daemonB.close()
    }

    @Test
    fun `a peer that closes mid-handshake surfaces a handshake error, not a crash`() {
        val idA = wireIdentity("alpha")
        val aClip = FakeClipboard()
        val daemonA = newWireDaemon(idA, aClip, wireTrust(idA))

        val server = ServerSocket(0)
        val acceptErrors = CopyOnWriteArrayList<Throwable>()
        val accept = thread(name = "test-accept") {
            try {
                daemonA.acceptOn(server)
            } catch (e: Throwable) {
                acceptErrors.add(e)
            }
        }

        // A socket that connects, then hangs up without presenting a Hello.
        thread(name = "test-connector") {
            java.net.Socket("127.0.0.1", server.localPort).close()
        }

        accept.join()
        assertEquals(1, acceptErrors.size)
        assertTrue(
            acceptErrors[0] is HandshakeException,
            "a mid-handshake close must be a HandshakeException, got: ${acceptErrors[0]}",
        )
        daemonA.close()
    }
}