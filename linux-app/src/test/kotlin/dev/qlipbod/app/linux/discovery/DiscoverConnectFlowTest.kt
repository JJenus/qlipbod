package dev.qlipbod.app.linux.discovery

import dev.qlipbod.app.linux.daemon.FakeClipboard
import dev.qlipbod.app.linux.daemon.await
import dev.qlipbod.app.linux.daemon.newWireDaemon
import dev.qlipbod.app.linux.daemon.wireIdentity
import dev.qlipbod.app.linux.daemon.wireTrust
import dev.qlipbod.app.linux.daemon.FakeDiscovery
import dev.qlipbod.sync.discovery.DiscoveredDevice
import dev.qlipbod.sync.discovery.DiscoveryStatus
import dev.qlipbod.sync.protocol.HandshakeException
import dev.qlipbod.sync.protocol.UnverifiedPeerException
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Plan §5, wired end to end (plan §11.1): discovery feeds [AutoConnect], which classifies
 * each found device against the daemon's trust store and — only for devices whose
 * *advertised fingerprint* is already trusted — dials it through the fingerprint-verified
 * handshake. An unpaired device is never dialed; a lying advertisement (trusted
 * fingerprint, untrusted certificate) is still refused at the handshake.
 */
class DiscoverConnectFlowTest {

    @Test
    fun `a discovered paired device is dialed and the verified handshake syncs clips`() {
        val laptopId = wireIdentity("laptop")
        val phoneId = wireIdentity("phone")
        val laptopClip = FakeClipboard()
        val phoneClip = FakeClipboard()
        val laptop = newWireDaemon(laptopId, laptopClip, wireTrust(laptopId, phoneId))
        val phone = newWireDaemon(phoneId, phoneClip, wireTrust(laptopId, phoneId))

        // The phone is listening on the port it will advertise.
        val server = ServerSocket(0)
        val acceptErrors = CopyOnWriteArrayList<Throwable>()
        val accept = thread(name = "phone-accept") {
            try { phone.acceptOn(server) } catch (e: Throwable) { acceptErrors += e }
        }

        val discovery = FakeDiscovery()
        val statuses = CopyOnWriteArrayList<DiscoveryStatus>()
        AutoConnect(laptop, discovery) { statuses += it }.apply { start() }
        discovery.find(
            DiscoveredDevice(
                label = "phone", address = "127.0.0.1", port = server.localPort,
                fingerprintHex = phoneId.fingerprint.hex,
            ),
        )

        accept.join(5_000)
        assertEquals(emptyList<Throwable>(), acceptErrors, "the phone-side handshake must succeed")

        // Auto-connect dialed it: a user copy crosses to the phone.
        laptopClip.value = "found you on the network"
        assertTrue(laptop.poll())
        await { phoneClip.value == "found you on the network" }
        assertEquals("found you on the network", phoneClip.value)

        assertEquals(1, statuses.size, "exactly one status: the paired classification")
        assertIs<DiscoveryStatus.Paired>(statuses.single())

        discovery.close()
        server.close()
        laptop.close()
        phone.close()
    }

    @Test
    fun `an unpaired discovered device is never dialed and is reported as unpaired`() {
        val laptopId = wireIdentity("laptop")
        val phoneId = wireIdentity("phone")
        val laptop = newWireDaemon(laptopId, FakeClipboard(), wireTrust(laptopId)) // phone NOT paired
        val phone = newWireDaemon(phoneId, FakeClipboard(), wireTrust(laptopId, phoneId))

        // The phone advertises itself, but since it is not trusted it must never receive
        // a dial: its accept loop stays blocked for the whole window.
        val server = ServerSocket(0)
        val acceptReturned = java.util.concurrent.CountDownLatch(1)
        val accept = thread(name = "phone-accept") {
            try { phone.acceptOn(server) } catch (e: Throwable) { }
            acceptReturned.countDown()
        }

        val discovery = FakeDiscovery()
        val statuses = CopyOnWriteArrayList<DiscoveryStatus>()
        AutoConnect(laptop, discovery) { statuses += it }.apply { start() }
        discovery.find(
            DiscoveredDevice(
                label = "phone", address = "127.0.0.1", port = server.localPort,
                fingerprintHex = phoneId.fingerprint.hex,
            ),
        )

        Thread.sleep(300) // give any (wrong) dial the whole loopback round trip to land
        assertEquals(1L, acceptReturned.count, "the phone must never have been dialed")

        val status = assertIs<DiscoveryStatus.Unpaired>(statuses.single())
        assertEquals("phone", status.device.label)

        discovery.close()
        server.close() // unblock the accept thread
        accept.join(5_000)
        laptop.close()
        phone.close()
    }

    @Test
    fun `a lying advertisement is refused by the handshake and never syncs a clip`() {
        val laptopId = wireIdentity("laptop")
        val phoneId = wireIdentity("phone")
        val impostorId = wireIdentity("impostor")

        // The laptop trusts the *phone*; the impostor trusts nobody but itself.
        val laptop = newWireDaemon(laptopId, FakeClipboard(), wireTrust(laptopId, phoneId))
        val impostorClip = FakeClipboard()
        val impostor = newWireDaemon(impostorId, impostorClip, wireTrust(impostorId))

        val server = ServerSocket(0)
        val acceptErrors = CopyOnWriteArrayList<Throwable>()
        val accept = thread(name = "impostor-accept") {
            try { impostor.acceptOn(server) } catch (e: Throwable) { acceptErrors += e }
        }

        val discovery = FakeDiscovery()
        val statuses = CopyOnWriteArrayList<DiscoveryStatus>()
        AutoConnect(laptop, discovery) { statuses += it }.apply { start() }

        // Advertises the PHONE's fingerprint (paired badge) but serves the IMPOSTOR's
        // certificate on the wire: the classifier dials it, the handshake refuses it.
        discovery.find(
            DiscoveredDevice(
                label = "phone", address = "127.0.0.1", port = server.localPort,
                fingerprintHex = phoneId.fingerprint.hex,
            ),
        )

        accept.join(5_000)
        assertEquals(1, acceptErrors.size, "the impostor side must also fail the handshake")
        assertTrue(
            acceptErrors.single() is UnverifiedPeerException || acceptErrors.single() is HandshakeException,
            "impostor handshake must be refused, got: ${acceptErrors.single()}",
        )

        // Paired by advertisement, then re-classified unpaired when the handshake refused.
        assertEquals(2, statuses.size, "Paired then fallback Unpaired")
        assertIs<DiscoveryStatus.Paired>(statuses[0])
        assertIs<DiscoveryStatus.Unpaired>(statuses[1])
        assertEquals(null, impostorClip.value, "no clip may flow from a refused handshake")

        discovery.close()
        server.close()
        laptop.close()
        impostor.close()
    }
}