package dev.qlipbod.app.linux.daemon

import dev.qlipbod.app.linux.clipboard.ClipboardAdapter
import dev.qlipbod.sync.crypto.Fingerprint
import dev.qlipbod.sync.crypto.Sha256
import dev.qlipbod.sync.discovery.DiscoveredDevice
import dev.qlipbod.sync.discovery.DiscoveryListener
import dev.qlipbod.sync.discovery.DiscoveryService
import dev.qlipbod.sync.history.ClipHistory
import dev.qlipbod.sync.history.HistoryStorage
import dev.qlipbod.sync.identity.DeviceId
import dev.qlipbod.sync.identity.GeneratedIdentity
import dev.qlipbod.sync.identity.LocalIdentity
import dev.qlipbod.sync.protocol.SyncEvent
import dev.qlipbod.sync.trust.TrustStorage
import dev.qlipbod.sync.trust.TrustStore
import dev.qlipbod.sync.trust.TrustedPeer
import java.io.IOException

/**
 * A controllable [DiscoveryService] for tests: [find] pushes a device appearance at the
 * registered listener exactly like a real mDNS browse would.
 */
internal class FakeDiscovery : DiscoveryService {
    private var listener: DiscoveryListener? = null

    /** True once [browse] has registered a listener — awaits before [find] in tests. */
    @Volatile
    var browsing = false
        private set

    override fun advertise(identity: LocalIdentity, port: Int) = Unit
    override fun browse(listener: DiscoveryListener) {
        this.listener = listener
        browsing = true
    }

    override fun close() { listener = null; browsing = false }
    fun find(device: DiscoveredDevice) = listener?.onDeviceFound(device)
}

/** Deterministic clipboard for tests: value + write counter. */
internal class FakeClipboard(var value: String? = null) : ClipboardAdapter {
    var writes = 0
        private set
    override fun read(): String? = value
    override fun write(text: String) {
        writes++
        value = text
    }
}

/** Adapter that behaves like a broken/headless clipboard. */
internal class ThrowingClipboard : ClipboardAdapter {
    override fun read(): String? = throw IOException("no X server")
    override fun write(text: String) = throw IOException("no X server")
}

/** Minimal identity whose fingerprint derives from a stable seed (mirrors sync-core TestSupport). */
internal class TestIdentity(seed: String) : LocalIdentity {
    override val deviceId = DeviceId(seed)
    override val fingerprint = Fingerprint.ofDigest(Sha256.digest("cert-$seed".encodeToByteArray()))
    override val certDer: ByteArray? = null
}

internal class MemTrustStorage : TrustStorage {
    private var peers: List<TrustedPeer> = emptyList()
    override fun load(): List<TrustedPeer> = peers
    override fun save(newPeers: List<TrustedPeer>) { peers = newPeers }
}

internal class MemHistoryStorage : HistoryStorage {
    private var events: List<SyncEvent> = emptyList()
    override fun load(): List<SyncEvent> = events
    override fun save(newEvents: List<SyncEvent>) { events = newEvents }
}

/** A peer object whose fingerprint belongs to the seeded identity. */
internal fun trustedPeer(seed: String): TrustedPeer =
    TrustedPeer(Fingerprint.ofDigest(Sha256.digest("cert-$seed".encodeToByteArray())), seed, addedAtEpochMillis = 0)

/** A fresh trust store trusting exactly [seeds] (separate instance per call). */
internal fun trustFor(vararg seeds: String): TrustStore =
    TrustStore(MemTrustStorage()).also { store -> seeds.forEach { store.add(trustedPeer(it).fingerprint, it) } }

/** A daemon wired with deterministic fakes and the given trust store. */
internal fun newTestDaemon(seed: String, clipboard: ClipboardAdapter, trustStore: TrustStore): SyncDaemon =
    SyncDaemon(
        identity = TestIdentity(seed),
        trustStore = trustStore,
        clock = dev.qlipbod.sync.engine.InMemoryMonotonicClock(),
        history = ClipHistory(capacity = 10, storage = MemHistoryStorage()),
        clipboard = clipboard,
    )

/**
 * Wire-level identities: real RSA self-signed certificates, because the connection
 * handshake presents and verifies actual [dev.qlipbod.sync.identity.LocalIdentity.certDer]
 * bytes (the seed-based [TestIdentity] has no certificate and is for clipboard-plane tests only).
 */
internal fun wireIdentity(seed: String): GeneratedIdentity = GeneratedIdentity.generate(DeviceId(seed))

/** Trust store keyed by the fingerprints of exactly [ids] (separate instance per call). */
internal fun wireTrust(vararg ids: GeneratedIdentity): TrustStore =
    TrustStore(MemTrustStorage()).also { store ->
        ids.forEach { store.add(it.fingerprint, it.deviceId.value) }
    }

/** A daemon holding a real identity, for handshake/stream tests. */
internal fun newWireDaemon(id: GeneratedIdentity, clipboard: ClipboardAdapter, trustStore: TrustStore): SyncDaemon =
    SyncDaemon(
        identity = id,
        trustStore = trustStore,
        clock = dev.qlipbod.sync.engine.InMemoryMonotonicClock(),
        history = ClipHistory(capacity = 10, storage = MemHistoryStorage()),
        clipboard = clipboard,
    )