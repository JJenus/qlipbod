package dev.qlipbod.sync.trust

import dev.qlipbod.sync.crypto.Fingerprint

/**
 * Fingerprint-keyed store of trusted peers. All identity decisions answer one question:
 * "does the certificate presented match a stored fingerprint?" — independent of network,
 * IP, or hostname (plan §4). Mutations persist through the optional [TrustStorage].
 */
class TrustStore(private val storage: TrustStorage? = null) {

    private val peers = LinkedHashMap<String, TrustedPeer>()

    init {
        storage?.load()?.forEach { peers[it.fingerprint.hex] = it }
    }

    fun add(peer: TrustedPeer): TrustedPeer {
        peers[peer.fingerprint.hex] = peer
        persist()
        return peer
    }

    fun add(fingerprint: Fingerprint, label: String, addedAtEpochMillis: Long = 0L): TrustedPeer =
        add(TrustedPeer(fingerprint, label, addedAtEpochMillis))

    fun remove(fingerprint: Fingerprint): Boolean {
        val removed = peers.remove(fingerprint.hex) != null
        if (removed) persist()
        return removed
    }

    fun isTrusted(fingerprint: Fingerprint): Boolean = peers.containsKey(fingerprint.hex)

    fun find(fingerprint: Fingerprint): TrustedPeer? = peers[fingerprint.hex]

    fun all(): List<TrustedPeer> = peers.values.toList()

    private fun persist() {
        storage?.save(all())
    }
}