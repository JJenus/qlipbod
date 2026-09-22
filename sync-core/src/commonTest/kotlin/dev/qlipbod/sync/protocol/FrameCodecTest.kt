package dev.qlipbod.sync.protocol

import dev.qlipbod.sync.identity.DeviceId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Exercises the wire format: JSON serialization of SyncEvent and the length-prefixed frame codec. */
class FrameCodecTest {

    private val event = SyncEvent(
        origin = DeviceId("abc123"),
        sequence = 7,
        payload = "hello clipboard",
        sensitive = true,
        sentAtMillis = 1_700_000_000_000,
    )

    @Test
    fun `event json round trips all fields`() {
        val decoded = FrameCodec.json.decodeFromString(SyncEvent.serializer(), FrameCodec.json.encodeToString(SyncEvent.serializer(), event))
        assertEquals(event, decoded)
    }

    @Test
    fun `frame encodes and decodes a single event`() {
        val decoder = FrameDecoder()
        val frames = decoder.push(FrameCodec.encodeFrame(event))
        assertEquals(listOf(event), frames)
    }

    @Test
    fun `decoder accumulates partial frames across pushes`() {
        val bytes = FrameCodec.encodeFrame(event)
        val decoder = FrameDecoder()
        assertTrue(decoder.push(bytes.copyOfRange(0, 5)).isEmpty())
        val rest = decoder.push(bytes.copyOfRange(5, bytes.size))
        assertEquals(listOf(event), rest)
        assertTrue(decoder.finish().isEmpty())
    }

    @Test
    fun `two frames in one push are both decoded in order`() {
        val other = event.copy(payload = "second", sequence = 8)
        val bytes = FrameCodec.encodeFrame(event) + FrameCodec.encodeFrame(other)
        assertEquals(listOf(event, other), FrameDecoder().push(bytes))
    }

    @Test
    fun `oversized length header is rejected`() {
        /* length = 2 GiB, no body */
        val bad = byteArrayOf((0x80).toByte(), 0, 0, 0)
        assertFailsWith<FrameFormatException> { FrameDecoder().push(bad) }
    }

    @Test
    fun `length header exceeding max frame bytes is rejected`() {
        val len = FrameCodec.DEFAULT_MAX_FRAME_BYTES + 1
        val bad = byteArrayOf(
            (len ushr 24).toByte(), (len ushr 16).toByte(), (len ushr 8).toByte(), len.toByte(),
        )
        assertFailsWith<FrameFormatException> { FrameDecoder().push(bad) }
    }

    @Test
    fun `zero length header is rejected`() {
        assertFailsWith<FrameFormatException> { FrameDecoder().push(byteArrayOf(0, 0, 0, 0)) }
    }

    @Test
    fun `truncated frame fails at finish`() {
        val bytes = FrameCodec.encodeFrame(event)
        val decoder = FrameDecoder()
        decoder.push(bytes.copyOfRange(0, bytes.size - 3))
        assertFailsWith<FrameFormatException> { decoder.finish() }
    }

    @Test
    fun `accumulating beyond max frame bytes without a complete frame is rejected`() {
        val decoder = FrameDecoder()
        assertFailsWith<FrameFormatException> {
            decoder.push(ByteArray(FrameCodec.DEFAULT_MAX_FRAME_BYTES + 8))
        }
    }
}