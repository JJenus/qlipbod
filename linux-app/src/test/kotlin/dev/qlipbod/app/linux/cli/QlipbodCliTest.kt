package dev.qlipbod.app.linux.cli

import dev.qlipbod.sync.clipboard.ClipboardAdapter
import dev.qlipbod.app.linux.daemon.FakeClipboard
import dev.qlipbod.app.linux.daemon.FakeDiscovery
import dev.qlipbod.app.linux.daemon.await
import dev.qlipbod.app.linux.daemon.newWireDaemon
import dev.qlipbod.app.linux.daemon.wireIdentity
import dev.qlipbod.sync.discovery.DiscoveredDevice
import dev.qlipbod.sync.discovery.DiscoveryService
import dev.qlipbod.sync.pairing.PairingTransport
import dev.qlipbod.sync.storage.JsonTrustStorage
import dev.qlipbod.sync.trust.TrustStore
import java.io.File
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The `qlipbod` command surface, exercised end to end (pairing, state, daemon wiring). */
class QlipbodCliTest {

    private fun tempDir(): File = createTempDirectory("qlipbod-cli").toFile()

    private class FakePlatform(
        val clipboard: ClipboardAdapter = FakeClipboard(),
        val discovery: DiscoveryService = FakeDiscovery(),
    ) : QlipbodPlatform {
        override fun newClipboard() = clipboard
        override fun newDiscovery() = discovery
        override fun close() = Unit
    }

    @Test
    fun `help prints usage and succeeds`() {
        val out = mutableListOf<String>()
        assertEquals(QlipbodCli.EXIT_OK, QlipbodCli.run(arrayOf("help"), FakePlatform(), out = { out += it }))
        assertTrue(out.joinToString().contains("usage:"))
    }

    @Test
    fun `an unknown command prints an error and fails`() {
        val out = mutableListOf<String>()
        assertEquals(QlipbodCli.EXIT_FAILURE, QlipbodCli.run(arrayOf("bogus"), FakePlatform(), out = { out += it }))
        assertTrue(out.joinToString().contains("unknown command 'bogus'"))
        assertTrue(out.joinToString().contains("usage:"))
    }

    @Test
    fun `pair with a matching pin pins the confirmed fingerprint in the trust store and succeeds`() {
        val dataDir = tempDir()
        val phone = wireIdentity("phone")
        val pin = "2468"
        val pairServer = ServerSocket(0)
        val responderErrors = CopyOnWriteArrayList<Throwable>()
        val responder = thread(name = "test-pair-responder") {
            try { PairingTransport.responder(pairServer, phone, pin) } catch (e: Throwable) { responderErrors += e }
        }

        val out = mutableListOf<String>()
        val code = QlipbodCli.run(
            arrayOf(
                "pair", "127.0.0.1", "--pin", pin,
                "--pairing-port", pairServer.localPort.toString(),
                "--data-dir", dataDir.path,
            ),
            FakePlatform(),
            out = { out += it },
        )
        responder.join(5_000)
        pairServer.close()

        assertEquals(QlipbodCli.EXIT_OK, code)
        assertEquals(emptyList<Throwable>(), responderErrors, "the responder side must also succeed")
        assertTrue(out.joinToString().contains("paired with phone"), "banner mentions the peer: $out")

        val persisted = TrustStore(JsonTrustStorage(File(dataDir, "trust.json")))
        assertTrue(persisted.isTrusted(phone.fingerprint), "the confirmed fingerprint is pinned on disk")
    }

    @Test
    fun `pair with a mismatched pin fails and leaves the trust store untouched`() {
        val dataDir = tempDir()
        val phone = wireIdentity("phone")
        val pairServer = ServerSocket(0)
        val responderErrors = CopyOnWriteArrayList<Throwable>()
        val responder = thread(name = "test-pair-responder") {
            try { PairingTransport.responder(pairServer, phone, "2468") } catch (e: Throwable) { responderErrors += e }
        }

        val out = mutableListOf<String>()
        val code = QlipbodCli.run(
            arrayOf(
                "pair", "127.0.0.1", "--pin", "9999",
                "--pairing-port", pairServer.localPort.toString(),
                "--data-dir", dataDir.path,
            ),
            FakePlatform(),
            out = { out += it },
        )
        responder.join(5_000)
        pairServer.close()

        assertEquals(QlipbodCli.EXIT_FAILURE, code)
        assertTrue(out.joinToString().contains("failed"), "reports the failure: $out")
        val persisted = TrustStore(JsonTrustStorage(File(dataDir, "trust.json")))
        assertTrue(persisted.all().isEmpty(), "nothing may be trusted from a failed pairing")
    }

    @Test
    fun `state persists identity and trust across boots`() {
        val dataDir = tempDir()
        val boot1 = QlipbodState.at(dataDir)
        val phone = wireIdentity("phone")
        boot1.trust.add(phone.fingerprint, "phone")

        val boot2 = QlipbodState.at(dataDir)
        assertEquals(boot1.identity.fingerprint, boot2.identity.fingerprint, "identity survives restart")
        assertEquals(boot1.identity.deviceId, boot2.identity.deviceId)
        assertEquals("phone", boot2.trust.find(phone.fingerprint)?.label, "trust survives restart")
    }

    @Test
    fun `pair then start auto-connects the paired device over discovery`() {
        val dataDir = tempDir()
        val pin = "2468"

        // The laptop identity the CLI will generate and persist on `start`.
        val laptop = QlipbodState.at(dataDir).identity

        // The phone (its own daemon) answers the pairing exchange, then a TCP accept.
        val phoneId = wireIdentity("phone")
        val phoneClip = FakeClipboard()
        val laptopFp = laptop.fingerprint
        val phoneTrust = TrustStore(dev.qlipbod.app.linux.daemon.MemTrustStorage()).also { it.add(laptopFp, "laptop") }
        val phone = newWireDaemon(phoneId, phoneClip, phoneTrust)

        // 1. Run `pair` through the CLI: the phone responder presents the same PIN.
        val pairServer = ServerSocket(0)
        val responder = thread(name = "test-pair-responder") {
            runCatching { PairingTransport.responder(pairServer, phoneId, pin) }
        }
        val pairOut = mutableListOf<String>()
        assertEquals(
            QlipbodCli.EXIT_OK,
            QlipbodCli.run(
                arrayOf(
                    "pair", "127.0.0.1", "--pin", pin,
                    "--pairing-port", pairServer.localPort.toString(),
                    "--data-dir", dataDir.path,
                ),
                FakePlatform(),
                out = { pairOut += it },
            ),
        )
        responder.join(5_000)
        pairServer.close()

        // 2. Start the daemon exactly as the CLI would, using the same data dir.
        val laptopClip = FakeClipboard()
        val discovery = FakeDiscovery()
        val platform = FakePlatform(clipboard = laptopClip, discovery = discovery)
        val runtime = QlipbodCli.buildRuntime(
            CliCommand.Start(syncPort = 0, pollIntervalMs = 20, dataDir = dataDir),
            platform,
            out = { },
        )
        runtime.start()

        // 3. The phone appears on discovery → the paired badge auto-connects it.
        val phoneServer = ServerSocket(0)
        val acceptErrors = CopyOnWriteArrayList<Throwable>()
        val accept = thread(name = "test-phone-accept") {
            try { phone.acceptOn(phoneServer) } catch (e: Throwable) { acceptErrors += e }
        }
        await { discovery.browsing } // browse runs on its own thread inside the runtime
        discovery.find(
            DiscoveredDevice(
                label = "phone", address = "127.0.0.1", port = phoneServer.localPort,
                fingerprintHex = phoneId.fingerprint.hex,
            ),
        )
        accept.join(5_000)
        assertEquals(emptyList<Throwable>(), acceptErrors, "the phone-side handshake must succeed")

        laptopClip.value = "via the cli"
        await { phoneClip.value == "via the cli" }
        assertEquals("via the cli", phoneClip.value, "clip flows end to end from a CLI-paired device")

        runtime.close()
        phoneServer.close()
    }
}