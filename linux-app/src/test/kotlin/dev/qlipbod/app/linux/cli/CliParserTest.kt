package dev.qlipbod.app.linux.cli

import dev.qlipbod.sync.QlipbodDefaults
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/** The pure argument grammar of the `qlipbod` CLI — every branch, no sockets. */
class CliParserTest {

    @Test
    fun `no arguments is help`() {
        assertIs<CliCommand.Help>(parseCli(arrayOf()))
    }

    @Test
    fun `help flags are help`() {
        assertIs<CliCommand.Help>(parseCli(arrayOf("help")))
        assertIs<CliCommand.Help>(parseCli(arrayOf("--help")))
        assertIs<CliCommand.Help>(parseCli(arrayOf("-h")))
    }

    @Test
    fun `unknown command is an error`() {
        val error = assertIs<CliCommand.Error>(parseCli(arrayOf("frobnicate")))
        assertEquals("unknown command 'frobnicate'", error.message)
    }

    @Test
    fun `start uses wire defaults`() {
        val start = assertIs<CliCommand.Start>(parseCli(arrayOf("start")))
        assertEquals(QlipbodDefaults.SYNC_PORT, start.syncPort)
        assertEquals(QlipbodDefaults.PAIRING_PORT, start.pairingPort)
        assertEquals(500L, start.pollIntervalMs)
        assertEquals(File(System.getProperty("user.home"), ".local/share/qlipbod"), start.dataDir)
        assertNull(start.connect)
    }

    @Test
    fun `start parses every option`() {
        val start = assertIs<CliCommand.Start>(
            parseCli(
                arrayOf(
                    "start", "--port", "5000", "--pairing-port", "5100", "--poll-ms", "200",
                    "--data-dir", "/tmp/qlipbod-state", "--connect", "192.168.1.20:5555",
                ),
            ),
        )
        assertEquals(5000, start.syncPort)
        assertEquals(5100, start.pairingPort)
        assertEquals(200L, start.pollIntervalMs)
        assertEquals(File("/tmp/qlipbod-state"), start.dataDir)
        assertEquals(HostAndPort("192.168.1.20", 5555), start.connect)
    }

    @Test
    fun `connect without a port defaults to the sync port`() {
        val start = assertIs<CliCommand.Start>(parseCli(arrayOf("start", "--connect", "192.168.1.20")))
        assertEquals(HostAndPort("192.168.1.20", QlipbodDefaults.SYNC_PORT), start.connect)
    }

    @Test
    fun `start rejects a non-numeric port`() {
        val error = assertIs<CliCommand.Error>(parseCli(arrayOf("start", "--port", "not-a-port")))
        assertEquals("--port expects a port 1-65535, got 'not-a-port'", error.message)
    }

    @Test
    fun `start rejects an out-of-range port`() {
        val error = assertIs<CliCommand.Error>(parseCli(arrayOf("start", "--port", "70000")))
        assertEquals("--port expects a port 1-65535, got '70000'", error.message)
    }

    @Test
    fun `start rejects an option missing its value`() {
        val error = assertIs<CliCommand.Error>(parseCli(arrayOf("start", "--data-dir")))
        assertEquals("--data-dir needs a value", error.message)
    }

    @Test
    fun `start rejects an unknown option`() {
        val error = assertIs<CliCommand.Error>(parseCli(arrayOf("start", "--bogus")))
        assertEquals("start: unknown option '--bogus'", error.message)
    }

    @Test
    fun `pair parses host pin and pairing port`() {
        val pair = assertIs<CliCommand.Pair>(
            parseCli(arrayOf("pair", "192.168.1.20", "--pin", "2468", "--pairing-port", "6000")),
        )
        assertEquals("192.168.1.20", pair.host)
        assertEquals("2468", pair.pin)
        assertEquals(6000, pair.pairingPort)
    }

    @Test
    fun `pair without a pin means prompt`() {
        val pair = assertIs<CliCommand.Pair>(parseCli(arrayOf("pair", "192.168.1.20")))
        assertEquals("192.168.1.20", pair.host)
        assertNull(pair.pin)
    }

    @Test
    fun `pair rejects a missing host`() {
        val error = assertIs<CliCommand.Error>(parseCli(arrayOf("pair", "--pin", "2468")))
        assertEquals("pair expects a host address, e.g. 'pair 192.168.1.20'", error.message)
    }

    @Test
    fun `pair rejects an unknown option`() {
        val error = assertIs<CliCommand.Error>(parseCli(arrayOf("pair", "192.168.1.20", "--bogus")))
        assertEquals("pair: unknown option '--bogus'", error.message)
    }
}