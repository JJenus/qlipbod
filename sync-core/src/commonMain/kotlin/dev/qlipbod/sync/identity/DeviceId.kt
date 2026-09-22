package dev.qlipbod.sync.identity

import dev.qlipbod.sync.crypto.randomHex
import kotlinx.serialization.Serializable

/** Opaque, unique device identifier. Never derived from network or hostname. */
@Serializable
@JvmInline
value class DeviceId(val value: String) {
    override fun toString(): String = value

    companion object {
        fun random(): DeviceId = DeviceId(randomHex(16))
    }
}