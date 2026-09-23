package dev.qlipbod.sync.discovery

import dev.qlipbod.sync.identity.LocalIdentity
import javax.jmdns.JmDNS
import javax.jmdns.ServiceEvent
import javax.jmdns.ServiceInfo
import javax.jmdns.ServiceListener

/**
 * mDNS/DNS-SD discovery on the JVM via JmDNS (plan §3; Avahi-free, pure multicast).
 * Translates the DNS-SD event model into the space-agnostic [DiscoveryService] contract:
 *
 * - [advertise] registers `_clipsync._tcp` with our label, protocol version, and
 *   fingerprint in TXT records — the identity *claim* used for the paired/unpaired badge;
 * - [browse] resolves every appearance to a [DiscoveredDevice] and reports it exactly
 *   once until the service disappears (re-announcements are deduped);
 * - [close] deregisters everything so the same instance can be restarted after a network
 *   change (plan §3).
 *
 * Trust is deliberately absent here: the caller classifies against its trust store, and
 * the connection handshake remains the gatekeeper (§4, §5).
 */
class JmDnsDiscovery(
    private val jmdns: JmDNS,
    private val serviceType: String = ClipboardService.TYPE,
) : DiscoveryService {

    private var advertisement: ServiceInfo? = null
    private var listener: ServiceListener? = null

    override fun advertise(identity: LocalIdentity, port: Int) {
        check(advertisement == null) { "already advertising on $serviceType" }
        val info = ServiceInfo.create(
            serviceType,
            ClipboardService.instanceName(identity.deviceId.value),
            port,
            0,
            0,
            ClipboardService.txt(identity.deviceId.value, identity.fingerprint.hex),
        )
        jmdns.registerService(info)
        advertisement = info
    }

    override fun browse(listener: DiscoveryListener) {
        check(this.listener == null) { "already browsing $serviceType" }
        val adapter = object : ServiceListener {
            /** Instance names already reported, so periodic re-announcements stay silent. */
            private val announced = mutableSetOf<String>()

            /** How many resolves we have seen without TXT, per instance name. */
            private val noTxtResolves = mutableMapOf<String, Int>()

            override fun serviceAdded(event: ServiceEvent) {
                // The full record is delivered via serviceResolved once requested.
                jmdns.requestServiceInfo(event.type, event.name, false)
            }

            override fun serviceResolved(event: ServiceEvent) {
                val info = event.info
                val txt = readTxt(info)

                // JmDNS can resolve a service before its TXT records have arrived (the
                // DATA_PENDING resolve). Wait a bounded number of retries so our TXT —
                // label + fingerprint — is present when we report the device; without it
                // the badge logic would mislabel everything as unpaired (§4).
                if (txt.isEmpty()) {
                    val tries = (noTxtResolves[info.name] ?: 0) + 1
                    noTxtResolves[info.name] = tries
                    if (tries < MAX_NO_TXT_RESOLVES) {
                        jmdns.requestServiceInfo(event.type, event.name, true)
                        return
                    }
                }
                noTxtResolves.remove(info.name)

                if (announced.add(info.name)) listener.onDeviceFound(info.toDiscoveredDevice(txt))
            }

            override fun serviceRemoved(event: ServiceEvent) {
                listener.onDeviceLost(event.info.toDiscoveredDevice(readTxt(event.info)))
                announced.remove(event.info.name)
                noTxtResolves.remove(event.info.name)
            }
        }
        jmdns.addServiceListener(serviceType, adapter)
        jmdns.list(serviceType) // resolve anything already announced before we subscribed
        this.listener = adapter
    }

    override fun close() {
        listener?.let { runCatching { jmdns.removeServiceListener(serviceType, it) } }
        listener = null
        advertisement?.let { runCatching { jmdns.unregisterService(it) } }
        advertisement = null
    }

    private companion object {
        /** Resolves JmDNS can deliver without TXT before we report a device anyway. */
        const val MAX_NO_TXT_RESOLVES = 5
    }
}

private fun readTxt(info: ServiceInfo): Map<String, String> =
    info.getPropertyNames().toList()
        .associate { name -> name.lowercase() to (info.getPropertyString(name) ?: "") }

private fun ServiceInfo.toDiscoveredDevice(txt: Map<String, String>): DiscoveredDevice {
    val address = inet4Addresses.firstOrNull()?.hostAddress
        ?: hostAddress
        ?: ""
    return DiscoveredDevice(
        label = txt[ClipboardService.TXT_LABEL]?.takeIf { it.isNotEmpty() } ?: name,
        address = address,
        port = port,
        fingerprintHex = txt[ClipboardService.TXT_FINGERPRINT]?.takeIf { it.isNotEmpty() },
        protocolVersion = txt[ClipboardService.TXT_VERSION]?.toIntOrNull()
            ?: ClipboardService.PROTOCOL_VERSION,
    )
}