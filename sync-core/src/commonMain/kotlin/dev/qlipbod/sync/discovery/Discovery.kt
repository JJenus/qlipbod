package dev.qlipbod.sync.discovery

import dev.qlipbod.sync.crypto.Fingerprint
import dev.qlipbod.sync.identity.LocalIdentity
import dev.qlipbod.sync.trust.TrustStore
import dev.qlipbod.sync.trust.TrustedPeer

/**
 * A device seen on the local network. Discovery answers "what is out there and where";
 * it never answers "is it trustworthy". [fingerprintHex] is only ever the *advertised*
 * hint used to badge a device as paired/unpaired — the fingerprint-verified connection
 * handshake remains the sole authority on trust (plan §3-§5).
 */
data class DiscoveredDevice(
    val label: String,
    val address: String,
    val port: Int,
    val fingerprintHex: String? = null,
    val protocolVersion: Int = ClipboardService.PROTOCOL_VERSION,
)

/** Receives devices as they appear and disappear on the network. */
fun interface DiscoveryListener {
    /** A device appeared (or re-appeared). Trust is decided later, at the handshake. */
    fun onDeviceFound(device: DiscoveredDevice)

    /** A device stopped responding; drop it from any live list. */
    fun onDeviceLost(device: DiscoveredDevice) = Unit
}

/**
 * The discovery contract every platform binds behind one shape (plan §3): Linux via
 * Avahi/mDNS (JVM: [dev.qlipbod.sync.discovery.JmDnsDiscovery]), Android via
 * NsdManager — same service type, same model. Network changes must restart
 * [advertise]/[browse], so implementations are restartable per instance.
 */
interface DiscoveryService {
    /** Advertise this device as a clip-sync target listening on [port]. */
    fun advertise(identity: LocalIdentity, port: Int)

    /** Start browsing; each device appearance is delivered exactly once, until lost. */
    fun browse(listener: DiscoveryListener)

    /** Stop advertising and browsing; release sockets so discovery can be restarted. */
    fun close()
}

/** A classified discovery: what the daemon may do with a found device. */
sealed interface DiscoveryStatus {
    /** Advertised fingerprint is already trusted — safe to auto-connect (the handshake re-verifies). */
    data class Paired(val device: DiscoveredDevice, val peer: TrustedPeer) : DiscoveryStatus

    /** Not trustworthy from what is advertised; never auto-connect, never auto-prompt (§4). */
    data class Unpaired(val device: DiscoveredDevice) : DiscoveryStatus
}

/**
 * Plan §5 badge logic: an advertised fingerprint found in [trustStore] means the device
 * may be dialed; anything else — including a missing or malformed fingerprint — means
 * "unpaired". This is a hint, not a verdict: the connection handshake always re-checks
 * the presented certificate against the trust store, so a lying advertisement still
 * fails there.
 */
fun classifyDiscovery(device: DiscoveredDevice, trustStore: TrustStore): DiscoveryStatus {
    val peer = device.fingerprintHex
        ?.let { runCatching { Fingerprint(it) }.getOrNull() }
        ?.let { trustStore.find(it) }
    return if (peer != null) DiscoveryStatus.Paired(device, peer)
    else DiscoveryStatus.Unpaired(device)
}