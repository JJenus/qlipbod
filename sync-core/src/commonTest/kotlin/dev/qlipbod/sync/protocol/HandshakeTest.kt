package dev.qlipbod.sync.protocol

import dev.qlipbod.sync.TestIdentity
import dev.qlipbod.sync.identity.DeviceId
import dev.qlipbod.sync.trust.TrustStore
import dev.qlipbod.sync.trust.TrustedPeer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Unit contract for the mutual certificate handshake (plan §4, §6): the presented
 * certificate round-trips through a Hello frame and resolves to exactly the trusted
 * peer carrying that fingerprint; anything unpaired or malformed is refused with a
 * typed error. Socket-level framing is exercised end to end by the Linux daemon tests.
 */
class HandshakeTest {

    private fun trust(vararg peers: Pair<TestIdentity, String>): TrustStore =
        TrustStore().also { store -> peers.forEach { (id, label) -> store.add(id.fingerprint, label) } }

    /** Strip the 4-byte frame header to get the Hello body for [HandshakeProtocol.decodeHello]. */
    private fun helloBody(identity: TestIdentity): ByteArray {
        val frame = HandshakeProtocol.encodeHello(identity)
        return frame.copyOfRange(FrameCodec.HEADER_BYTES, frame.size)
    }

    @Test
    fun `presented certificate resolves to the trusted peer carrying its fingerprint`() {
        val phone = TestIdentity(DeviceId("phone"))
        val laptop = TestIdentity(DeviceId("laptop"))

        val hello = HandshakeProtocol.decodeHello(helloBody(phone))
        assertEquals(phone.deviceId, hello.deviceId)
        assertEquals(1, hello.protocolVersion)

        val resolved: TrustedPeer = HandshakeProtocol.resolvePeer(hello, trust(laptop to "laptop", phone to "phone"))
        assertEquals(phone.fingerprint, resolved.fingerprint)
        assertEquals("phone", resolved.label)
    }

    @Test
    fun `certificate from an unpaired key is refused`() {
        val stranger = TestIdentity(DeviceId("stranger"))
        val laptop = TestIdentity(DeviceId("laptop"))

        val hello = HandshakeProtocol.decodeHello(helloBody(stranger))
        assertFailsWith<UnverifiedPeerException> { HandshakeProtocol.resolvePeer(hello, trust(laptop to "laptop")) }
    }

    @Test
    fun `malformed hello body is refused with a handshake error`() {
        assertFailsWith<HandshakeException> {
            HandshakeProtocol.decodeHello("{not json".encodeToByteArray())
        }
    }

    @Test
    fun `non-hex certificate is refused`() {
        val laptop = TestIdentity(DeviceId("laptop"))
        val hello = HandshakeProtocol.decodeHello(helloBody(laptop)).copy(certDerHex = "zz-not-hex-zz")

        assertFailsWith<HandshakeException> { HandshakeProtocol.resolvePeer(hello, trust(laptop to "laptop")) }
    }
}