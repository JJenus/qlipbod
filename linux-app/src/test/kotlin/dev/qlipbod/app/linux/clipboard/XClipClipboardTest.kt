package dev.qlipbod.app.linux.clipboard

import org.junit.Assume.assumeTrue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Real X11 CLIPBOARD round-trip via `xclip`. Skipped when xclip is absent or there is
 * no X server (headless CI). The plan targets X11 first and syncs CLIPBOARD only (§8) —
 * never PRIMARY, or every text selection would be synced.
 */
class XClipClipboardTest {

    /** Probe the environment once (write+read); skip the test when it cannot work. */
    private fun skipUnlessRunnable() {
        val probe = XClipClipboard()
        val ok = runCatching {
            probe.write("qlipbod-probe-${System.nanoTime()}")
            probe.read() != null
        }.getOrDefault(false)
        assumeTrue("xclip or an X server is unavailable", ok)
    }

    @Test
    fun `write then read round trips through the CLIPBOARD buffer`() {
        skipUnlessRunnable()
        val clipboard = XClipClipboard()
        val payload = "qlipbod-${System.nanoTime()}"

        clipboard.write(payload)
        assertEquals(payload, clipboard.read())
    }

    @Test
    fun `empty clipboard reads as null`() {
        skipUnlessRunnable()
        val clipboard = XClipClipboard()

        clipboard.write("")
        assertNull(clipboard.read(), "empty CLIPBOARD selection must map to null, not \"\"")
    }
}