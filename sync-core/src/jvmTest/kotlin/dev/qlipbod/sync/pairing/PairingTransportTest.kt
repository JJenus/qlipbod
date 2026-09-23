package dev.qlipbod.sync.pairing

import dev.qlipbod.sync.identity.DeviceId
import dev.qlipbod.sync.identity.GeneratedIdentity
import java.net.ServerSocket
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Real loopback TCP pairing over the framed transport (plan §4, §11.1): a shared PIN
 * pairs both sides and returns the counterpart's confirmed identity; a mismatched PIN
 * fails both sides with a typed [PairingFailedException] — never a hang or a silent
 * partial state.
 */
class PairingTransportTest {

    @Test
    fun `two devices pair over loopback with a matching PIN`() {
        val laptop = GeneratedIdentity.generate(DeviceId("laptop"))
        val phone = GeneratedIdentity.generate(DeviceId("phone"))
        val server = ServerSocket(0)

        val phoneResult = CompletableFuture<PairingPeer>()
        val responder = thread(name = "pair-responder") {
            phoneResult.complete(PairingTransport.responder(server, phone, "2468"))
        }

        val laptopPeer = PairingTransport.initiator("127.0.0.1", server.localPort, laptop, "2468")
        val phonePeer = phoneResult.get(10, TimeUnit.SECONDS)
        responder.join()

        // Each side leaves with the *other* device's identity, pinned by fingerprint.
        assertEquals(phone.fingerprint, laptopPeer.fingerprint)
        assertEquals(phone.deviceId, laptopPeer.deviceId)
        assertEquals(laptop.fingerprint, phonePeer.fingerprint)
        assertEquals(laptop.deviceId, phonePeer.deviceId)
    }

    @Test
    fun `a mismatched PIN fails both sides`() {
        val laptop = GeneratedIdentity.generate(DeviceId("laptop"))
        val phone = GeneratedIdentity.generate(DeviceId("phone"))
        val server = ServerSocket(0)

        val responderFailure = CompletableFuture<Throwable>()
        val responder = thread(name = "pair-responder") {
            try {
                PairingTransport.responder(server, phone, "9999")
                responderFailure.complete(AssertionError("responder should have failed"))
            } catch (e: Throwable) {
                responderFailure.complete(e)
            }
        }

        assertFailsWith<PairingFailedException> {
            PairingTransport.initiator("127.0.0.1", server.localPort, laptop, "1234")
        }
        val responderThrowable = responderFailure.get(10, TimeUnit.SECONDS)
        responder.join()

        assertTrue(
            responderThrowable is PairingFailedException,
            "responder must fail with the typed error too, got: $responderThrowable",
        )
    }
}