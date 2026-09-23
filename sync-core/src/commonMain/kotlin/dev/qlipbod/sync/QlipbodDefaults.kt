package dev.qlipbod.sync

/**
 * Wire-level constants shared by both platforms (plan §8): the TCP ports the verified
 * data connection and the one-time PIN pairing exchange listen on by default. They are
 * just defaults — discovery advertises whichever sync port a host actually bound.
 */
object QlipbodDefaults {
    /** Default port for the verified data connection (auto-connect dials this). */
    const val SYNC_PORT = 4343

    /** Default port for the short-lived PIN pairing exchange. */
    const val PAIRING_PORT = 4344
}