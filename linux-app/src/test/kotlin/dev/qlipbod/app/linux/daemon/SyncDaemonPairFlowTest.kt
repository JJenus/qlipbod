package dev.qlipbod.app.linux.daemon

import dev.qlipbod.sync.pairing.PairingPeer
import dev.qlipbod.sync.pairing.PairingTransport
import java.net.ServerSocket
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The product promise end to end (plan §11.1): pair once over TCP with a shared PIN,
 * persist each side's confirmed fingerprint into its trust store, then the ordinary
 * fingerprint-verified data handshake connects the devices — and clips flow both ways
 * with no loops. Pairing and data traffic stay on separate connections.
 */
class SyncDaemonPairFlowTest {

    @Test
    fun `pair once with a PIN, then clips flow over the verified data channel`() {
        val laptopId = wireIdentity("laptop")
        val phoneId = wireIdentity("phone")
        val pin = "1357"

        // 1. Pair over TCP. The phone answers, the laptop dials; both need the shared PIN.
        val server = ServerSocket(0)
        val phonePeerResult = CompletableFuture<PairingPeer>()
        val responder = thread(name = "pair-responder") {
            phonePeerResult.complete(PairingTransport.responder(server, phoneId, pin))
        }
        val laptopPeer = PairingTransport.initiator("127.0.0.1", server.localPort, laptopId, pin)
        val phonePeer = phonePeerResult.get(10, TimeUnit.SECONDS)
        responder.join()

        // 2. Each side persists the counterpart's confirmed, PIN-proven fingerprint.
        val laptopTrust = wireTrust(laptopId).also { it.add(laptopPeer.fingerprint, laptopPeer.deviceId.value) }
        val phoneTrust = wireTrust(phoneId).also { it.add(phonePeer.fingerprint, phonePeer.deviceId.value) }

        // 3. The verified data handshake connects them with no extra ceremony.
        val laptopClip = FakeClipboard()
        val phoneClip = FakeClipboard()
        val laptop = newWireDaemon(laptopId, laptopClip, laptopTrust)
        val phone = newWireDaemon(phoneId, phoneClip, phoneTrust)
        val conn = link(laptop, phone)

        // Laptop copies -> phone applies.
        laptopClip.value = "copied on the laptop"
        assertTrue(laptop.poll())
        await { phoneClip.value == "copied on the laptop" }
        assertEquals("copied on the laptop", phoneClip.value)

        // Phone copies -> laptop applies.
        phoneClip.value = "copied on the phone"
        assertTrue(phone.poll())
        await { laptopClip.value == "copied on the phone" }
        assertEquals("copied on the phone", laptopClip.value)

        // Exactly one broadcast per device — no loops.
        assertEquals(1, laptop.broadcastLog.size)
        assertEquals(1, phone.broadcastLog.size)

        conn.close()
        laptop.close()
        phone.close()
    }
}