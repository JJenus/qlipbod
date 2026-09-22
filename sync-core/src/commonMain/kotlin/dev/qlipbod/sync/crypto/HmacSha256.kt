package dev.qlipbod.sync.crypto

/** HMAC-SHA256 (RFC 2104) on top of the pure-Kotlin [Sha256]. */
object HmacSha256 {
    private const val BLOCK_SIZE = 64
    private const val IPAD = 0x36
    private const val OPAD = 0x5c

    fun digest(key: ByteArray, data: ByteArray): ByteArray {
        val normalized = if (key.size > BLOCK_SIZE) Sha256.digest(key) else key
        val block = ByteArray(BLOCK_SIZE)
        normalized.copyInto(block, 0)

        val inner = Sha256.digest(ByteArray(BLOCK_SIZE) { (block[it].toInt() xor IPAD).toByte() } + data)
        return Sha256.digest(ByteArray(BLOCK_SIZE) { (block[it].toInt() xor OPAD).toByte() } + inner)
    }

    fun digestHex(key: ByteArray, data: ByteArray): String = Hex.encode(digest(key, data))
}