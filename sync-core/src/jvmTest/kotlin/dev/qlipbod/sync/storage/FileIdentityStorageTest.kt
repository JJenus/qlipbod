package dev.qlipbod.sync.storage

import dev.qlipbod.sync.identity.GeneratedIdentity
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The daemon's identity must survive restarts: a regenerated identity would present a new
 * fingerprint and orphan every established pairing (plan §4 "long-lived keypair").
 * Save → load must reproduce the exact deviceId, fingerprint, and certificate bytes.
 */
class FileIdentityStorageTest {

    private fun tempDir(prefix: String = "identity"): File = createTempDirectory(prefix).toFile()

    @Test
    fun `save then load reproduces the exact identity`() {
        val storage = FileIdentityStorage(tempDir())
        val original = GeneratedIdentity.generate(dev.qlipbod.sync.identity.DeviceId("laptop"))

        storage.save(original)
        val restored = storage.load()!!

        assertEquals(original.deviceId, restored.deviceId)
        assertEquals(original.fingerprint, restored.fingerprint)
        assertEquals(original.certDer.toList(), restored.certDer.toList())
    }

    @Test
    fun `load returns null when nothing has been stored`() {
        assertNull(FileIdentityStorage(tempDir()).load())
    }

    @Test
    fun `load returns null when the store is corrupt`() {
        val dir = tempDir()
        File(dir, "device-id").writeText("laptop")
        File(dir, "identity-key.der").writeText("not a key")
        File(dir, "identity-cert.der").writeText("not a cert")
        assertNull(FileIdentityStorage(dir).load())
    }

    @Test
    fun `loadOrCreate generates once and reloads the same identity on the next boot`() {
        val dir = tempDir()
        val storage = FileIdentityStorage(dir)

        val first = storage.loadOrCreate()
        val second = storage.loadOrCreate()
        val third = storage.loadOrCreate()

        assertEquals(first.fingerprint, second.fingerprint)
        assertEquals(first.fingerprint, third.fingerprint)
        assertEquals(first.deviceId, second.deviceId)
        assertEquals(listOf("device-id", "identity-cert.der", "identity-key.der"), dir.list()!!.sorted())
    }

    @Test
    fun `save overwrites a previous identity`() {
        val storage = FileIdentityStorage(tempDir())
        val first = storage.loadOrCreate()

        storage.save(GeneratedIdentity.generate(dev.qlipbod.sync.identity.DeviceId("replaced")))
        assertNotEquals(first.fingerprint, storage.load()!!.fingerprint)
        assertTrue(storage.load()!!.certDer.isNotEmpty())
    }
}