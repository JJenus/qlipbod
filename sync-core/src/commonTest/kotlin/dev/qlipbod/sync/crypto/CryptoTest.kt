package dev.qlipbod.sync.crypto

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

/**
 * Known-vector tests for the pure-Kotlin crypto primitives.
 * SHA-256 vectors: FIPS 180-4. HMAC-SHA256 vector: RFC 4231 test case 1.
 */
class CryptoTest {

    @Test
    fun `sha256 of empty message matches FIPS vector`() {
        val hex = Sha256.digestHex(byteArrayOf())
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", hex)
    }

    @Test
    fun `sha256 of abc matches FIPS vector`() {
        val hex = Sha256.digestHex("abc".encodeToByteArray())
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", hex)
    }

    @Test
    fun `sha256 digests differ for different inputs`() {
        assertNotEquals(Sha256.digestHex("one".encodeToByteArray()), Sha256.digestHex("two".encodeToByteArray()))
        assertNotEquals(Sha256.digestHex("one".encodeToByteArray()), Sha256.digestHex("two".encodeToByteArray()))
    }

    /** RFC 4231 test case 1: key = 0x0b * 20, data = "Hi There". */
    @Test
    fun `hmac-sha256 matches RFC 4231 test case 1`() {
        val key = ByteArray(20) { 0x0b }
        val data = "Hi There".encodeToByteArray()
        val hex = HmacSha256.digest(key, data).toHex()
        assertEquals("b0344c61d8db38535ca8afceaf0bf12b881dc200c9833da726e9376c2e32cff7", hex)
    }

    @Test
    fun `hex encode decodes round trip`() {
        val original = byteArrayOf(0x00, 0x01, 0x7f, (0xff).toByte(), 0x42)
        assertEquals("00017fff42", original.toHex())
        assertEquals(original.toList(), original.toHex().fromHex().toList())
    }

    @Test
    fun `hex decode rejects malformed input`() {
        assertFailsWith<IllegalArgumentException> { "xyz".fromHex() }
        assertFailsWith<IllegalArgumentException> { "abc".fromHex() } // odd length
    }
}

private fun ByteArray.toHex(): String = Hex.encode(this)
private fun String.fromHex(): ByteArray = Hex.decode(this)