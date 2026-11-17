package co.electriccoin.zcash.ui.screen.chat.model

/**
 * ZC-1473 — whether the chat menu offers "Reconnect (re-send my key)". It re-sends our key exchange or our relay
 * identity, which an Open chat never uses (Open sends nothing on Reconnect and pays nothing), so it is hidden there.
 * The chat screen wires it (B2); the tap's own result is reported by ChatViewModel.reSendOurIdentity.
 */
object ReconnectMenu {
    fun visible(mode: ConversationMode): Boolean = mode != ConversationMode.OPEN
}
