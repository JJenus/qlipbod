package dev.qlipbod.sync.discovery

import dev.qlipbod.sync.identity.DeviceId
import dev.qlipbod.sync.identity.GeneratedIdentity
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import javax.jmdns.JmDNS
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.Assume.assumeTrue

/**
 * Environment-gated mDNS round trip (plan §3): one [JmDnsDiscovery] advertises a
 * clip-sync service on a real port, a second instance browses for it on the same
 * multicast domain and resolves the label, fingerprint, and port from the TXT records.
 * Skips when the host has no multicast support (headless containers), mirroring the
 * xclip environment gate.
 */
class JmDnsDiscoveryTest {

    private fun multicastAvailable(): Boolean = try {
        val nic = NetworkInterface.networkInterfaces().toList().firstOrNull { it.supportsMulticast() }
            ?: return false
        MulticastSocket().use { socket ->
            val group = InetAddress.getByName("224.0.0.251")
            socket.joinGroup(java.net.InetSocketAddress(group, 0), nic)
            socket.leaveGroup(java.net.InetSocketAddress(group, 0), nic)
        }
        true
    } catch (e: Exception) {
        false
    }

    @Test
    fun `an advertisement is discovered and resolved by a browser on the same network`() {
        assumeTrue("mDNS multicast unavailable on this host", multicastAvailable())
        val phone = GeneratedIdentity.generate(DeviceId("phone"))
        val server = ServerSocket(0)

        val advertiser = JmDnsDiscovery(JmDNS.create())
        val browser = JmDnsDiscovery(JmDNS.create())
        val found = CopyOnWriteArrayList<DiscoveredDevice>()

        try {
            browser.browse { found += it }
            advertiser.advertise(phone, server.localPort)

            await { found.isNotEmpty() }
        } finally {
            advertiser.close()
            browser.close()
            server.close()
        }

        val device = found.single()
        assertEquals(phone.deviceId.value, device.label)
        assertEquals(server.localPort, device.port)
        assertEquals(phone.fingerprint.hex, device.fingerprintHex)
        assertEquals(ClipboardService.PROTOCOL_VERSION, device.protocolVersion)
    }

    private fun await(timeoutMs: Long = 15_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "timed out waiting for mDNS discovery" }
            Thread.sleep(50)
        }
    }
}