package dev.qlipbod.app.linux.daemon

import dev.qlipbod.app.linux.clipboard.ClipboardAdapter
import dev.qlipbod.app.linux.transport.PeerConnection
import dev.qlipbod.sync.engine.EngineListener
import dev.qlipbod.sync.engine.EventSink
import dev.qlipbod.sync.engine.MonotonicClock
import dev.qlipbod.sync.engine.SyncEngine
import dev.qlipbod.sync.history.ClipHistory
import dev.qlipbod.sync.identity.LocalIdentity
import dev.qlipbod.sync.protocol.SyncEvent
import dev.qlipbod.sync.trust.TrustedPeer
import dev.qlipbod.sync.trust.TrustStore
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Composition root of the Linux daemon (plan §8, §9, §11.1). Owns the wiring between
 * the [SyncEngine], the [ClipboardAdapter], and [PeerConnection]s:
 *
 * - a clipboard change surfaced by [poll] is a *user copy* → engine `onLocalClipChanged` —
 *   broadcast once, monotonically sequenced;
 * - an engine `onClipApplied` is written to the adapter, and the resulting OS-level
 *   clipboard echo is suppressed by the next poll (host-side loop prevention);
 * - engine broadcasts fan out to every attached [PeerConnection]; a dead channel only
 *   costs that peer, never the others;
 * - content already on the clipboard at boot is never re-synced;
 * - connections are authenticated by a mutual certificate exchange: a device that cannot
 *   prove a trusted fingerprint is refused at the handshake, before any event is read
 *   (plan §4 "never trust by network membership"; §9 "unpaired, not an error").
 *
 * Threading note: the engine is not internally synchronized — one poll thread plus one
 * reader thread per connection is the intended shape; cross-thread history access is
 * safe in that single-writer-per-source regime.
 */
class SyncDaemon(
    private val identity: LocalIdentity,
    val trustStore: TrustStore,
    clock: MonotonicClock,
    history: ClipHistory,
    private val clipboard: ClipboardAdapter,
) : AutoCloseable {

    private val connections = CopyOnWriteArrayList<PeerConnection>()

    /** The engine this daemon drives, exposed for observability and future pairing flows. */
    val engine = SyncEngine(
        identity = identity,
        trustStore = trustStore,
        clock = clock,
        history = history,
        eventSink = EventSink { event ->
            connections.forEach { runCatching { it.send(event) } }
        },
        listener = object : EngineListener {
            override fun onClipApplied(event: SyncEvent) {
                lastWritten = event.payload
                clipboard.write(event.payload)
            }

            override fun onBroadcast(event: SyncEvent) {
                broadcastLog += event
            }

            override fun onRejected(peer: TrustedPeer, reason: String) {
                rejectedLog += peer to reason
            }
        },
    )

    /** Every broadcast the engine handed to the transport, in order. */
    val broadcastLog = mutableListOf<SyncEvent>()

    /** Every message-layer refusal, as (peer as declared on the connection, reason). */
    val rejectedLog = mutableListOf<Pair<TrustedPeer, String>>()

    /** Clipboard contents the daemon last accounted for (boot state or last successful poll). */
    var lastSeen: String? = null
        private set

    /** Our own most recent network-applied write, used to suppress the OS echo. */
    private var lastWritten: String? = null

    private var closed = false

    init {
        // Do not re-sync whatever is in the clipboard when the daemon starts.
        lastSeen = readClipboard()
    }

    /**
     * Check the clipboard for a user copy.
     *
     * @return true when a *new user copy* was found and handed to the engine.
     */
    fun poll(): Boolean {
        val value = readClipboard() ?: return false
        if (value == lastSeen) return false
        val echoOfOurOwnWrite = value == lastWritten
        lastSeen = value
        if (echoOfOurOwnWrite) return false // the OS echoed our own apply; never rebroadcast (§9)
        engine.onLocalClipChanged(value)
        return true
    }

    /**
     * Dial a peer and authenticate it: both sides exchange certificates and the peer is
     * resolved from the trust store by fingerprint, so only a paired device can talk.
     */
    fun connectTo(host: String, port: Int): PeerConnection =
        attach(PeerConnection.dial(host, port, identity, trustStore) { peer, event ->
            engine.onPeerMessage(peer, event)
        })

    /** Block until a peer connects on [serverSocket], then authenticate it. Run on a dedicated thread. */
    fun acceptOn(serverSocket: ServerSocket): PeerConnection =
        attach(PeerConnection.accept(serverSocket, identity, trustStore) { peer, event ->
            engine.onPeerMessage(peer, event)
        })

    private fun attach(connection: PeerConnection): PeerConnection {
        check(!closed) { "daemon is closed" }
        connections += connection
        return connection
    }

    override fun close() {
        closed = true
        connections.forEach { runCatching { it.close() } }
        connections.clear()
    }

    private fun readClipboard(): String? = runCatching { clipboard.read() }.getOrNull()
}