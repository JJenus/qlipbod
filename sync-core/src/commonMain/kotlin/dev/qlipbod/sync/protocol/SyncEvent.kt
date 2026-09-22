package dev.qlipbod.sync.protocol

import dev.qlipbod.sync.identity.DeviceId
import kotlinx.serialization.Serializable

@Serializable
enum class EventType { CLIP }

@Serializable
enum class ContentType { TEXT }

/**
 * The canonical wire message for v1 (plan §7, §11.5). The [origin]/[sequence] pair is a
 * logical clock — never a wall-clock timestamp — so near-simultaneous copies across
 * devices with clock drift still order deterministically.
 *
 * Structured as a generic event envelope so file/notification types can ride the same
 * transport later without a protocol change.
 */
@Serializable
data class SyncEvent(
    val type: EventType = EventType.CLIP,
    val origin: DeviceId,
    val sequence: Long,
    val contentType: ContentType = ContentType.TEXT,
    val payload: String,
    /** Mirrors Android's EXTRA_IS_SENSITIVE: excluded from sync by default (§9). */
    val sensitive: Boolean = false,
    /** Informational only — never used for ordering. */
    val sentAtMillis: Long? = null,
) {
    /** Deterministic total-order key for LWW conflict resolution: (origin, sequence). */
    fun totalOrderKey(): String = "${origin.value}|${sequence.toString().padStart(20, '0')}"
}