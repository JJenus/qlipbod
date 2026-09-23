package dev.qlipbod.app.linux.discovery

import dev.qlipbod.app.linux.daemon.SyncDaemon
import dev.qlipbod.sync.discovery.DiscoveredDevice
import dev.qlipbod.sync.discovery.DiscoveryListener
import dev.qlipbod.sync.discovery.DiscoveryService
import dev.qlipbod.sync.discovery.DiscoveryStatus
import dev.qlipbod.sync.discovery.classifyDiscovery

/**
 * The auto-connect half of plan §5: each discovered device is classified against the
 * daemon's trust store by its *advertised* fingerprint. An advertised fingerprint that
 * is already trusted is dialed — and only the daemon's fingerprint-verified handshake
 * (which re-checks the presented certificate) can actually complete a connection.
 * Anything unpaired is surfaced via [onStatus] and never dialed or prompted (§4).
 *
 * If a "paired" advertisement turns out to lie — its certificate is refused by the
 * handshake — the dial fails and the device is re-reported as unpaired: discovery is a
 * hint, the handshake is the verdict.
 *
 * Restartable: a network change (plan §3) closes this and starts a fresh one.
 */
class AutoConnect(
    private val daemon: SyncDaemon,
    private val discovery: DiscoveryService,
    private val onStatus: (DiscoveryStatus) -> Unit = {},
) : AutoCloseable {

    private val listener = object : DiscoveryListener {
        override fun onDeviceFound(device: DiscoveredDevice) {
            val status = classifyDiscovery(device, daemon.trustStore)
            onStatus(status)
            if (status is DiscoveryStatus.Paired) {
                val connected = runCatching { daemon.connectTo(device.address, device.port) }.isSuccess
                if (!connected) onStatus(DiscoveryStatus.Unpaired(device))
            }
        }

        override fun onDeviceLost(device: DiscoveredDevice) = Unit
    }

    /** Begin browsing; safe to call again after [close] to re-arm discovery. */
    fun start(): Unit = discovery.browse(listener)

    override fun close() = discovery.close()
}