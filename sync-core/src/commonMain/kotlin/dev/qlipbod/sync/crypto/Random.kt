package dev.qlipbod.sync.crypto

import kotlin.random.Random

internal fun randomBytes(byteCount: Int): ByteArray =
    ByteArray(byteCount).also { Random.Default.nextBytes(it) }

internal fun randomHex(byteCount: Int): String = Hex.encode(randomBytes(byteCount))