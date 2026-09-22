package dev.qlipbod.sync.crypto

import kotlinx.serialization.Serializable

/**
 * The trust anchor: a SHA-256 digest of a peer's certificate bytes, as lowercase hex.
 * Identity is fingerprint-based and network-independent (plan §4): never bound to
 * an IP, MAC, or the network a device happens to be on.
 */
@Serializable
@JvmInline
value class Fingerprint(val hex: String) {
    init {
        require(hex.length == HEX_LENGTH) { "fingerprint must be $HEX_LENGTH hex chars, got ${hex.length}" }
        require(hex.all { it in HEX_CHARS }) { "fingerprint must be lowercase hex" }
    }

    override fun toString(): String = hex

    companion object {
        const val HEX_LENGTH = 64
        private const val HEX_CHARS = "0123456789abcdef"

        fun of(certDer: ByteArray): Fingerprint = ofDigest(Sha256.digest(certDer))

        fun ofDigest(digest: ByteArray): Fingerprint = Fingerprint(Hex.encode(digest))
    }
}