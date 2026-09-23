package dev.qlipbod.sync.transport

import dev.qlipbod.sync.identity.DeviceId
import dev.qlipbod.sync.protocol.SyncEvent
import java.net.ServerSocket
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Round-trips framed events over a real loopback TCP connection. */
class TcpMessageChannelTest {

    private fun event(seq: Long, payload: String) = SyncEvent(
        origin = DeviceId("peer"),
        sequence = seq,
        payload = payload,
    )

    @Test
    fun `events arrive in order over loopback`() {
        val server = ServerSocket(0).apply { reuseAddress = true }
        val received = mutableListOf<SyncEvent>()

        val serverThread = Thread {
            val channel = TcpMessageChannel.accept(server) { received.add(it) }
            try {
                Thread.sleep(2000)
            } finally {
                channel.close()
            }
        }
        serverThread.isDaemon = true
        serverThread.start()

        val client = TcpMessageChannel.connect("127.0.0.1", server.localPort)
        try {
            client.send(event(1, "alpha"))
            client.send(event(2, "beta"))
            client.send(event(3, "gamma"))

            val deadline = System.currentTimeMillis() + 5000
            while (received.size < 3 && System.currentTimeMillis() < deadline) {
                Thread.sleep(20)
            }
            assertEquals(listOf("alpha", "beta", "gamma"), received.map { it.payload })
        } finally {
            client.close()
            server.close()
        }
    }

    @Test
    fun `large payload survives framing end to end`() {
        val server = ServerSocket(0)
        val received = mutableListOf<SyncEvent>()
        val serverThread = Thread {
            val channel = TcpMessageChannel.accept(server) { received.add(it) }
            try {
                Thread.sleep(2000)
            } finally {
                channel.close()
            }
        }
        serverThread.isDaemon = true
        serverThread.start()

        val big = "x".repeat(500_000)
        val client = TcpMessageChannel.connect("127.0.0.1", server.localPort)
        try {
            client.send(event(1, big))
            val deadline = System.currentTimeMillis() + 5000
            while (received.isEmpty() && System.currentTimeMillis() < deadline) {
                Thread.sleep(20)
            }
            assertEquals(1, received.size)
            assertTrue(received[0].payload.length == 500_000, "payload must be intact")
        } finally {
            client.close()
            server.close()
        }
    }

    @Test
    fun `attach wraps an already-connected socket`() {
        val server = ServerSocket(0)
        val received = mutableListOf<SyncEvent>()
        val serverThread = Thread {
            val socket = server.accept()
            val channel = TcpMessageChannel.attach(socket) { received.add(it) }
            try {
                Thread.sleep(1000)
            } finally {
                channel.close()
            }
        }
        serverThread.isDaemon = true
        serverThread.start()

        val client = TcpMessageChannel.connect("127.0.0.1", server.localPort)
        try {
            client.send(event(1, "via-attach"))
            val deadline = System.currentTimeMillis() + 5000
            while (received.isEmpty() && System.currentTimeMillis() < deadline) {
                Thread.sleep(20)
            }
            assertEquals(listOf("via-attach"), received.map { it.payload })
        } finally {
            client.close()
            server.close()
        }
    }
}