package dev.qlipbod.sync.storage

import dev.qlipbod.sync.identity.DeviceId
import dev.qlipbod.sync.identity.GeneratedIdentity
import java.io.File
import java.security.KeyFactory
import java.security.KeyPair
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec

/**
 * Persists the device identity (deviceId + RSA keypair + the self-signed certificate) so
 * the fingerprint — and therefore every pairing — survives daemon restarts. Without this,
 * a freshly generated identity on boot would orphan all previously paired peers.
 *
 * Files are plain DER/text so they stay inspectable and portable:
 * `device-id`, `identity-key.der` (PKCS#8), `identity-cert.der` (X.509).
 */
class FileIdentityStorage(private val dir: File) {

    private val deviceIdFile get() = File(dir, "device-id")
    private val keyFile get() = File(dir, "identity-key.der")
    private val certFile get() = File(dir, "identity-cert.der")

    /** Load the stored identity, or null when nothing is stored or the store is corrupt. */
    fun load(): GeneratedIdentity? {
        val deviceId = runCatching { deviceIdFile.readText().trim() }.getOrNull()
            ?.takeIf { it.isNotEmpty() } ?: return null
        val keyBytes = runCatching { keyFile.readBytes() }.getOrNull() ?: return null
        val certBytes = runCatching { certFile.readBytes() }.getOrNull() ?: return null
        return runCatching {
            val cert = CertificateFactory.getInstance("X.509")
                .generateCertificate(certBytes.inputStream()) as X509Certificate
            val privateKey = KeyFactory.getInstance("RSA")
                .generatePrivate(PKCS8EncodedKeySpec(keyBytes))
            GeneratedIdentity(DeviceId(deviceId), KeyPair(cert.publicKey, privateKey), certBytes)
        }.getOrNull()
    }

    /** Persist [identity]; overwrites any previous identity in this directory. */
    fun save(identity: GeneratedIdentity) {
        dir.mkdirs()
        deviceIdFile.writeText(identity.deviceId.value)
        keyFile.writeBytes(identity.keyPair.private.encoded)
        certFile.writeBytes(identity.certDer)
    }

    /** [load], or generate a fresh identity and persist it. */
    fun loadOrCreate(): GeneratedIdentity = load() ?: GeneratedIdentity.generate().also { save(it) }
}