package dev.qlipbod.app.linux.clipboard

import java.util.concurrent.TimeUnit

/**
 * X11 CLIPBOARD buffer via the `xclip` CLI (plan §8: X11 first, CLIPBOARD only — never
 * PRIMARY, or every text selection would get synced).
 *
 * A read is a short `xclip -o` invocation; `xclip` exits non-zero when the clipboard is
 * empty or there is no X server, which maps to null. A write pipes stdin into
 * `xclip -i`, which owns the selection and serves it to other clients.
 */
class XClipClipboard : ClipboardAdapter {

    override fun read(): String? = runCatching {
        val p = ProcessBuilder("xclip", "-selection", "clipboard", "-o").start()
        val out = p.inputStream.readBytes().decodeToString()
        if (p.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS) && p.exitValue() == 0 && out.isNotEmpty()) out else null
    }.getOrNull()

    override fun write(text: String) {
        val p = ProcessBuilder("xclip", "-selection", "clipboard", "-i").start()
        p.outputStream.bufferedWriter().use { it.write(text) }
        if (!p.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            p.destroyForcibly()
            error("xclip did not accept clipboard content within $TIMEOUT_SECONDS s")
        }
    }

    private companion object {
        const val TIMEOUT_SECONDS = 10L
    }
}