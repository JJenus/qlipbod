package dev.qlipbod.sync.transport

import dev.qlipbod.sync.protocol.SyncEvent

/** Minimal outbound transport seam the engine plugs into (TCP today; TLS wrapper later). */
interface MessageChannel {
    fun send(event: SyncEvent)
}