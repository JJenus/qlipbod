package dev.qlipbod.app.linux.daemon

import dev.qlipbod.app.linux.transport.PeerConnection
import java.net.ServerSocket
import kotlin.concurrent.thread

/**
 * Shared wiring helpers for the loopback daemon tests (plan §11.1): poll until a
 * clipboard/history condition holds, and connect two daemons whose verified handshakes
 * must both resolve. Kept in one place so the plain stream test and the pair-flow test
 * exercise the identical transport plumbing.
 */

/** Polls until [condition] or fails after [timeoutMs]. */
internal fun await(timeoutMs: Long = 5_000, condition: () -> Boolean) {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (!condition()) {
        check(System.currentTimeMillis() < deadline) { "timed out awaiting condition" }
        Thread.sleep(10)
    }
}

/** Wires [daemonA] (listener) and [daemonB] (dialer) over loopback; both handshakes must resolve. */
internal fun link(daemonA: SyncDaemon, daemonB: SyncDaemon): PeerConnection {
    val server = ServerSocket(0)
    val accept = thread(name = "test-accept") { daemonA.acceptOn(server) }
    val connB = daemonB.connectTo("127.0.0.1", server.localPort)
    accept.join()
    return connB
}