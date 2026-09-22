package dev.qlipbod.sync.trust

import dev.qlipbod.sync.crypto.Fingerprint
import kotlinx.serialization.Serializable

/** A device this host has paired with. The [fingerprint] is the whole trust story. */
@Serializable
data class TrustedPeer(
    val fingerprint: Fingerprint,
    val label: String,
    val addedAtEpochMillis: Long,
)