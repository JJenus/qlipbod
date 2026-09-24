package dev.qlipbod.sync.identity

import dev.qlipbod.sync.crypto.Fingerprint
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.util.Date

/**
 * Real device identity for the JVM: RSA-2048 keypair with a self-signed X.509 certificate.
 * The fingerprint (SHA-256 of the DER bytes) is the trust anchor stored at pairing time;
 * the certificate itself rides the future mTLS handshake.
 */
class GeneratedIdentity internal constructor(
    override val deviceId: DeviceId,
    internal val keyPair: KeyPair,
    override val certDer: ByteArray,
) : LocalIdentity {

    override val fingerprint: Fingerprint = Fingerprint.of(certDer)

    companion object {
        fun generate(deviceId: DeviceId = DeviceId.random()): GeneratedIdentity {
            val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
            val now = System.currentTimeMillis()
            val subject = X500Name("CN=qlipbod, O=Qlipbod, OU=clipboard-sync")
            val builder = JcaX509v3CertificateBuilder(
                subject,
                BigInteger(160, SecureRandom()),
                Date(now - 86_400_000L),          // tolerate minor clock skew backwards
                Date(now + 10L * 365 * 86_400_000L),
                subject,
                keyPair.public,
            )
            val signer = JcaContentSignerBuilder("SHA256withRSA").build(keyPair.private)
            val cert = builder.build(signer)
            return GeneratedIdentity(deviceId, keyPair, cert.encoded)
        }
    }
}