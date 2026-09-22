package dev.qlipbod.sync.history

import dev.qlipbod.sync.InMemoryHistoryStorage
import dev.qlipbod.sync.identity.DeviceId
import dev.qlipbod.sync.protocol.EventType
import dev.qlipbod.sync.protocol.SyncEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Clip history is the bounded, deduplicated record of synced clips (see plan §7/§12). */
class ClipHistoryTest {

    private fun event(origin: String, seq: Long, payload: String = "p$origin-$seq") = SyncEvent(
        origin = DeviceId(origin),
        sequence = seq,
        payload = payload,
    )

    @Test
    fun `history is bounded by capacity with fifo eviction`() {
        val history = ClipHistory(capacity = 3)
        history.record(event("a", 1))
        history.record(event("a", 2))
        history.record(event("a", 3))
        history.record(event("a", 4))

        assertEquals(3, history.size())
        assertFalse(history.contains(DeviceId("a"), 1), "oldest entry must be evicted")
        assertTrue(history.contains(DeviceId("a"), 4))
    }

    @Test
    fun `duplicate event is not recorded twice`() {
        val history = ClipHistory(capacity = 10)
        val e = event("a", 5)
        assertTrue(history.record(e))
        assertFalse(history.record(e), "same (origin, seq) must dedupe")
        assertEquals(1, history.size())
    }

    @Test
    fun `recent returns newest first`() {
        val history = ClipHistory(capacity = 10)
        history.record(event("a", 1))
        history.record(event("b", 2))
        assertEquals(2, history.recent().size)
        assertEquals(DeviceId("b"), history.recent()[0].origin)
        assertEquals(2L, history.recent()[0].sequence)
    }

    @Test
    fun `history persists through storage round trip`() {
        val storage = InMemoryHistoryStorage()
        val history = ClipHistory(capacity = 5, storage = storage)
        history.record(event("a", 1))
        history.record(event("b", 2))

        val reloaded = ClipHistory(capacity = 5, storage = storage)
        assertEquals(2, reloaded.size())
        assertTrue(reloaded.contains(DeviceId("b"), 2))
        assertEquals(listOf("b", "a"), reloaded.recent().map { it.origin.value })
    }

    @Test
    fun `record returns false when storage-backed and duplicate`() {
        val storage = InMemoryHistoryStorage()
        val history = ClipHistory(capacity = 5, storage = storage)
        val e = event("a", 1)
        history.record(e)
        assertFalse(history.record(e))
    }
}