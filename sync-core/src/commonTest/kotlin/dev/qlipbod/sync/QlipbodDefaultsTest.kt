package dev.qlipbod.sync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The shared wire defaults (ports) are part of the cross-platform contract; pin them. */
class QlipbodDefaultsTest {

    @Test
    fun `default ports are valid and distinct`() {
        assertTrue(QlipbodDefaults.SYNC_PORT in 1..65535)
        assertTrue(QlipbodDefaults.PAIRING_PORT in 1..65535)
        assertTrue(QlipbodDefaults.SYNC_PORT != QlipbodDefaults.PAIRING_PORT)
    }

    @Test
    fun `the sync and pairing ports have stable values`() {
        assertEquals(4343, QlipbodDefaults.SYNC_PORT)
        assertEquals(4344, QlipbodDefaults.PAIRING_PORT)
    }
}