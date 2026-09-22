package dev.qlipbod.sync.identity

import dev.qlipbod.sync.crypto.Sha256
import org.bouncycastle.cert.X509CertificateHolder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class GeneratedIdentityTest {

    @Test
    fun `generates a self-signed RSA cert with stable fingerprint`() {
        val identity = GeneratedIdentity.generate()

        assertTrue(identity.certDer.isNotEmpty())
        assertTrue(identity.fingerprint.hex.length == 64, "fingerprint is a SHA-256 hex digest")

        val holder = X509CertificateHolder(identity.certDer)
        assertEquals("1.2.840.113549.1.1.1", holder.subjectPublicKeyInfo.algorithm.algorithm.id, "RSA public key")
        assertTrue(holder.subject.toString().contains("CN=qlipbod"), "subject identifies the app")
        assertTrue(holder.notAfter.time > holder.notBefore.time)

        // Fingerprint is exactly SHA-256 of the certificate bytes (the TLS pinning anchor).
        assertEquals(Sha256.digestHex(identity.certDer), identity.fingerprint.hex)
    }

    @Test
    fun `two identities get distinct device ids and fingerprints`() {
        val first = GeneratedIdentity.generate()
        val second = GeneratedIdentity.generate()
        assertNotEquals(first.deviceId, second.deviceId)
        assertNotEquals(first.fingerprint, second.fingerprint)
    }

    @Test
    fun `generating with an explicit device id preserves it`() {
        val id = DeviceId("fixed-device-id")
        assertEquals(id, GeneratedIdentity.generate(id).deviceId)
    }
}