package dev.qlipbod.sync.pairing

import dev.qlipbod.sync.identity.LocalIdentity
import dev.qlipbod.sync.protocol.FrameCodec
import dev.qlipbod.sync.protocol.StreamFrameException
import dev.qlipbod.sync.protocol.readFrameBody
import kotlinx.serialization.SerializationException
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException

/**
 * The one-time PIN exchange over a short-lived TCP connection (plan §4, §11.1, §9 "add by
 * IP" fallback). Each side runs its [PairingSession] to CONFIRMED or FAILED; on success the
 * confirmed [PairingPeer] is returned so the caller can persist its fingerprint into the
 * [dev.qlipbod.sync.trust.TrustStore]. Pairing connections are separate from data
 * connections: once a fingerprint is trusted, ordinary sync flows over the verified
 * handshake — nothing about pairing repeats.
 */
object PairingTransport {

    /** How long either side waits for the counterpart's next message before giving up. */
    const val EXCHANGE_TIMEOUT_MS = 30_000

    /**
     * Initiator side: dial [host]:[port] and present the PIN. Blocks until the exchange
     * settles; throws [PairingFailedException] on mismatch, timeout, or a vanished peer.
     */
    fun initiator(host: String, port: Int, identity: LocalIdentity, pin: String): PairingPeer =
        onSocket(Socket(host, port)) { socket -> exchange(socket, PairingSession(PairingRole.INITIATOR, identity, pin)) }

    /**
     * Responder side: block until a device connects on [serverSocket], then run the same
     * exchange. Call on a dedicated thread.
     */
    fun responder(serverSocket: ServerSocket, identity: LocalIdentity, pin: String): PairingPeer =
        onSocket(serverSocket.accept()) { socket -> exchange(socket, PairingSession(PairingRole.RESPONDER, identity, pin)) }

    private inline fun onSocket(socket: Socket, block: (Socket) -> PairingPeer): PairingPeer =
        try {
            socket.soTimeout = EXCHANGE_TIMEOUT_MS
            block(socket)
        } finally {
            runCatching { socket.close() }
        }

    private fun exchange(socket: Socket, session: PairingSession): PairingPeer {
        val input = DataInputStream(BufferedInputStream(socket.getInputStream(), 8192))
        val output = socket.getOutputStream()
        try {
            return when (
                val outcome = session.drive(
                    send = { write(output, it) },
                    receive = { read(input) },
                )
            ) {
                is PairingOutcome.Confirmed -> outcome.peer
                is PairingOutcome.Failed -> throw PairingFailedException(outcome.reason)
                is PairingOutcome.Send -> error("exchange settled with an undelivered message")
            }
        } catch (e: SocketTimeoutException) {
            throw PairingFailedException("pairing timed out waiting for the other side", e)
        } catch (e: StreamFrameException) {
            throw PairingFailedException(e.message ?: "pairing read failed", e)
        }
    }

    private fun write(output: OutputStream, message: PairingMessage) {
        val body = FrameCodec.json.encodeToString(PairingMessage.serializer(), message).encodeToByteArray()
        output.write(FrameCodec.encodeFrameBytes(body))
        output.flush()
    }

    private fun read(input: DataInputStream): PairingMessage {
        val body = readFrameBody(input, what = "pairing message")
        return try {
            FrameCodec.json.decodeFromString(PairingMessage.serializer(), body.decodeToString())
        } catch (e: SerializationException) {
            throw StreamFrameException("malformed pairing message: ${e.message}", e)
        }
    }
}

/** The PIN exchange failed: mismatch, replay, tampering, timeout, or a vanished peer. */
class PairingFailedException(message: String, cause: Throwable? = null) : Exception(message, cause)