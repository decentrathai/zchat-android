package co.electriccoin.zcash.ui.screen.chat.model

/**
 * ZC-1474 (owner W-123, FIX_PLAN REVISION 2) — which way a photo, file or voice note goes.
 *
 * - [Route.E2E_WRAP]: the chat has an end-to-end shared key; the file key is wrapped with it in a ZFILE (unchanged).
 * - [Route.NOSTR_ZATT]: an Open or Tunnel chat with no shared key, the contact's relay key bound and its newest relay
 *   rumor carrying the `zatt1` capability: the file key goes inside an authenticated relay message ([ZATTMessage]) to
 *   that key, free. Never in a Shielded chat, never to a contact without the capability (2.12.4 would show the key).
 * - [Route.REFUSE]: the existing notice (B1b's reason and priced setup).
 */
object AttachmentTransport {
    enum class Route { NOSTR_ZATT, E2E_WRAP, REFUSE }

    fun choose(
        mode: ConversationMode,
        peerNostrKeyBound: Boolean,
        peerCap2130: Boolean,
        hasE2ESharedKey: Boolean,
    ): Route =
        when {
            hasE2ESharedKey -> Route.E2E_WRAP
            freeRouteOpen(mode, peerNostrKeyBound, peerCap2130) -> Route.NOSTR_ZATT
            else -> Route.REFUSE
        }

    /** Whether a ZATT may go to this contact now, a shared key or not (the send's re-check after the upload). */
    fun freeRouteOpen(
        mode: ConversationMode,
        peerNostrKeyBound: Boolean,
        peerCap2130: Boolean,
    ): Boolean = (mode == ConversationMode.OPEN || mode == ConversationMode.TUNNEL) && peerNostrKeyBound && peerCap2130
}
