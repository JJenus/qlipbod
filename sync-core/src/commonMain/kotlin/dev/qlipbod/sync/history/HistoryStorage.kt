package dev.qlipbod.sync.history

import dev.qlipbod.sync.protocol.SyncEvent

/** Persistence seam for clip history. */
interface HistoryStorage {
    fun load(): List<SyncEvent>
    fun save(events: List<SyncEvent>)
}