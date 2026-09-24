package dev.qlipbod.sync.clipboard

/**
 * The platform clipboard plane (plan §8). Linux implements this with `xclip` over the
 * X11 CLIPBOARD buffer; Android will provide its own adapter behind the same interface.
 */
interface ClipboardAdapter {
    /** Current clipboard text, or null when empty or unavailable. Must not throw. */
    fun read(): String?

    /** Replace the clipboard with [text]. May throw when the platform refuses. */
    fun write(text: String)
}