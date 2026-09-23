package dev.qlipbod.app.linux.cli

import dev.qlipbod.app.linux.clipboard.XClipClipboard
import dev.qlipbod.sync.discovery.DiscoveryListener
import dev.qlipbod.sync.discovery.DiscoveryService
import dev.qlipbod.sync.discovery.JmDnsDiscovery
import dev.qlipbod.sync.identity.LocalIdentity
import javax.jmdns.JmDNS
import kotlin.system.exitProcess

/**
 * Real (non-test) platform bindings for QlipbodCli: the X11 clipboard via `xclip` and
 * mDNS discovery via JmDNS. If mDNS cannot start (for example a host with no multicast
 * interface), discovery degrades to [NoopDiscovery] — the daemon still polls the
 * clipboard and accepts inbound dials, and the `--connect` manual fallback still works.
 */
fun main(args: Array<String>) {
    val platform = object : QlipbodPlatform {
        private var jmdns: JmDNS? = null

        override fun newClipboard() = XClipClipboard()

        override fun newDiscovery(): DiscoveryService =
            runCatching {
                JmDNS.create().also { jmdns = it }
            }.map { JmDnsDiscovery(it) }
                .getOrElse { e ->
                    System.err.println("qlipbod: mDNS unavailable (${e.message}); continuing without discovery")
                    NoopDiscovery
                }

        override fun close() {
            jmdns?.let { runCatching { it.close() } } // JmDNS threads are non-daemon; stop them at exit
            jmdns = null
        }
    }
    exitProcess(
        QlipbodCli.run(args, platform).also { System.out.flush() }, // stdout to a pipe/file is buffered; don't drop banners
    )
}

/** Discovery that does nothing — used when mDNS is unavailable (headless/no multicast). */
object NoopDiscovery : DiscoveryService {
    override fun advertise(identity: LocalIdentity, port: Int) = Unit
    override fun browse(listener: DiscoveryListener) = Unit
    override fun close() = Unit
}