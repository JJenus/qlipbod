@file:OptIn(kotlin.ExperimentalUnsignedTypes::class)

package dev.qlipbod.sync.crypto

/**
 * Pure-Kotlin SHA-256 (FIPS 180-4). Deterministic and multiplatform so fingerprints
 * and pairing MACs are byte-identical across Linux and Android without native crypto.
 */
object Sha256 {

    private val K = uintArrayOf(
        0x428a2f98u, 0x71374491u, 0xb5c0fbcfu, 0xe9b5dba5u, 0x3956c25bu, 0x59f111f1u, 0x923f82a4u, 0xab1c5ed5u,
        0xd807aa98u, 0x12835b01u, 0x243185beu, 0x550c7dc3u, 0x72be5d74u, 0x80deb1feu, 0x9bdc06a7u, 0xc19bf174u,
        0xe49b69c1u, 0xefbe4786u, 0x0fc19dc6u, 0x240ca1ccu, 0x2de92c6fu, 0x4a7484aau, 0x5cb0a9dcu, 0x76f988dau,
        0x983e5152u, 0xa831c66du, 0xb00327c8u, 0xbf597fc7u, 0xc6e00bf3u, 0xd5a79147u, 0x06ca6351u, 0x14292967u,
        0x27b70a85u, 0x2e1b2138u, 0x4d2c6dfcu, 0x53380d13u, 0x650a7354u, 0x766a0abbu, 0x81c2c92eu, 0x92722c85u,
        0xa2bfe8a1u, 0xa81a664bu, 0xc24b8b70u, 0xc76c51a3u, 0xd192e819u, 0xd6990624u, 0xf40e3585u, 0x106aa070u,
        0x19a4c116u, 0x1e376c08u, 0x2748774cu, 0x34b0bcb5u, 0x391c0cb3u, 0x4ed8aa4au, 0x5b9cca4fu, 0x682e6ff3u,
        0x748f82eeu, 0x78a5636fu, 0x84c87814u, 0x8cc70208u, 0x90befffau, 0xa4506cebu, 0xbef9a3f7u, 0xc67178f2u,
    )

    fun digest(data: ByteArray): ByteArray {
        val bitLength = data.size.toLong() * 8

        // Padding: 0x80, zero bytes, then a 64-bit big-endian bit length, to a 512-bit multiple.
        val padded = ByteArray(((data.size + 8) / 64 + 1) * 64)
        data.copyInto(padded, 0)
        padded[data.size] = 0x80.toByte()
        for (i in 0 until 8) {
            padded[padded.size - 1 - i] = (bitLength ushr (8 * i)).toByte()
        }

        var h0 = 0x6a09e667u
        var h1 = 0xbb67ae85u
        var h2 = 0x3c6ef372u
        var h3 = 0xa54ff53au
        var h4 = 0x510e527fu
        var h5 = 0x9b05688cu
        var h6 = 0x1f83d9abu
        var h7 = 0x5be0cd19u

        val w = UIntArray(64)
        for (chunk in 0 until padded.size step 64) {
            for (t in 0 until 16) w[t] = bigEndianUInt(padded, chunk + t * 4)
            for (t in 16 until 64) {
                val s0 = rotr(w[t - 15], 7) xor rotr(w[t - 15], 18) xor (w[t - 15] shr 3)
                val s1 = rotr(w[t - 2], 17) xor rotr(w[t - 2], 19) xor (w[t - 2] shr 10)
                w[t] = w[t - 16] + s0 + w[t - 7] + s1
            }

            var a = h0
            var b = h1
            var c = h2
            var d = h3
            var e = h4
            var f = h5
            var g = h6
            var h = h7

            for (t in 0 until 64) {
                val bigS1 = rotr(e, 6) xor rotr(e, 11) xor rotr(e, 25)
                val ch = (e and f) xor (e.inv() and g)
                val temp1 = h + bigS1 + ch + K[t] + w[t]
                val bigS0 = rotr(a, 2) xor rotr(a, 13) xor rotr(a, 22)
                val maj = (a and b) xor (a and c) xor (b and c)
                val temp2 = bigS0 + maj

                h = g
                g = f
                f = e
                e = d + temp1
                d = c
                c = b
                b = a
                a = temp1 + temp2
            }

            h0 += a
            h1 += b
            h2 += c
            h3 += d
            h4 += e
            h5 += f
            h6 += g
            h7 += h
        }

        return byteArrayOf(
            (h0 shr 24).toByte(), (h0 shr 16).toByte(), (h0 shr 8).toByte(), h0.toByte(),
            (h1 shr 24).toByte(), (h1 shr 16).toByte(), (h1 shr 8).toByte(), h1.toByte(),
            (h2 shr 24).toByte(), (h2 shr 16).toByte(), (h2 shr 8).toByte(), h2.toByte(),
            (h3 shr 24).toByte(), (h3 shr 16).toByte(), (h3 shr 8).toByte(), h3.toByte(),
            (h4 shr 24).toByte(), (h4 shr 16).toByte(), (h4 shr 8).toByte(), h4.toByte(),
            (h5 shr 24).toByte(), (h5 shr 16).toByte(), (h5 shr 8).toByte(), h5.toByte(),
            (h6 shr 24).toByte(), (h6 shr 16).toByte(), (h6 shr 8).toByte(), h6.toByte(),
            (h7 shr 24).toByte(), (h7 shr 16).toByte(), (h7 shr 8).toByte(), h7.toByte(),
        )
    }

    fun digestHex(data: ByteArray): String = Hex.encode(digest(data))

    private fun bigEndianUInt(bytes: ByteArray, offset: Int): UInt =
        ((bytes[offset].toUInt() and 0xffu) shl 24) or
            ((bytes[offset + 1].toUInt() and 0xffu) shl 16) or
            ((bytes[offset + 2].toUInt() and 0xffu) shl 8) or
            (bytes[offset + 3].toUInt() and 0xffu)

    private fun rotr(x: UInt, n: Int): UInt = (x shr n) or (x shl (32 - n))
}