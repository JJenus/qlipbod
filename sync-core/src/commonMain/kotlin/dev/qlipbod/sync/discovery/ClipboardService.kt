package dev.qlipbod.sync.discovery

/**
 * DNS-SD constants shared by every platform so the wire service type matches exactly
 * (plan §3): every device advertises and browses `_clipsync._tcp` over mDNS. TXT
 * records carry the identity *claim* (label + fingerprint) used for the paired/unpaired
 * badge; actual trust is proven later by the connection handshake.
 */
object ClipboardService {
    /** Service type on mDNS (JmDNS/Avahi form). Android's NsdManager uses `_clipsync._tcp.`. */
    const val TYPE = "_clipsync._tcp.local."

    /** Protocol generation shared with the handshake; bumped only on wire-breaking changes. */
    const val PROTOCOL_VERSION = 1

    const val TXT_LABEL = "label"
    const val TXT_VERSION = "ver"
    const val TXT_FINGERPRINT = "fp"

    /** DNS-SD instance name for a device; unique per (host, service type). */
    fun instanceName(deviceId: String): String = "Clipsync-$deviceId"

    /** TXT records an advertiser publishes: label + protocol version + fingerprint hint. */
    fun txt(label: String, fingerprintHex: String): Map<String, String> = mapOf(
        TXT_LABEL to label,
        TXT_VERSION to PROTOCOL_VERSION.toString(),
        TXT_FINGERPRINT to fingerprintHex,
    )
}