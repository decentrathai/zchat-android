package co.electriccoin.zcash.ui.screen.chat.model

/** ZC-1475: the actions of the safety-number dialog. There is no "Done": the close cross dismisses it. */
enum class SafetyNumberButton { MARK_VERIFIED, CLOSE }

/**
 * ZC-1475: which actions the safety-number dialog offers. A contact not yet verified gets one primary action (Mark as
 * verified) and the close cross; a verified contact gets the cross only. Marking stays a local flag (markE2EVerified),
 * nothing goes on the wire.
 */
object SafetyNumberButtons {
    fun forState(isVerified: Boolean): List<SafetyNumberButton> =
        if (isVerified) {
            listOf(SafetyNumberButton.CLOSE)
        } else {
            listOf(SafetyNumberButton.MARK_VERIFIED, SafetyNumberButton.CLOSE)
        }
}
