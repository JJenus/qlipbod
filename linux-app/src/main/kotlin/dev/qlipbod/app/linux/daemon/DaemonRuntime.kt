package dev.qlipbod.app.linux.daemon

import dev.qlipbod.app.linux.discovery.AutoConnect
import dev.qlipbod.sync.QlipbodDefaults
import dev.qlipbod.sync.discovery.DiscoveryService
import dev.qlipbod.sync.discovery.DiscoveryStatus
import java.net.ServerSocket
import java.net.SocketException
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

/**
 * The long-running composition behind the `qlipbod start` command (plan §8). One thread
 * polls the clipboard, one thread accepts inbound verified connections on the sync port,
 * and discovery + [AutoConnect] dial paired devices as they appear. `--connect` dials are
 * [initialDials]: the manual "add by IP" fallback from plan §9, used when mDNS is blocked
 * (hotspot/guest network isolation).
 *
 * All loops stop and every resource is released on [close]; the accept loop stays blocked
 * in `accept()` until the server socket is closed, so shutdown never relies on timeouts.
 */
class DaemonRuntime(
    /** The daemon being driven; exposed so hosts can report identity in banners/status. */
    val daemon: SyncDaemon,
    private val discovery: DiscoveryService,
    port: Int = QlipbodDefaults.SYNC_PORT,
    private val pollIntervalMs: Long = 500,
    private val initialDials: List<HostPort> = emptyList(),
    private val onStatus: (DiscoveryStatus) -> Unit = {},
    private val onDialFailure: (HostPort, Throwable) -> Unit = { _, _ -> },
) : AutoCloseable {

    /** A concrete dial target for the manual add-by-IP fallback. */
    data class HostPort(val host: String, val port: Int)

    private val server = ServerSocket(port)
    private val threads = CopyOnWriteArrayList<Thread>()
    private val autoConnect = AutoConnect(daemon, discovery, onStatus)
    @Volatile private var closed = false
    private var started = false

    /** The port actually bound (useful when constructed with port = 0). */
    val boundPort: Int get() = server.localPort

    init {
        discovery.advertise(daemon.identity, server.localPort)
    }

    /** Start all loops and begin browsing. Idempotent-guarded; safe to call once. */
    fun start() {
        check(!started) { "runtime already started" }
        started = true
        threads += thread(name = "qlipbod-poll") { pollLoop() }
        threads += thread(name = "qlipbod-accept") { acceptLoop() }
        // Browsing must never block startup: the real binder (JmDNS) synchronously lists
        // pre-announced services, which can take seconds on a quiet network. It runs on
        // a daemon thread so a shutdown mid-listing is never held up either.
        threads += thread(name = "qlipbod-browse", isDaemon = true) { autoConnect.start() }
        initialDials.forEach { address ->
            threads += thread(name = "qlipbod-dial-$address") { dialOnce(address) }
        }
    }

    /** Block the calling thread until the runtime is closed (used by the real CLI main). */
    fun awaitTermination() {
        while (!closed) {
            try {
                Thread.sleep(100)
            } catch (e: InterruptedException) {
                return
            }
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        autoConnect.close()
        runCatching { server.close() } // unblocks the accept loop
        threads.forEach { runCatching { it.join(2_000) } }
        threads.clear()
        daemon.close()
    }

    private fun pollLoop() {
        while (!closed) {
            daemon.poll()
            try {
                Thread.sleep(pollIntervalMs)
            } catch (e: InterruptedException) {
                return
            }
        }
    }

    private fun acceptLoop() {
        while (!closed) {
            try {
                daemon.acceptOn(server)
            } catch (e: SocketException) {
                if (closed) return
                // Transient (e.g. an SYN-flood-ish reset); keep listening.
            } catch (e: Exception) {
                if (closed) return
                // A refused handshake (untrusted peer) must never kill the accept loop;
                // the reason is already recorded in the daemon's rejectedLog.
            }
        }
    }

    private fun dialOnce(address: HostPort) {
        val result = runCatching { daemon.connectTo(address.host, address.port) }
        if (result.isFailure) onDialFailure(address, result.exceptionOrNull()!!)
    }
}