package dev.qlipbod.sync.transport

import dev.qlipbod.sync.protocol.FrameCodec
import dev.qlipbod.sync.protocol.FrameDecoder
import dev.qlipbod.sync.protocol.SyncEvent
import java.io.BufferedInputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Blocking TCP channel with length-prefixed framing. One daemon reader thread per
 * connection; sends are serialized under a lock. TLS wraps this in a later phase —
 * identity and trust are already pinned by [dev.qlipbod.sync.trust.TrustStore].
 */
class TcpMessageChannel private constructor(
    private val socket: Socket,
    private val receiver: (SyncEvent) -> Unit,
) : AutoCloseable {

    private val closed = AtomicBoolean(false)
    private val writeLock = Any()
    private val output: OutputStream = socket.getOutputStream()

    init {
        Thread({ readLoop() }, "qlipbod-sync-reader").apply {
            isDaemon = true
            start()
        }
    }

    private fun readLoop() {
        try {
            val input = BufferedInputStream(socket.getInputStream(), 8192)
            val decoder = FrameDecoder()
            val buffer = ByteArray(8192)
            while (true) {
                val n = input.read(buffer)
                if (n == -1) break
                decoder.push(buffer.copyOf(n)).forEach(receiver)
            }
        } catch (_: Exception) {
            // Connection reset or malformed frame kills this channel only; the daemon
            // layer decides whether to reconnect. Framing defects are already rejected.
        } finally {
            closeInternal()
        }
    }

    fun send(event: SyncEvent) {
        check(!closed.get()) { "channel is closed" }
        val frame = FrameCodec.encodeFrame(event)
        synchronized(writeLock) {
            output.write(frame)
            output.flush()
        }
    }

    override fun close() = closeInternal()

    private fun closeInternal() {
        if (closed.compareAndSet(false, true)) {
            runCatching { socket.close() }
        }
    }

    companion object {
        /** Blocks until a peer connects (run on a dedicated thread). */
        fun accept(serverSocket: ServerSocket, receiver: (SyncEvent) -> Unit): TcpMessageChannel =
            TcpMessageChannel(serverSocket.accept(), receiver)

        fun connect(host: String, port: Int, receiver: (SyncEvent) -> Unit = {}): TcpMessageChannel =
            TcpMessageChannel(Socket(host, port), receiver)
    }
}