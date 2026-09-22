package dev.qlipbod.sync.pairing

import dev.qlipbod.sync.TestIdentity
import dev.qlipbod.sync.crypto.Sha256
import dev.qlipbod.sync.identity.DeviceId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Drives a full pairing exchange between two [PairingSession]s in-process,
 * feeding each side's output into the other, mirroring the transport order:
 * HELLO -> ACCEPT -> CONFIRM. Returns both final states.
 */
internal fun drivePairing(
    initiator: PairingSession,
    responder: PairingSession,
): Pair<PairingSession.State, PairingSession.State> {
    val hello = initiator.start()
    val accept = responder.handle(hello)
    if (accept is PairingOutcome.Failed) {
        accept.notice?.let { initiator.handle(it) }
        return initiator.state to responder.state
    }
    require(accept is PairingOutcome.Send) { "responder should respond with ACCEPT, got $accept" }

    val confirm = initiator.handle(accept.message)
    if (confirm is PairingOutcome.Failed) {
        confirm.notice?.let { responder.handle(it) }
        return initiator.state to responder.state
    }
    require(confirm is PairingOutcome.Send) { "initiator should send CONFIRM, got $confirm" }

    responder.handle(confirm.message)
    return initiator.state to responder.state
}

/** PIN-authenticated key exchange (plan §4, §10): PIN gates the fingerprint exchange. */
class PairingTest {

    private val initiatorIdentity = TestIdentity(DeviceId("initiator-device"))
    private val responderIdentity = TestIdentity(DeviceId("responder-device"))
    private val pin = "482913"

    private fun sessionPair(responderPin: String) = Pair(
        PairingSession(PairingRole.INITIATOR, initiatorIdentity, pin),
        PairingSession(PairingRole.RESPONDER, responderIdentity, responderPin),
    )

    @Test
    fun `matching pin confirms both sides and exchanges fingerprints`() {
        val (initiator, responder) = sessionPair(pin)
        val (initiatorState, responderState) = drivePairing(initiator, responder)

        assertEquals(PairingSession.State.CONFIRMED, initiatorState, "initiator must confirm")
        assertEquals(PairingSession.State.CONFIRMED, responderState, "responder must confirm")
        assertEquals(responderIdentity.fingerprint, initiator.peer?.fingerprint, "initiator learns responder fingerprint")
        assertEquals(initiatorIdentity.fingerprint, responder.peer?.fingerprint, "responder learns initiator fingerprint")
        assertEquals(responderIdentity.deviceId, initiator.peer?.deviceId)
        assertEquals(initiatorIdentity.deviceId, responder.peer?.deviceId)
    }

    @Test
    fun `wrong pin fails both sides and exchanges nothing`() {
        val (initiator, responder) = sessionPair(responderPin = "000000")
        val (initiatorState, responderState) = drivePairing(initiator, responder)

        assertEquals(PairingSession.State.FAILED, initiatorState, "initiator must learn of the failure")
        assertEquals(PairingSession.State.FAILED, responderState)
        assertEquals(null, initiator.peer)
        assertEquals(null, responder.peer)
    }

    @Test
    fun `initiator rejects a forged accept without the pin`() {
        val initiator = PairingSession(PairingRole.INITIATOR, initiatorIdentity, pin)
        val hello = initiator.start()

        // A third party without the PIN tries to answer as a trusted device.
        val forged = PairingMessage(
            phase = PairingPhase.ACCEPT,
            sessionId = hello.sessionId,
            nonce = "0f".repeat(8),
            deviceId = DeviceId("attacker"),
            fingerprintHex = Sha256.digestHex("attacker".encodeToByteArray()),
            certDerHex = null,
            macHex = "00", // cannot compute a valid MAC without the PIN
        )
        val outcome = initiator.handle(forged)

        assertTrue(outcome is PairingOutcome.Failed, "forged ACCEPT must fail")
        assertEquals(PairingSession.State.FAILED, initiator.state)
    }

    @Test
    fun `replayed hello with the same session id is rejected`() {
        val initiator = PairingSession(PairingRole.INITIATOR, initiatorIdentity, pin)
        val responder = PairingSession(PairingRole.RESPONDER, responderIdentity, pin)
        val hello = initiator.start()

        assertTrue(responder.handle(hello) is PairingOutcome.Send, "first HELLO is accepted")
        val replay = responder.handle(hello)
        assertTrue(replay is PairingOutcome.Failed, "replayed HELLO must fail")
        assertEquals(PairingSession.State.FAILED, responder.state)
    }

    @Test
    fun `already finished session rejects new input`() {
        val initiator = PairingSession(PairingRole.INITIATOR, initiatorIdentity, pin)
        val responder = PairingSession(PairingRole.RESPONDER, responderIdentity, pin)

        val hello = initiator.start()
        val accept = responder.handle(hello) as PairingOutcome.Send
        val confirm = initiator.handle(accept.message) as PairingOutcome.Send
        val confirmed = responder.handle(confirm.message)
        assertTrue(confirmed is PairingOutcome.Confirmed)

        // Both sides are now CONFIRMED (initiator after sending CONFIRM, responder after receiving).
        assertEquals(PairingSession.State.CONFIRMED, initiator.state)
        assertEquals(PairingSession.State.CONFIRMED, responder.state)

        // Any further message on a finished session is refused.
        val late = PairingMessage(
            phase = PairingPhase.HELLO,
            sessionId = "fresh-session",
            nonce = "ab".repeat(8),
            deviceId = initiatorIdentity.deviceId,
            fingerprintHex = initiatorIdentity.fingerprint.hex,
            certDerHex = null,
            macHex = PairingMac.compute(
                pin, PairingPhase.HELLO, "fresh-session", "ab".repeat(8), initiatorIdentity.deviceId,
                initiatorIdentity.fingerprint.hex, null,
            ),
        )
        assertTrue(responder.handle(late) is PairingOutcome.Failed)
        assertEquals(PairingSession.State.CONFIRMED, responder.state, "confirmed state must not regress")
    }
}