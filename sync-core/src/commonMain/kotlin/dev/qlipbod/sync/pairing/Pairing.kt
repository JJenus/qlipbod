package dev.qlipbod.sync.pairing

import dev.qlipbod.sync.crypto.Fingerprint
import dev.qlipbod.sync.crypto.Hex
import dev.qlipbod.sync.crypto.HmacSha256
import dev.qlipbod.sync.crypto.Sha256
import dev.qlipbod.sync.crypto.randomHex
import dev.qlipbod.sync.identity.DeviceId
import dev.qlipbod.sync.identity.LocalIdentity
import kotlinx.serialization.Serializable

enum class PairingRole { INITIATOR, RESPONDER }

enum class PairingPhase { HELLO, ACCEPT, CONFIRM, FAILED }

/**
 * A pairing exchange message. The MAC binds every field to the shared PIN, so a third
 * party on the network cannot inject its own key during the exchange (plan §4, §10.1).
 */
@Serializable
data class PairingMessage(
    val phase: PairingPhase,
    val sessionId: String,
    val nonce: String,
    val deviceId: DeviceId,
    val fingerprintHex: String,
    val certDerHex: String? = null,
    val macHex: String,
)

data class PairingPeer(
    val deviceId: DeviceId,
    val fingerprint: Fingerprint,
    val certDerHex: String?,
)

sealed interface PairingOutcome {
    /** Transmit this message to the other side. */
    data class Send(val message: PairingMessage) : PairingOutcome

    /** Exchange finished; peer identity is pinned and ready to be trusted. */
    data class Confirmed(val peer: PairingPeer) : PairingOutcome

    /** Exchange aborted. [notice] should be sent so the counterpart learns why. */
    data class Failed(val reason: String, val notice: PairingMessage? = null) : PairingOutcome
}

/**
 * PIN-derived MAC (HMAC-SHA256) over the canonical field tuple. Only a party that knows
 * the PIN can produce or verify a message, which is what stops a MITM key injection.
 */
internal object PairingMac {
    private const val DOMAIN = "qlipbod-pair-v1"

    fun compute(
        pin: String,
        phase: PairingPhase,
        sessionId: String,
        nonce: String,
        deviceId: DeviceId,
        fingerprintHex: String,
        certDerHex: String?,
    ): String {
        val canonical = listOf(
            phase.name, sessionId, nonce, deviceId.value, fingerprintHex, certDerHex ?: "",
        ).joinToString("|")
        val key = Sha256.digest("$DOMAIN|$pin".encodeToByteArray())
        return Hex.encode(HmacSha256.digest(key, canonical.encodeToByteArray()))
    }

    fun verify(pin: String, message: PairingMessage): Boolean {
        val expected = compute(
            pin, message.phase, message.sessionId, message.nonce,
            message.deviceId, message.fingerprintHex, message.certDerHex,
        )
        return constantTimeEquals(expected, message.macHex)
    }

    private fun constantTimeEquals(a: String, b: String): Boolean {
        if (a.length != b.length) return false
        var diff = 0
        for (i in a.indices) diff = diff or (a[i].code xor b[i].code)
        return diff == 0
    }
}

/**
 * State machine for the one-time PIN/QR pairing exchange (plan §4):
 *
 * ```
 * INITIATOR                       RESPONDER
 *   start()  ── HELLO ─────────────►   verify MAC, echo sessionId
 *             ◄─ ACCEPT ───────────    verify MAC, pin peer
 *   CONFIRMED ─ CONFIRM ───────────►   CONFIRMED
 * ```
 *
 * Any MAC failure, replayed session id, or message on a finished session fails the
 * exchange for both sides; a [PairingOutcome.Failed.notice] carries the bad news back.
 */
class PairingSession(
    private val role: PairingRole,
    private val identity: LocalIdentity,
    private val pin: String,
) {
    enum class State { STARTED, WAITING, CONFIRMED, FAILED }

    var state: State = State.STARTED
        private set

    /** Peer learned during the exchange; set on success, null otherwise. */
    var peer: PairingPeer? = null
        private set

    private var sessionId: String? = null
    private val seenSessionIds = mutableSetOf<String>()
    private var pendingPeer: PairingPeer? = null

    fun start(): PairingMessage {
        require(role == PairingRole.INITIATOR) { "only the initiator starts a pairing" }
        require(state == State.STARTED) { "session already started" }
        val sid = randomHex(16)
        sessionId = sid
        state = State.WAITING
        return build(PairingPhase.HELLO, sid)
    }

    fun handle(message: PairingMessage): PairingOutcome {
        if (state == State.CONFIRMED || state == State.FAILED) {
            return PairingOutcome.Failed("session already finished")
        }
        if (message.phase == PairingPhase.FAILED) {
            state = State.FAILED
            return PairingOutcome.Failed("peer reports failure: ${message.sessionId}")
        }
        if (!PairingMac.verify(pin, message)) {
            state = State.FAILED
            return PairingOutcome.Failed(
                "PIN mismatch or tampered message",
                notice = build(PairingPhase.FAILED, message.sessionId),
            )
        }
        return when (role) {
            PairingRole.RESPONDER -> handleAsResponder(message)
            PairingRole.INITIATOR -> handleAsInitiator(message)
        }
    }

    private fun handleAsResponder(message: PairingMessage): PairingOutcome = when (message.phase) {
        PairingPhase.HELLO -> {
            if (!seenSessionIds.add(message.sessionId)) {
                state = State.FAILED
                PairingOutcome.Failed(
                    "replayed HELLO for session ${message.sessionId}",
                    notice = build(PairingPhase.FAILED, message.sessionId),
                )
            } else {
                pendingPeer = PairingPeer(
                    deviceId = message.deviceId,
                    fingerprint = Fingerprint(message.fingerprintHex),
                    certDerHex = message.certDerHex,
                )
                PairingOutcome.Send(build(PairingPhase.ACCEPT, message.sessionId))
            }
        }

        PairingPhase.CONFIRM -> {
            val confirmed = pendingPeer
                ?: run {
                    state = State.FAILED
                    return PairingOutcome.Failed("CONFIRM received before HELLO")
                }
            peer = confirmed
            state = State.CONFIRMED
            PairingOutcome.Confirmed(confirmed)
        }

        else -> PairingOutcome.Failed("unexpected ${message.phase} for responder")
    }

    private fun handleAsInitiator(message: PairingMessage): PairingOutcome = when (message.phase) {
        PairingPhase.ACCEPT -> {
            if (message.sessionId != sessionId) {
                state = State.FAILED
                PairingOutcome.Failed("session id mismatch")
            } else if (!seenSessionIds.add(message.sessionId)) {
                state = State.FAILED
                PairingOutcome.Failed("replayed ACCEPT")
            } else {
                val accepted = PairingPeer(
                    deviceId = message.deviceId,
                    fingerprint = Fingerprint(message.fingerprintHex),
                    certDerHex = message.certDerHex,
                )
                // The peer's MAC already proved PIN knowledge; confirm and finish.
                val confirm = build(PairingPhase.CONFIRM, message.sessionId)
                peer = accepted
                state = State.CONFIRMED
                PairingOutcome.Send(confirm)
            }
        }

        else -> PairingOutcome.Failed("unexpected ${message.phase} for initiator")
    }

    private fun build(phase: PairingPhase, sid: String): PairingMessage {
        val nonce = randomHex(16)
        val certDerHex = identity.certDer?.let { Hex.encode(it) }
        return PairingMessage(
            phase = phase,
            sessionId = sid,
            nonce = nonce,
            deviceId = identity.deviceId,
            fingerprintHex = identity.fingerprint.hex,
            certDerHex = certDerHex,
            macHex = PairingMac.compute(pin, phase, sid, nonce, identity.deviceId, identity.fingerprint.hex, certDerHex),
        )
    }
}