package dev.qlipbod.app.linux.daemon

import dev.qlipbod.sync.discovery.DiscoveredDevice
import dev.qlipbod.sync.discovery.DiscoveryStatus
import java.net.ServerSocket
import java.net.ConnectException
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The long-running composition behind `qlipbod start` (plan §8): the poll loop surfaces
 * user copies, the accept loop takes inbound verified dials, discovery + [AutoConnect]
 * dial paired devices as they appear, and `--connect` (the §9 manual add-by-IP) dials a
 * fixed host at startup. Everything stops on [DaemonRuntime.close].
 */
class DaemonRuntimeTest {

    @Test
    fun `the poll and accept loops sync a manual dial both directions`() {
        val laptopId = wireIdentity("laptop")
        val phoneId = wireIdentity("phone")
        val laptopClip = FakeClipboard()
        val phoneClip = FakeClipboard()
        val laptop = newWireDaemon(laptopId, laptopClip, wireTrust(laptopId, phoneId))
        val phone = newWireDaemon(phoneId, phoneClip, wireTrust(laptopId, phoneId))

        val runtime = DaemonRuntime(laptop, FakeDiscovery(), port = 0, pollIntervalMs = 20)
        runtime.start()
        try {
            // A paired phone dials the laptop; the accept loop authenticates it.
            phone.connectTo("127.0.0.1", runtime.boundPort)

            laptopClip.value = "from laptop"
            await { phoneClip.value == "from laptop" }
            assertEquals("from laptop", phoneClip.value, "laptop poll loop → engine → phone")

            phoneClip.value = "from phone"
            assertTrue(phone.poll(), "the phone daemon surfaces its user copy")
            await { laptopClip.value == "from phone" }
            assertEquals("from phone", laptopClip.value, "phone broadcast → laptop apply")
        } finally {
            runtime.close()
        }
    }

    @Test
    fun `a discovered paired device is dialed by the runtime`() {
        val laptopId = wireIdentity("laptop")
        val phoneId = wireIdentity("phone")
        val laptopClip = FakeClipboard()
        val phoneClip = FakeClipboard()
        val laptop = newWireDaemon(laptopId, laptopClip, wireTrust(laptopId, phoneId))
        val phone = newWireDaemon(phoneId, phoneClip, wireTrust(laptopId, phoneId))

        val discovery = FakeDiscovery()
        val statuses = CopyOnWriteArrayList<DiscoveryStatus>()
        val runtime = DaemonRuntime(
            laptop, discovery, port = 0, pollIntervalMs = 20,
            onStatus = { statuses += it },
        )
        runtime.start()
        val phoneAcceptErrors = CopyOnWriteArrayList<Throwable>()
        val phoneServer = ServerSocket(0)
        val phoneAccept = thread(name = "test-phone-accept") {
            try { phone.acceptOn(phoneServer) } catch (e: Throwable) { phoneAcceptErrors += e }
        }
        try {
            await { discovery.browsing } // browse runs on its own thread inside the runtime
            discovery.find(
                DiscoveredDevice(
                    label = "phone", address = "127.0.0.1", port = phoneServer.localPort,
                    fingerprintHex = phoneId.fingerprint.hex,
                ),
            )

            phoneAccept.join(5_000)
            assertEquals(emptyList<Throwable>(), phoneAcceptErrors, "the phone-side handshake must succeed")

            laptopClip.value = "found by discovery"
            await { phoneClip.value == "found by discovery" }
            assertEquals("found by discovery", phoneClip.value)

            assertEquals(1, statuses.size, "one paired classification broadcast")
            assertIs<DiscoveryStatus.Paired>(statuses.single())
        } finally {
            runtime.close()
            phoneServer.close()
        }
    }

    @Test
    fun `the manual add-by-IP connect dials a fixed host at startup`() {
        val laptopId = wireIdentity("laptop")
        val phoneId = wireIdentity("phone")
        val laptopClip = FakeClipboard()
        val phoneClip = FakeClipboard()
        val laptop = newWireDaemon(laptopId, laptopClip, wireTrust(laptopId, phoneId))
        val phone = newWireDaemon(phoneId, phoneClip, wireTrust(laptopId, phoneId))

        val phoneServer = ServerSocket(0)
        val phoneAcceptErrors = CopyOnWriteArrayList<Throwable>()
        val phoneAccept = thread(name = "test-phone-accept") {
            try { phone.acceptOn(phoneServer) } catch (e: Throwable) { phoneAcceptErrors += e }
        }

        val failures = CopyOnWriteArrayList<Pair<DaemonRuntime.HostPort, Throwable>>()
        val runtime = DaemonRuntime(
            laptop, FakeDiscovery(), port = 0, pollIntervalMs = 20,
            initialDials = listOf(DaemonRuntime.HostPort("127.0.0.1", phoneServer.localPort)),
            onDialFailure = { address, error -> failures += address to error },
        )
        runtime.start()
        try {
            phoneAccept.join(5_000)
            assertEquals(emptyList<Throwable>(), phoneAcceptErrors, "the §9 fallback dial must connect")

            laptopClip.value = "added by ip"
            await { phoneClip.value == "added by ip" }
            assertEquals("added by ip", phoneClip.value)
            assertEquals(emptyList<Pair<DaemonRuntime.HostPort, Throwable>>(), failures)
        } finally {
            runtime.close()
            phoneServer.close()
        }
    }

    @Test
    fun `close is graceful, idempotent, and stops accepting`() {
        val laptop = newWireDaemon(wireIdentity("laptop"), FakeClipboard(), wireTrust())
        val runtime = DaemonRuntime(laptop, FakeDiscovery(), port = 0, pollIntervalMs = 20)
        runtime.start()

        runtime.close()
        runtime.close() // idempotent: the second close must be a no-op

        // The daemon is closed and its socket released: a fresh dial must be refused
        // outright (no accept loop is left to answer it).
        assertFailsWith<ConnectException> { laptop.connectTo("127.0.0.1", runtime.boundPort) }
    }
}