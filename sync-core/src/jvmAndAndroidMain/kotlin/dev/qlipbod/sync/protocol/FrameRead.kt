package dev.qlipbod.sync.protocol

import java.io.DataInputStream
import java.io.EOFException

/**
 * A length-prefixed frame could not be read off a live stream: the peer went away
 * mid-frame, or the header was nonsense. Distinct from [FrameFormatException], which
 * guards *decode* — this guards the socket read itself.
 */
class StreamFrameException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Reads exactly one length-prefixed frame body (4-byte big-endian length + payload) off a
 * live stream without over-reading, so the caller's own reader resumes where this left
 * off. Used by the certificate handshake and the pairing exchange to share one framing
 * definition (plan §11.5); [what] names the frame kind in error messages.
 */
fun readFrameBody(
    input: DataInputStream,
    maxBodyBytes: Int = FrameCodec.DEFAULT_MAX_FRAME_BYTES,
    what: String = "frame",
): ByteArray {
    val length = readFrameLength(input, maxBodyBytes, what)
    val body = ByteArray(length)
    try {
        input.readFully(body)
    } catch (e: EOFException) {
        throw StreamFrameException("peer closed before $what body", e)
    }
    return body
}

private fun readFrameLength(input: DataInputStream, maxBodyBytes: Int, what: String): Int {
    val header = ByteArray(FrameCodec.HEADER_BYTES)
    try {
        input.readFully(header)
    } catch (e: EOFException) {
        throw StreamFrameException("peer closed before $what length", e)
    }
    val length = ((header[0].toInt() and 0xff) shl 24) or
        ((header[1].toInt() and 0xff) shl 16) or
        ((header[2].toInt() and 0xff) shl 8) or
        (header[3].toInt() and 0xff)
    if (length <= 0 || length > maxBodyBytes) throw StreamFrameException("invalid $what length: $length")
    return length
}