package dev.qlipbod.sync.protocol

import java.io.DataInputStream
import java.io.EOFException

/**
 * JVM framing glue for the mutual certificate handshake: reads exactly one Hello frame
 * (4-byte length prefix + body) off a live stream without over-reading, so the data
 * channel's own reader resumes where this left off. A peer that goes away mid-handshake
 * surfaces as [HandshakeException] — "unpaired, not an error" (plan §9).
 */
fun readHelloFrame(input: DataInputStream): HandshakeHello {
    val body = ByteArray(readHelloLength(input))
    try {
        input.readFully(body)
    } catch (e: EOFException) {
        throw HandshakeException("peer closed before hello body", e)
    }
    return HandshakeProtocol.decodeHello(body)
}

private fun readHelloLength(input: DataInputStream): Int {
    val header = ByteArray(FrameCodec.HEADER_BYTES)
    try {
        input.readFully(header)
    } catch (e: EOFException) {
        throw HandshakeException("peer closed before hello length", e)
    }
    val length = ((header[0].toInt() and 0xff) shl 24) or
        ((header[1].toInt() and 0xff) shl 16) or
        ((header[2].toInt() and 0xff) shl 8) or
        (header[3].toInt() and 0xff)
    if (length <= 0 || length > HandshakeProtocol.MAX_HELLO_BYTES) {
        throw HandshakeException("invalid hello length: $length")
    }
    return length
}