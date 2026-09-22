package dev.qlipbod.sync

import dev.qlipbod.sync.crypto.Fingerprint
import dev.qlipbod.sync.crypto.Sha256
import dev.qlipbod.sync.engine.EngineListener
import dev.qlipbod.sync.engine.EventSink
import dev.qlipbod.sync.engine.InMemoryMonotonicClock
import dev.qlipbod.sync.engine.SyncEngine
import dev.qlipbod.sync.history.ClipHistory
import dev.qlipbod.sync.history.HistoryStorage
import dev.qlipbod.sync.identity.DeviceId
import dev.qlipbod.sync.identity.LocalIdentity
import dev.qlipbod.sync.protocol.SyncEvent
import dev.qlipbod.sync.trust.TrustedPeer
import dev.qlipbod.sync.trust.TrustStorage
import dev.qlipbod.sync.trust.TrustStore

/** Minimal in-memory identity for tests; fingerprint derives from a fixed seed. */
class TestIdentity(
    override val deviceId: DeviceId,
    seed: String = deviceId.value,
) : LocalIdentity {
    private val der = "cert-$seed".encodeToByteArray()
    override val certDer: ByteArray = der
    override val fingerprint: Fingerprint = Fingerprint.ofDigest(Sha256.digest(der))
}

class InMemoryTrustStorage : TrustStorage {
    private var peers: List<TrustedPeer> = emptyList()
    override fun load(): List<TrustedPeer> = peers
    override fun save(newPeers: List<TrustedPeer>) { peers = newPeers }
}

class InMemoryHistoryStorage : HistoryStorage {
    private var events: List<SyncEvent> = emptyList()
    override fun load(): List<SyncEvent> = events
    override fun save(newEvents: List<SyncEvent>) { events = newEvents }
}

/** Records every engine interaction for assertions. */
class RecordingListener : EngineListener {
    val applied = mutableListOf<SyncEvent>()
    val broadcasted = mutableListOf<SyncEvent>()
    val rejected = mutableListOf<TrustedPeer>()
    override fun onClipApplied(event: SyncEvent) { applied += event }
    override fun onBroadcast(event: SyncEvent) { broadcasted += event }
    override fun onRejected(peer: TrustedPeer, reason: String) { rejected += peer }
}

/** An [EventSink] whose target engine is wired after construction (breaks the circular dependency). */
class DeferredSink : EventSink {
    var target: SyncEngine? = null
    var trustedAs: TrustedPeer? = null
    val sent = mutableListOf<SyncEvent>()
    override fun send(event: SyncEvent) {
        sent += event
        target?.onPeerMessage(trustedAs!!, event)
    }
}
