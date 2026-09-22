package dev.qlipbod.sync.history

import dev.qlipbod.sync.identity.DeviceId
import dev.qlipbod.sync.protocol.SyncEvent

/**
 * Bounded, deduplicated record of synced clips (plan §7, §12).
 *
 * - Capacity 50 by default, FIFO eviction (the "recommended" depth chosen for v1).
 * - Deduplicated by (origin, sequence) so bounce-backs and re-received events
 *   never create duplicates.
 * - Keeping the *loser* of a conflict in history ensures nothing is silently lost.
 */
class ClipHistory(
    private val capacity: Int = 50,
    private val storage: HistoryStorage? = null,
) {
    // Oldest first. ArrayDeque is the multiplatform-idiomatic ring-buffer backbone.
    private val events = ArrayDeque<SyncEvent>()

    init {
        storage?.load()?.forEach { events.addLast(it) }
    }

    /** @return false when the event was already present (deduplicated). */
    fun record(event: SyncEvent): Boolean {
        if (contains(event.origin, event.sequence)) return false
        events.addLast(event)
        while (events.size > capacity) events.removeFirst()
        storage?.save(events.toList())
        return true
    }

    /** Newest first. */
    fun recent(limit: Int = capacity): List<SyncEvent> = events.toList().asReversed().take(limit)

    fun contains(origin: DeviceId, sequence: Long): Boolean =
        events.any { it.origin == origin && it.sequence == sequence }

    fun size(): Int = events.size
}