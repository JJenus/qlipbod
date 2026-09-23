package dev.qlipbod.app.linux.transport

import dev.qlipbod.sync.identity.LocalIdentity
import dev.qlipbod.sync.protocol.HandshakeProtocol
import dev.qlipbod.sync.protocol.SyncEvent
import dev.qlipbod.sync.protocol.readHelloFrame
import dev.qlipbod.sync.transport.TcpMessageChannel
import dev.qlipbod.sync.trust.TrustStore
import dev.qlipbod.sync.trust.TrustedPeer
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.net.ServerSocket
import java.net.Socket

/**
 * A live data connection to one trusted peer, established over a mutual certificate
 * handshake (plan §4, §6, §10; protocol logic lives in sync-core's [HandshakeProtocol]).
 * Both sides present their [LocalIdentity] certificate on connect; each side hashes the
 * presented certificate and resolves the peer as whoever it proves to be — a caller can
 * no longer declare a peer, and unpaired devices are refused at the handshake, before
 * any sync event is read. The [peer] is therefore the *verified* identity for the life
 * of the connection.
 */
class PeerConnection private constructor(
    private val channel: TcpMessageChannel,
    val peer: TrustedPeer,
) : AutoCloseable {

    fun send(event: SyncEvent) = channel.send(event)

    override fun close() = channel.close()

    companion object {
        /**
         * Connect to [host]:[port], present our certificate, and verify + resolve the peer.
         * Throws [dev.qlipbod.sync.protocol.HandshakeException] /
         * [dev.qlipbod.sync.protocol.UnverifiedPeerException] when the other side is absent,
         * malformed, or untrusted.
         */
        fun dial(
            host: String,
            port: Int,
            identity: LocalIdentity,
            trustStore: TrustStore,
            receiver: (TrustedPeer, SyncEvent) -> Unit,
        ): PeerConnection = authenticate(Socket(host, port), identity, trustStore, receiver)

        /**
         * Block until a peer connects on [serverSocket], then run the same mutual handshake.
         * Call on a dedicated thread.
         */
        fun accept(
            serverSocket: ServerSocket,
            identity: LocalIdentity,
            trustStore: TrustStore,
            receiver: (TrustedPeer, SyncEvent) -> Unit,
        ): PeerConnection = authenticate(serverSocket.accept(), identity, trustStore, receiver)

        private fun authenticate(
            socket: Socket,
            identity: LocalIdentity,
            trustStore: TrustStore,
            receiver: (TrustedPeer, SyncEvent) -> Unit,
        ): PeerConnection {
            try {
                socket.soTimeout = HANDSHAKE_TIMEOUT_MS
                val input = DataInputStream(BufferedInputStream(socket.getInputStream(), 8192))
                val output = socket.getOutputStream()

                // Write-first avoids a deadlock window: both sides fully present themselves,
                // then read. Small messages never fill a TCP send buffer.
                output.write(HandshakeProtocol.encodeHello(identity))
                output.flush()
                val peer = HandshakeProtocol.resolvePeer(readHelloFrame(input), trustStore)

                socket.soTimeout = 0
                val channel = TcpMessageChannel.attach(socket) { event -> receiver(peer, event) }
                return PeerConnection(channel, peer)
            } catch (e: Exception) {
                runCatching { socket.close() }
                throw e
            }
        }

        private const val HANDSHAKE_TIMEOUT_MS = 10_000
    }
}