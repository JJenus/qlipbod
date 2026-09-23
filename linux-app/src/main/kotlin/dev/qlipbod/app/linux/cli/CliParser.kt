package dev.qlipbod.app.linux.cli

import dev.qlipbod.sync.QlipbodDefaults
import java.io.File

/**
 * A parsed, validated invocation of the `qlipbod` command. Parsing is pure and
 * side-effect free so every branch can be unit-tested without touching sockets.
 */
sealed interface CliCommand {
    /** Run the daemon: poll clipboard, accept dials, advertise + browse, auto-connect. */
    data class Start(
        val syncPort: Int = QlipbodDefaults.SYNC_PORT,
        val pairingPort: Int = QlipbodDefaults.PAIRING_PORT,
        val pollIntervalMs: Long = 500,
        val dataDir: File = defaultDataDir(),
        /** Manual "add by IP" fallback (plan §9): dial this host once at startup. */
        val connect: HostAndPort? = null,
    ) : CliCommand

    /** Pair with a device at [host] using the same PIN shown on both sides (§4, §9). */
    data class Pair(
        val host: String,
        /** null means "prompt on the terminal" (or fail when there is no terminal). */
        val pin: String? = null,
        val pairingPort: Int = QlipbodDefaults.PAIRING_PORT,
        val dataDir: File = defaultDataDir(),
    ) : CliCommand

    /** Show usage, or the command was invoked with no arguments. */
    data object Help : CliCommand

    /** Unparseable invocation; [message] is shown before the usage text. */
    data class Error(val message: String) : CliCommand
}

/** A host and port pair, e.g. from `--connect 192.168.1.20:4343`. */
data class HostAndPort(val host: String, val port: Int)

/** XDG-style default state directory: `~/.local/share/qlipbod`. */
fun defaultDataDir(): File = File(System.getProperty("user.home", "."), ".local/share/qlipbod")

/** Parse [args] into a [CliCommand]; never throws — every failure is an [CliCommand.Error]. */
fun parseCli(args: Array<String>): CliCommand = try {
    when {
        args.isEmpty() -> CliCommand.Help
        args[0] == "help" || args[0] == "--help" || args[0] == "-h" -> CliCommand.Help
        args[0] == "start" -> parseStart(args.drop(1))
        args[0] == "pair" -> parsePair(args.drop(1))
        else -> throw CliError("unknown command '${args[0]}'")
    }
} catch (e: CliError) {
    CliCommand.Error(e.message ?: "invalid arguments")
}

private class CliError(message: String) : Exception(message)

private fun parseStart(args: List<String>): CliCommand {
    var syncPort = QlipbodDefaults.SYNC_PORT
    var pairingPort = QlipbodDefaults.PAIRING_PORT
    var pollMs = 500L
    var dataDir = defaultDataDir()
    var connect: HostAndPort? = null
    val tokens = args.iterator()
    while (tokens.hasNext()) {
        when (val flag = tokens.next()) {
            "--port" -> syncPort = requirePort(tokens, flag)
            "--pairing-port" -> pairingPort = requirePort(tokens, flag)
            "--poll-ms" -> pollMs = requirePositiveLong(tokens, flag)
            "--data-dir" -> dataDir = File(requireValue(tokens, flag))
            "--connect" -> connect = requireHostAndPort(requireValue(tokens, flag))
            else -> throw CliError("start: unknown option '$flag'")
        }
    }
    return CliCommand.Start(syncPort, pairingPort, pollMs, dataDir, connect)
}

private fun parsePair(args: List<String>): CliCommand {
    val host = args.firstOrNull()?.takeUnless { it.startsWith("--") }
        ?: throw CliError("pair expects a host address, e.g. 'pair 192.168.1.20'")
    var pin: String? = null
    var pairingPort = QlipbodDefaults.PAIRING_PORT
    var dataDir = defaultDataDir()
    val tokens = args.drop(1).iterator()
    while (tokens.hasNext()) {
        when (val flag = tokens.next()) {
            "--pin" -> pin = requireValue(tokens, flag)
            "--pairing-port" -> pairingPort = requirePort(tokens, flag)
            "--data-dir" -> dataDir = File(requireValue(tokens, flag))
            else -> throw CliError("pair: unknown option '$flag'")
        }
    }
    return CliCommand.Pair(host, pin, pairingPort, dataDir)
}

private fun requireValue(tokens: Iterator<String>, flag: String): String {
    if (!tokens.hasNext()) throw CliError("$flag needs a value")
    return tokens.next()
}

private fun requirePort(tokens: Iterator<String>, flag: String): Int {
    val raw = requireValue(tokens, flag)
    val port = raw.toIntOrNull()
    if (port == null || port !in 1..65535) throw CliError("$flag expects a port 1-65535, got '$raw'")
    return port
}

private fun requirePositiveLong(tokens: Iterator<String>, flag: String): Long {
    val raw = requireValue(tokens, flag)
    val value = raw.toLongOrNull()
    if (value == null || value <= 0) throw CliError("$flag expects a positive number, got '$raw'")
    return value
}

private fun requireHostAndPort(raw: String): HostAndPort {
    if (raw.isBlank()) throw CliError("--connect expects HOST or HOST:PORT")
    val colon = raw.lastIndexOf(':')
    if (colon >= 0) {
        val host = raw.substring(0, colon)
        val port = raw.substring(colon + 1).toIntOrNull()
        if (host.isNotEmpty() && port != null && port in 1..65535) return HostAndPort(host, port)
        throw CliError("--connect expects HOST or HOST:PORT, got '$raw'")
    }
    return HostAndPort(raw, QlipbodDefaults.SYNC_PORT)
}

val CliUsage = """
qlipbod — LAN clipboard sync daemon

usage:
  qlipbod start [--port N] [--pairing-port N] [--poll-ms N] [--data-dir DIR]
                [--connect HOST[:PORT]]
  qlipbod pair <HOST> [--pin PIN] [--pairing-port N] [--data-dir DIR]
  qlipbod help

commands:
  start    Run the daemon: watch the clipboard, accept paired connections,
           advertise + browse mDNS, and auto-connect paired devices found on
           the network. --connect dials one specific host at boot instead —
           the plan §9 "add by IP" fallback when mDNS is blocked.
  pair     Pair with a device at HOST using the same PIN shown on both sides.
           The confirmed fingerprint is pinned in the trust store.
  help     Show this help.

options:
  --port N           Sync data port (default ${QlipbodDefaults.SYNC_PORT})
  --pairing-port N   One-time pairing port (default ${QlipbodDefaults.PAIRING_PORT})
  --poll-ms N        Clipboard poll interval in ms (default 500)
  --data-dir DIR     State: identity, trust store, history (default ~/.local/share/qlipbod)
  --connect HOST[:PORT]  Dial HOST once at startup (manual add-by-IP)
  --pin PIN          Pairing PIN (omit to be prompted)
""".trimIndent()