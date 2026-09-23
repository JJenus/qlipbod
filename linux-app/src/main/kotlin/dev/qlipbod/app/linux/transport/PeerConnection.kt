package dev.qlipbod.app.linux.transport

import dev.qlipbod.sync.protocol.SyncEvent
import dev.qlipbod.sync.transport.TcpMessageChannel
import dev.qlipbod.sync.trust.TrustedPeer
import java.net.ServerSocket

/**
 * A live data connection to one [peer], wrapping the core length-prefixed
 * [TcpMessageChannel]. In this slice "authenticated" means the caller *declared* which
 * peer owns this socket (the manual "add by IP" fallback, plan §9); the engine still
 * refuses the peer if its fingerprint is not in the trust store. The fingerprint-verified
 * mTLS handshake replaces the declaration in a later slice.
 */
class PeerConnection private constructor(
    private val channel: TcpMessageChannel,
    val peer: TrustedPeer,
) : AutoCloseable {

    fun send(event: SyncEvent) = channel.send(event)

    override fun close() = channel.close()

    companion object {
        fun dial(host: String, port: Int, peer: TrustedPeer, receiver: (SyncEvent) -> Unit): PeerConnection {
            val channel = TcpMessageChannel.connect(host, port, receiver)
            return PeerConnection(channel, peer)
        }

        /** Blocks until a peer connects; run on a dedicated thread. */
        fun accept(serverSocket: ServerSocket, peer: TrustedPeer, receiver: (SyncEvent) -> Unit): PeerConnection {
            val channel = TcpMessageChannel.accept(serverSocket, receiver)
            return PeerConnection(channel, peer)
        }
    }
}