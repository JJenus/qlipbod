package dev.qlipbod.sync.storage

import dev.qlipbod.sync.history.HistoryStorage
import dev.qlipbod.sync.protocol.SyncEvent
import dev.qlipbod.sync.trust.TrustedPeer
import dev.qlipbod.sync.trust.TrustStorage
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

/**
 * JSON-file-backed persistence. A corrupt or missing file degrades to an empty store
 * rather than crashing the daemon — losing your trust store on a bad write is bad,
 * but not boot-looping is worse; the peer simply re-pairs.
 */
private val json = Json {
    encodeDefaults = true
    ignoreUnknownKeys = true
}

private val trustListSerializer = ListSerializer(TrustedPeer.serializer())
private val eventListSerializer = ListSerializer(SyncEvent.serializer())

class JsonTrustStorage(private val file: File) : TrustStorage {

    override fun load(): List<TrustedPeer> = runCatching {
        json.decodeFromString(trustListSerializer, file.readText())
    }.getOrDefault(emptyList())

    override fun save(peers: List<TrustedPeer>) {
        file.parentFile?.mkdirs()
        file.writeText(json.encodeToString(trustListSerializer, peers))
    }
}

class JsonHistoryStorage(private val file: File) : HistoryStorage {

    override fun load(): List<SyncEvent> = runCatching {
        json.decodeFromString(eventListSerializer, file.readText())
    }.getOrDefault(emptyList())

    override fun save(events: List<SyncEvent>) {
        file.parentFile?.mkdirs()
        file.writeText(json.encodeToString(eventListSerializer, events))
    }
}