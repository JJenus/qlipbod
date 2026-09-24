package dev.qlipbod.sync.protocol

import java.io.DataInputStream

/**
 * JVM framing glue for the mutual certificate handshake: reads exactly one Hello frame
 * and decodes it. The generic [readFrameBody] does the socket read; anything that goes
 * wrong mid-handshake surfaces as [HandshakeException] — "unpaired, not an error"
 * (plan §9).
 */
fun readHelloFrame(input: DataInputStream): HandshakeHello = try {
    HandshakeProtocol.decodeHello(readFrameBody(input, HandshakeProtocol.MAX_HELLO_BYTES, "hello"))
} catch (e: StreamFrameException) {
    throw HandshakeException(e.message ?: "handshake read failed", e)
}