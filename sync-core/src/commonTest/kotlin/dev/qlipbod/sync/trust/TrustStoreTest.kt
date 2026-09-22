package dev.qlipbod.sync.trust

import dev.qlipbod.sync.InMemoryTrustStorage
import dev.qlipbod.sync.crypto.Fingerprint
import dev.qlipbod.sync.crypto.Sha256
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Trust is fingerprint-based and network-independent (plan §4). */
class TrustStoreTest {

    private fun fingerprint(seed: String) = Fingerprint.ofDigest(Sha256.digest(seed.encodeToByteArray()))

    @Test
    fun `empty store trusts no one`() {
        val store = TrustStore()
        assertFalse(store.isTrusted(fingerprint("peer-a")))
        assertTrue(store.all().isEmpty())
    }

    @Test
    fun `added peer is trusted and findable by label`() {
        val store = TrustStore()
        val fp = fingerprint("peer-a")
        store.add(fp, "Keres' Pixel")
        assertTrue(store.isTrusted(fp))
        assertNotNull(store.find(fp))
        assertEquals("Keres' Pixel", store.find(fp)?.label)
        assertEquals(1, store.all().size)
    }

    @Test
    fun `removed peer is no longer trusted`() {
        val store = TrustStore()
        val fp = fingerprint("peer-a")
        store.add(fp, "label")
        assertTrue(store.remove(fp))
        assertFalse(store.isTrusted(fp))
        assertNull(store.find(fp))
        assertFalse(store.remove(fp), "second remove must report false")
    }

    @Test
    fun `re-adding same fingerprint replaces the entry`() {
        val store = TrustStore()
        val fp = fingerprint("peer-a")
        store.add(fp, "old label")
        store.add(fp, "new label")
        assertEquals(1, store.all().size)
        assertEquals("new label", store.find(fp)?.label)
    }

    @Test
    fun `store persists through storage round trip`() {
        val storage = InMemoryTrustStorage()
        val store = TrustStore(storage)
        val fp = fingerprint("peer-a")
        store.add(fp, "Keres' Pixel")

        val reloaded = TrustStore(storage)
        assertTrue(reloaded.isTrusted(fp))
        assertEquals("Keres' Pixel", reloaded.find(fp)?.label)
    }

    @Test
    fun `fingerprint rejects malformed hex`() {
        kotlin.test.assertFailsWith<IllegalArgumentException> { Fingerprint("nothex") }
        kotlin.test.assertFailsWith<IllegalArgumentException> {
            Fingerprint("00000000000000000000000000000000") // 32 chars, needs 64
        }
    }
}