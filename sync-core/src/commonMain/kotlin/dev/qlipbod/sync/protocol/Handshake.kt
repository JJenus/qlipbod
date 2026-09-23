package dev.qlipbod.sync.protocol

import dev.qlipbod.sync.crypto.Fingerprint
import dev.qlipbod.sync.crypto.Hex
import dev.qlipbod.sync.identity.DeviceId
import dev.qlipbod.sync.identity.LocalIdentity
import dev.qlipbod.sync.trust.TrustStore
import dev.qlipbod.sync.trust.TrustedPeer
import kotlinx.serialization.Serializable

/**
 * One certificate-exchange frame, sent by both sides immediately after the socket is
 * established and *before* any sync event can be read. The certificate is the proof of
 * identity: the receiver hashes it and resolves the peer against the trust store, so a
 * connection is trusted as whoever it proves to be — never as whatever an IP or a caller
 * claimed (plan §4 "never trust by network membership", §10).
 */
@Serializable
data class HandshakeHello(
    val protocolVersion: Int = 1,
    val deviceId: DeviceId,
    val certDerHex: String,
)

/** The handshake channel itself failed (truncated, oversized, malformed, or unreadable). */
class HandshakeException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** The presented certificate does not resolve to a fingerprint in the trust store. */
class UnverifiedPeerException(presentedFingerprint: String) :
    Exception("presented certificate ${presentedFingerprint.take(12)}… is not a trusted peer")

/**
 * Pure handshake logic, shared by every platform: encode a Hello, decode one from a frame
 * body, and verify the presented certificate against a trust store. Platform-specific
 * framing glue (reading exactly one frame off a live stream) lives in jvmMain/etc.
 */
object HandshakeProtocol {
    /** A certificate (DER, hex) is ~2 KB; 64 KB headroom rejects hijinks. */
    const val MAX_HELLO_BYTES = 64 * 1024

    /** One Hello frame, ready to write on the socket. */
    fun encodeHello(identity: LocalIdentity): ByteArray {
        val cert = requireNotNull(identity.certDer) { "identity has no certificate to present" }
        val hello = HandshakeHello(deviceId = identity.deviceId, certDerHex = Hex.encode(cert))
        return FrameCodec.encodeFrameBytes(
            FrameCodec.json.encodeToString(HandshakeHello.serializer(), hello).encodeToByteArray(),
        )
    }

    /** Decode a Hello from a frame body (length prefix already consumed). */
    fun decodeHello(body: ByteArray): HandshakeHello = try {
        FrameCodec.json.decodeFromString(HandshakeHello.serializer(), body.decodeToString())
    } catch (e: Exception) {
        throw HandshakeException("malformed hello: ${e.message}", e)
    }

    /** Hash the presented certificate and resolve it to a trusted peer, refusing when unpaired. */
    fun resolvePeer(hello: HandshakeHello, trustStore: TrustStore): TrustedPeer {
        val cert = try {
            Hex.decode(hello.certDerHex)
        } catch (e: IllegalArgumentException) {
            throw HandshakeException("peer certificate is not valid hex", e)
        }
        val fingerprint = Fingerprint.of(cert)
        return trustStore.find(fingerprint) ?: throw UnverifiedPeerException(fingerprint.hex)
    }
}