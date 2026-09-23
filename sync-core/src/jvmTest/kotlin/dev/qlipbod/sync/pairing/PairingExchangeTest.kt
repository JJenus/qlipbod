package dev.qlipbod.sync.pairing

import dev.qlipbod.sync.TestIdentity
import dev.qlipbod.sync.identity.DeviceId
import dev.qlipbod.sync.protocol.FrameCodec
import dev.qlipbod.sync.protocol.HandshakeProtocol
import dev.qlipbod.sync.protocol.UnverifiedPeerException
import dev.qlipbod.sync.trust.TrustStore
import java.util.concurrent.CompletableFuture
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Drives the pairing exchange over in-memory queues (no sockets) to pin the conversation
 * contract: matching PINs confirm on both sides with the counterpart's real identity, and
 * the confirmed fingerprint is exactly what the data-channel handshake resolver accepts.
 */
class PairingExchangeTest {

    /** Runs [PairingSession.drive] on both sides, cross-wired through queues. */
    private fun runExchange(
        initiatorPin: String,
        responderPin: String,
    ): Pair<PairingOutcome, PairingOutcome> {
        val laptop = TestIdentity(DeviceId("laptop"))
        val phone = TestIdentity(DeviceId("phone"))
        val phoneToLaptop = LinkedBlockingQueue<PairingMessage>()
        val laptopToPhone = LinkedBlockingQueue<PairingMessage>()

        val laptopResult = CompletableFuture<PairingOutcome>()
        val phoneResult = CompletableFuture<PairingOutcome>()

        Thread {
            laptopResult.complete(
                PairingSession(PairingRole.INITIATOR, laptop, initiatorPin)
                    .drive({ laptopToPhone.put(it) }, { phoneToLaptop.take() }),
            )
        }.apply { isDaemon = true; start() }

        Thread {
            phoneResult.complete(
                PairingSession(PairingRole.RESPONDER, phone, responderPin)
                    .drive({ phoneToLaptop.put(it) }, { laptopToPhone.take() }),
            )
        }.apply { isDaemon = true; start() }

        return laptopResult.get(5, TimeUnit.SECONDS) to phoneResult.get(5, TimeUnit.SECONDS)
    }

    @Test
    fun `matching PINs confirm both sides and the fingerprint satisfies the handshake`() {
        val laptop = TestIdentity(DeviceId("laptop"))
        val phone = TestIdentity(DeviceId("phone"))

        val (laptopOutcome, phoneOutcome) = runExchange("1234", "1234")

        val laptopConfirmed = assertIs<PairingOutcome.Confirmed>(laptopOutcome)
        val phoneConfirmed = assertIs<PairingOutcome.Confirmed>(phoneOutcome)

        // Each side learned the counterpart's identity from the exchange — not a label.
        assertEquals(phone.fingerprint, laptopConfirmed.peer.fingerprint)
        assertEquals("phone", laptopConfirmed.peer.deviceId.value)
        assertEquals(laptop.fingerprint, phoneConfirmed.peer.fingerprint)
        assertEquals("laptop", phoneConfirmed.peer.deviceId.value)

        // Pairing output drops straight into the data-plane contract: once each side
        // trusts the confirmed fingerprint, the handshake resolver accepts exactly the
        // paired certificate — and nobody else's.
        val laptopTrust = TrustStore().also { it.add(laptopConfirmed.peer.fingerprint, "phone") }
        val phoneTrust = TrustStore().also { it.add(phoneConfirmed.peer.fingerprint, "laptop") }

        val phoneHello = HandshakeProtocol.decodeHello(helloBody(phone))
        val laptopHello = HandshakeProtocol.decodeHello(helloBody(laptop))
        assertEquals(phone.fingerprint, HandshakeProtocol.resolvePeer(phoneHello, laptopTrust).fingerprint)
        assertEquals(laptop.fingerprint, HandshakeProtocol.resolvePeer(laptopHello, phoneTrust).fingerprint)

        val strangerHello = HandshakeProtocol.decodeHello(helloBody(TestIdentity(DeviceId("stranger"))))
        assertFailsWith<UnverifiedPeerException> {
            HandshakeProtocol.resolvePeer(strangerHello, laptopTrust)
        }
    }

    @Test
    fun `a mismatched PIN fails both sides`() {
        val (laptopOutcome, phoneOutcome) = runExchange("1234", "9999")
        assertTrue(laptopOutcome is PairingOutcome.Failed, "initiator must fail: $laptopOutcome")
        assertTrue(phoneOutcome is PairingOutcome.Failed, "responder must fail: $phoneOutcome")
    }

    private fun helloBody(identity: TestIdentity): ByteArray {
        val frame = HandshakeProtocol.encodeHello(identity)
        return frame.copyOfRange(FrameCodec.HEADER_BYTES, frame.size)
    }
}