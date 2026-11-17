package co.electriccoin.zcash.ui.call

/**
 * ZC-1467: whether the call overlay must clear the focused text field and hide the soft keyboard. The overlay is a
 * Compose sibling of the chat, so without this the chat field keeps focus and its keyboard covers the call screen.
 * True for every state that shows a call screen (Ringing, Dialling, Connecting, InCall); Idle and Ended leave the
 * keyboard alone.
 */
object CallImePolicy {
    fun shouldDismissIme(state: VoiceCallManager.CallState): Boolean =
        when (state) {
            is VoiceCallManager.CallState.Ringing,
            is VoiceCallManager.CallState.Dialling,
            is VoiceCallManager.CallState.Connecting,
            is VoiceCallManager.CallState.InCall -> true
            is VoiceCallManager.CallState.Idle,
            is VoiceCallManager.CallState.Ended -> false
        }
}
