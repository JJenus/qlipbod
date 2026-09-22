package dev.qlipbod.sync.identity

import dev.qlipbod.sync.crypto.Fingerprint

/**
 * The long-lived identity a device presents during pairing and syncing.
 * Platform-specific implementations materialize a real keypair + self-signed
 * certificate (JVM: [dev.qlipbod.sync.identity.GeneratedIdentity]);
 * tests use a seed-based stub.
 */
interface LocalIdentity {
    val deviceId: DeviceId
    val fingerprint: Fingerprint
    /** DER-encoded self-signed certificate, or null for material-less test identities. */
    val certDer: ByteArray?
}