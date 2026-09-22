package dev.qlipbod.sync.crypto

/** Canonical lowercase-hex encoding shared by fingerprints, MACs, and pairing fields. */
object Hex {
    private const val DIGITS = "0123456789abcdef"

    fun encode(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val v = b.toInt() and 0xff
            sb.append(DIGITS[v ushr 4]).append(DIGITS[v and 0x0f])
        }
        return sb.toString()
    }

    fun decode(hex: String): ByteArray {
        val cleaned = hex.lowercase()
        require(cleaned.length % 2 == 0) { "hex string must have even length" }
        val out = ByteArray(cleaned.length / 2)
        for (i in out.indices) {
            out[i] = ((digit(cleaned[i * 2]) shl 4) or digit(cleaned[i * 2 + 1])).toByte()
        }
        return out
    }

    private fun digit(c: Char): Int = when (c) {
        in '0'..'9' -> c - '0'
        in 'a'..'f' -> c - 'a' + 10
        else -> throw IllegalArgumentException("invalid hex character: $c")
    }
}