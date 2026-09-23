package dev.qlipbod.app.linux.daemon

import java.net.ServerSocket
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * End-to-end transport contract over real loopback TCP (plan §11.1: prove the trust and
 * transport model before automating anything):
 * - a user copy on A crosses the wire and lands on B's clipboard
 * - B's write of that clip is suppressed as an OS echo (no bounce)
 * - both directions deliver exactly one event per device: no loops
 * - a connection declared as an untrusted peer is refused at B's message layer —
 *   "unpaired, not an error" (plan §9)
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

    /** Wires A (listener) and B (dialer) over loopback, returning the dialed [PeerConnection]. */
    private fun link(daemonA: SyncDaemon, daemonB: SyncDaemon): dev.qlipbod.app.linux.transport.PeerConnection {
        val server = ServerSocket(0)
        val accept = thread(name = "test-accept") { daemonA.acceptOn(server, trustedPeer("beta")) }
        val connB = daemonB.connectTo("127.0.0.1", server.localPort, trustedPeer("alpha"))
        accept.join()
        return connB
    }

    @Test
    fun `copies sync both ways over loopback with no loops`() {
        val aClip = FakeClipboard()
        val bClip = FakeClipboard()
        val daemonA = newTestDaemon("alpha", aClip, trustFor("alpha", "beta"))
        val daemonB = newTestDaemon("beta", bClip, trustFor("alpha", "beta"))
        val connB = link(daemonA, daemonB)

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
    fun `connection declared as an untrusted peer is refused, not applied`() {
        val aClip = FakeClipboard()
        val bClip = FakeClipboard()
        // A's store trusts only itself — beta is unpaired.
        val daemonA = newTestDaemon("alpha", aClip, trustFor("alpha"))
        val daemonB = newTestDaemon("beta", bClip, trustFor("alpha", "beta"))
        val connB = link(daemonA, daemonB)

        bClip.value = "from an unpaired device"
        assertTrue(daemonB.poll())

        await { daemonA.rejectedLog.isNotEmpty() }
        assertEquals(1, daemonA.rejectedLog.size)
        assertEquals("beta", daemonA.rejectedLog[0].first.label)
        assertEquals(null, aClip.value, "unpaired peer's clip must never be applied")
        assertEquals(0, daemonA.broadcastLog.size)

        connB.close()
        daemonA.close()
        daemonB.close()
    }
}