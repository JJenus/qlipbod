package dev.qlipbod.sync.protocol

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/** A malformed or oversized frame. Fatal for the channel that produced it. */
class FrameFormatException(message: String) : Exception(message)

/**
 * Length-prefixed JSON framing for [SyncEvent]: 4-byte big-endian length + UTF-8 body.
 * The length is validated against a hard cap so a misbehaving peer cannot exhaust
 * memory or smuggle oversized payloads through (plan §9 "large clipboard payloads").
 */
object FrameCodec {
    const val DEFAULT_MAX_FRAME_BYTES = 1_000_000
    const val HEADER_BYTES = 4

    val json: Json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    fun encodeFrame(event: SyncEvent): ByteArray = encodeFrameBytes(encode(event))

    /**
     * Length-prefix [body] with the same 4-byte big-endian header used for event frames,
     * so handshake/pairing bytes can share the framing (plan §11.5).
     */
    fun encodeFrameBytes(body: ByteArray): ByteArray {
        require(body.size <= DEFAULT_MAX_FRAME_BYTES) {
            "frame of ${body.size} bytes exceeds the $DEFAULT_MAX_FRAME_BYTES limit"
        }
        val out = ByteArray(HEADER_BYTES + body.size)
        out[0] = (body.size ushr 24).toByte()
        out[1] = (body.size ushr 16).toByte()
        out[2] = (body.size ushr 8).toByte()
        out[3] = body.size.toByte()
        body.copyInto(out, HEADER_BYTES)
        return out
    }

    internal fun encode(event: SyncEvent): ByteArray =
        json.encodeToString(SyncEvent.serializer(), event).encodeToByteArray()

    internal fun decode(body: ByteArray): SyncEvent {
        val text = body.decodeToString()
        return try {
            json.decodeFromString(SyncEvent.serializer(), text)
        } catch (e: SerializationException) {
            throw FrameFormatException("malformed frame payload: ${e.message}")
        }
    }
}

/**
 * Stateful streaming decoder: accumulate arbitrary byte chunks, emit complete frames,
 * and reject anything that cannot possibly be a valid frame within the size cap.
 */
class FrameDecoder(
    private val maxFrameBytes: Int = FrameCodec.DEFAULT_MAX_FRAME_BYTES,
) {
    private var pending = ByteArray(0)

    fun push(bytes: ByteArray): List<SyncEvent> {
        if (bytes.isNotEmpty()) pending = pending + bytes
        val decoded = mutableListOf<SyncEvent>()
        while (pending.size >= FrameCodec.HEADER_BYTES) {
            val length = readLength(pending)
            if (length <= 0) throw FrameFormatException("invalid frame length: $length")
            if (length > maxFrameBytes) throw FrameFormatException("frame of $length bytes exceeds max $maxFrameBytes")
            if (pending.size < FrameCodec.HEADER_BYTES + length) break
            val body = pending.copyOfRange(FrameCodec.HEADER_BYTES, FrameCodec.HEADER_BYTES + length)
            decoded += FrameCodec.decode(body)
            pending = pending.copyOfRange(FrameCodec.HEADER_BYTES + length, pending.size)
        }
        if (pending.size > maxFrameBytes + FrameCodec.HEADER_BYTES) {
            throw FrameFormatException("buffered data exceeds max frame size without a complete frame")
        }
        return decoded
    }

    /** Signals the end of the stream; throws if a frame was cut short. */
    fun finish(): List<SyncEvent> {
        val rest = push(ByteArray(0))
        if (pending.isNotEmpty()) {
            throw FrameFormatException("stream ended mid-frame: ${pending.size} bytes remaining")
        }
        return rest
    }

    private fun readLength(bytes: ByteArray): Int =
        ((bytes[0].toInt() and 0xff) shl 24) or
            ((bytes[1].toInt() and 0xff) shl 16) or
            ((bytes[2].toInt() and 0xff) shl 8) or
            (bytes[3].toInt() and 0xff)
}