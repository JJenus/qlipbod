package dev.qlipbod.app.linux.cli

import dev.qlipbod.app.linux.clipboard.ClipboardAdapter
import dev.qlipbod.app.linux.daemon.DaemonRuntime
import dev.qlipbod.app.linux.daemon.SyncDaemon
import dev.qlipbod.sync.discovery.DiscoveryService
import dev.qlipbod.sync.discovery.DiscoveryStatus
import dev.qlipbod.sync.engine.SystemMonotonicClock
import dev.qlipbod.sync.history.ClipHistory
import dev.qlipbod.sync.identity.GeneratedIdentity
import dev.qlipbod.sync.pairing.PairingFailedException
import dev.qlipbod.sync.pairing.PairingTransport
import dev.qlipbod.sync.storage.FileIdentityStorage
import dev.qlipbod.sync.storage.JsonHistoryStorage
import dev.qlipbod.sync.storage.JsonTrustStorage
import dev.qlipbod.sync.trust.TrustStore
import java.io.File

/**
 * The platform halves the CLI cannot mock on its own: the clipboard and discovery
 * bindings. Tests inject fakes; the real [main] wires `xclip` + JmDNS.
 */
interface QlipbodPlatform {
    fun newClipboard(): ClipboardAdapter
    fun newDiscovery(): DiscoveryService

    /** Release platform resources (e.g. the shared JmDNS instance); called at shutdown. */
    fun close() = Unit
}

/**
 * The daemon's persistent state in a data directory (plan §8): a long-lived identity
 * whose fingerprint survives restarts, the fingerprint-pinned trust store, and the
 * bounded history. Reconstructed unchanged on every boot.
 */
class QlipbodState private constructor(
    val identity: GeneratedIdentity,
    val trust: TrustStore,
    val history: ClipHistory,
) {
    companion object {
        fun at(dataDir: File): QlipbodState {
            val identity = FileIdentityStorage(dataDir).loadOrCreate()
            val trust = TrustStore(JsonTrustStorage(File(dataDir, "trust.json")))
            val history = ClipHistory(capacity = 50, storage = JsonHistoryStorage(File(dataDir, "history.json")))
            return QlipbodState(identity, trust, history)
        }
    }
}

/** The `qlipbod` command surface: parse, dispatch, and interpret each command. */
object QlipbodCli {
    const val EXIT_OK = 0
    const val EXIT_FAILURE = 1

    fun run(
        args: Array<String>,
        platform: QlipbodPlatform,
        out: (String) -> Unit = ::println,
        readPin: () -> String? = defaultPinPrompt,
    ): Int = when (val command = parseCli(args)) {
        is CliCommand.Help -> {
            out(CliUsage)
            EXIT_OK
        }

        is CliCommand.Error -> {
            out("qlipbod: ${command.message}")
            out(CliUsage)
            EXIT_FAILURE
        }

        is CliCommand.Start -> runStart(command, platform, out)
        is CliCommand.Pair -> runPair(command, out, readPin)
    }

    /**
     * Wire up a running [DaemonRuntime] for the `start` command. Exposed for tests so a
     * runtime built exactly like the CLI's can be closed deterministically.
     */
    internal fun buildRuntime(
        command: CliCommand.Start,
        platform: QlipbodPlatform,
        out: (String) -> Unit,
    ): DaemonRuntime {
        val state = QlipbodState.at(command.dataDir)
        val daemon = SyncDaemon(
            identity = state.identity,
            trustStore = state.trust,
            clock = SystemMonotonicClock(),
            history = state.history,
            clipboard = platform.newClipboard(),
        )
        return DaemonRuntime(
            daemon = daemon,
            discovery = platform.newDiscovery(),
            port = command.syncPort,
            pollIntervalMs = command.pollIntervalMs,
            initialDials = command.connect?.let { listOf(DaemonRuntime.HostPort(it.host, it.port)) } ?: emptyList(),
            onStatus = { status -> out("qlipbod: ${status.describe()}") },
            onDialFailure = { address, error ->
                out("qlipbod: could not connect ${address.host}:${address.port} (${error.message}); " +
                    "is the device paired and online? Re-pair with 'qlipbod pair' if unsure.")
            },
        )
    }

    private fun runStart(command: CliCommand.Start, platform: QlipbodPlatform, out: (String) -> Unit): Int {
        val runtime = try {
            buildRuntime(command, platform, out)
        } catch (e: Exception) {
            out("qlipbod: could not start the daemon (${e.message}) — is another qlipbod already running on this port?")
            return EXIT_FAILURE
        }
        runtime.start()
        out("qlipbod: listening for sync on 0.0.0.0:${runtime.boundPort} as " +
            "${runtime.daemon.identity.deviceId.value} (${runtime.daemon.identity.fingerprint.hex})")
        Runtime.getRuntime().addShutdownHook(Thread {
            runtime.close()
            platform.close()
        })
        runtime.awaitTermination()
        return EXIT_OK
    }

    private fun runPair(command: CliCommand.Pair, out: (String) -> Unit, readPin: () -> String?): Int {
        val pin = command.pin ?: readPin()
        if (pin.isNullOrEmpty()) {
            out("qlipbod: no PIN given — pass --pin <pin> or provide it at the prompt")
            return EXIT_FAILURE
        }
        val state = QlipbodState.at(command.dataDir)
        return try {
            val peer = PairingTransport.initiator(command.host, command.pairingPort, state.identity, pin)
            state.trust.add(peer.fingerprint, peer.deviceId.value)
            out("qlipbod: paired with ${peer.deviceId.value} (${peer.fingerprint.hex}). " +
                "The fingerprint is pinned in the trust store; start the daemon to sync.")
            EXIT_OK
        } catch (e: PairingFailedException) {
            out("qlipbod: pairing with ${command.host}:${command.pairingPort} failed: ${e.message}")
            EXIT_FAILURE
        }
    }

    private val defaultPinPrompt: () -> String? = {
        @Suppress("DEPRECATION")
        System.console()?.readPassword("qlipbod PIN: ")?.concatToString()
    }
}

private fun DiscoveryStatus.describe(): String = when (this) {
    is DiscoveryStatus.Paired -> "found paired device ${device.label} (${peer.fingerprint.hex}); connecting"
    is DiscoveryStatus.Unpaired -> "found unpaired device ${device.label}; not connecting (pair it first)"
}