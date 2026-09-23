package dev.qlipbod.app.linux.daemon

import dev.qlipbod.app.linux.clipboard.ClipboardAdapter
import dev.qlipbod.sync.engine.EngineListener
import dev.qlipbod.sync.engine.EventSink
import dev.qlipbod.sync.engine.MonotonicClock
import dev.qlipbod.sync.engine.SyncEngine
import dev.qlipbod.sync.history.ClipHistory
import dev.qlipbod.sync.identity.LocalIdentity
import dev.qlipbod.sync.protocol.SyncEvent
import dev.qlipbod.sync.trust.TrustStore

/**
 * Composition root of the Linux daemon's clipboard plane (plan §8, §9, §11.1).
 *
 * Owns the [SyncEngine] ↔ [ClipboardAdapter] wiring:
 * - a clipboard change surfaced by [poll] is treated as a *user copy* and fed to the
 *   engine (`onLocalClipChanged`) — broadcast once, monotonically sequenced;
 * - an engine `onClipApplied` is written to the adapter, and the resulting OS-level
 *   clipboard echo is remembered so the next poll suppresses it (host-side loop
 *   prevention, mirroring the engine's `fromNetwork` flag);
 * - content already on the clipboard at boot is never re-synced.
 *
 * Transport, discovery, and the pairing UI are composed around this class in later
 * slices; nothing here is platform-specific beyond [ClipboardAdapter].
 */
class SyncDaemon(
    identity: LocalIdentity,
    trustStore: TrustStore,
    clock: MonotonicClock,
    history: ClipHistory,
    eventSink: EventSink,
    private val clipboard: ClipboardAdapter,
) : AutoCloseable {

    /** The engine this daemon drives; exposed for transport wiring and observability. */
    val engine = SyncEngine(
        identity = identity,
        trustStore = trustStore,
        clock = clock,
        history = history,
        eventSink = eventSink,
        listener = object : EngineListener {
            override fun onClipApplied(event: SyncEvent) {
                lastWritten = event.payload
                clipboard.write(event.payload)
            }
        },
    )

    /** Clipboard contents the daemon last accounted for (boot state or last successful poll). */
    var lastSeen: String? = null
        private set

    /** Our own most recent network-applied write, used to suppress the echo. */
    private var lastWritten: String? = null

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

    override fun close() {
        // Transports that attach in later slices register their own teardown here.
    }

    private fun readClipboard(): String? = runCatching { clipboard.read() }.getOrNull()
}