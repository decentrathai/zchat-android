package co.electriccoin.zcash.ui.screen.chat.model

/**
 * ZC-1275: a Tunnel chat's setup goes on-chain and is paid, so the chip must not say "not on-chain" during it.
 *
 * The caller's handshakeComplete is `conversation.hasNostrCallChannel`: the contact's NOSTR key is known, which is the state
 * the composer already reads to tell whether a Tunnel send still needs the on-chain handshake. Open has no on-chain setup.
 */
object TunnelSetupChip {
    fun shows(
        mode: ConversationMode,
        handshakeComplete: Boolean,
    ): Boolean = mode == ConversationMode.TUNNEL && !handshakeComplete
}
