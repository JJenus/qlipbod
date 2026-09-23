package dev.qlipbod.sync.discovery

import dev.qlipbod.sync.InMemoryTrustStorage
import dev.qlipbod.sync.TestIdentity
import dev.qlipbod.sync.identity.DeviceId
import dev.qlipbod.sync.trust.TrustStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Plan §4/§5 badge logic, pinned by test: discovery classifies a device as paired only
 * when its *advertised fingerprint* resolves in the trust store — and never treats a
 * missing, malformed, or unknown fingerprint as trust. The handshake remains the real
 * gatekeeper; this only decides whether a found device may be dialed.
 */
class DiscoveryClassifierTest {

    private val laptop = TestIdentity(DeviceId("laptop"))
    private val phone = TestIdentity(DeviceId("phone"))
    private val trustedPhone = TrustStore(InMemoryTrustStorage())
        .also { it.add(phone.fingerprint, "phone") }

    private fun deviceAt(port: Int, fingerprintHex: String? = phone.fingerprint.hex) =
        DiscoveredDevice(label = "phone", address = "192.168.1.20", port = port, fingerprintHex = fingerprintHex)

    @Test
    fun `an advertised fingerprint in the trust store is classified paired`() {
        val status = classifyDiscovery(deviceAt(port = 4343), trustedPhone)
        val paired = assertIs<DiscoveryStatus.Paired>(status)
        assertEquals(phone.fingerprint, paired.peer.fingerprint)
        assertEquals("phone", paired.peer.label)
        assertEquals(4343, paired.device.port)
    }

    @Test
    fun `an unknown fingerprint is unpaired`() {
        val status = classifyDiscovery(
            deviceAt(port = 4343, fingerprintHex = TestIdentity(DeviceId("other")).fingerprint.hex),
            trustedPhone,
        )
        assertIs<DiscoveryStatus.Unpaired>(status)
    }

    @Test
    fun `a device advertising no fingerprint is unpaired`() {
        assertIs<DiscoveryStatus.Unpaired>(classifyDiscovery(deviceAt(port = 4343, fingerprintHex = null), trustedPhone))
    }

    @Test
    fun `a malformed advertised fingerprint is unpaired, not a crash`() {
        assertIs<DiscoveryStatus.Unpaired>(classifyDiscovery(deviceAt(port = 4343, fingerprintHex = "not-hex"), trustedPhone))
        assertIs<DiscoveryStatus.Unpaired>(classifyDiscovery(deviceAt(port = 4343, fingerprintHex = ""), trustedPhone))
    }

    @Test
    fun `an unpaired device that later becomes trusted is paired`() {
        val store = TrustStore(InMemoryTrustStorage())
        assertIs<DiscoveryStatus.Unpaired>(classifyDiscovery(deviceAt(port = 4343), store))
        store.add(phone.fingerprint, "phone")
        assertIs<DiscoveryStatus.Paired>(classifyDiscovery(deviceAt(port = 4343), store))
    }
}