package dev.qlipbod.sync.trust

/** Persistence seam for the trust store; platform-specific (JSON file, Android prefs, ...). */
interface TrustStorage {
    fun load(): List<TrustedPeer>
    fun save(peers: List<TrustedPeer>)
}