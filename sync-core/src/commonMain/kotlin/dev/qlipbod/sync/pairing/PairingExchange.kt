package dev.qlipbod.sync.pairing

/**
 * Drives a [PairingSession] to completion over any bidirectional transport (a short-lived
 * TCP connection today; whatever Android needs tomorrow). Both sides alternate writes and
 * reads until the exchange is CONFIRMED or FAILED, then stop — the initiator owes one final
 * CONFIRM after its own ACCEPT, so the loop ends as soon as the state machine settles
 * (plan §4 HELLO → ACCEPT → CONFIRM, §10.1). Pure logic: no I/O happens here.
 *
 * On failure the failing side forwards its [PairingOutcome.Failed.notice] so the
 * counterpart learns why (PIN mismatch, replay, tampering) instead of hanging.
 */
fun PairingSession.drive(
    send: (PairingMessage) -> Unit,
    receive: () -> PairingMessage,
): PairingOutcome {
    var pending: PairingMessage? = if (role == PairingRole.INITIATOR) start() else null

    while (true) {
        pending?.let(send)
        pending = null

        // The initiator's state settles to CONFIRMED the moment it has produced its final
        // CONFIRM; deliver that, then stop — nothing further is owed either way.
        when (state) {
            PairingSession.State.CONFIRMED -> return PairingOutcome.Confirmed(checkNotNull(peer))
            PairingSession.State.FAILED -> return PairingOutcome.Failed("session failed during exchange")
            PairingSession.State.STARTED, PairingSession.State.WAITING -> Unit
        }

        when (val outcome = handle(receive())) {
            is PairingOutcome.Send -> pending = outcome.message
            is PairingOutcome.Confirmed -> return outcome
            is PairingOutcome.Failed -> {
                outcome.notice?.let(send)
                return outcome
            }
        }
    }
}