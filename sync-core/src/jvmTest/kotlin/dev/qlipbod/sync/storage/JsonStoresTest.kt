package dev.qlipbod.sync.storage

import dev.qlipbod.sync.TestIdentity
import dev.qlipbod.sync.crypto.Fingerprint
import dev.qlipbod.sync.crypto.Sha256
import dev.qlipbod.sync.identity.DeviceId
import dev.qlipbod.sync.protocol.SyncEvent
import dev.qlipbod.sync.trust.TrustedPeer
import dev.qlipbod.sync.trust.TrustStore
import dev.qlipbod.sync.history.ClipHistory
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class JsonStoresTest {

    private fun tempFile(name: String): File =
        File.createTempFile("qlipbod-test-$name", ".json").apply { deleteOnExit() }

    private fun fp(seed: String) = Fingerprint.ofDigest(Sha256.digest(seed.encodeToByteArray()))

    @Test
    fun `trust store persists to disk and reloads`() {
        val file = tempFile("trust")
        val store = TrustStore(JsonTrustStorage(file))
        val peer = store.add(fp("peer-a"), "Keres' Pixel")

        val reloaded = TrustStore(JsonTrustStorage(file))
        assertTrue(reloaded.isTrusted(peer.fingerprint))
        assertEquals("Keres' Pixel", reloaded.find(peer.fingerprint)?.label)
    }

    @Test
    fun `corrupt trust file loads as empty store instead of crashing`() {
        val file = tempFile("corrupt")
        file.writeText("{not json at all")

        val store = TrustStore(JsonTrustStorage(file))
        assertEquals(0, store.all().size)
    }

    @Test
    fun `history persists to disk and reloads in order`() {
        val file = tempFile("history")
        val history = ClipHistory(capacity = 50, storage = JsonHistoryStorage(file))
        history.record(SyncEvent(origin = DeviceId("a"), sequence = 1, payload = "first"))
        history.record(SyncEvent(origin = DeviceId("b"), sequence = 1, payload = "second"))

        val reloaded = ClipHistory(capacity = 50, storage = JsonHistoryStorage(file))
        assertEquals(2, reloaded.size())
        assertEquals(listOf("second", "first"), reloaded.recent().map { it.payload })
    }

    @Test
    fun `corrupt history file loads as empty history instead of crashing`() {
        val file = tempFile("corrupt-history")
        file.writeText("garbage!!!")
        assertEquals(0, ClipHistory(storage = JsonHistoryStorage(file)).size())
    }
}