package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferences
import co.electriccoin.zcash.ui.screen.chat.util.MAX_MESSAGE_INPUT_CHARS
import co.electriccoin.zcash.ui.screen.chat.util.capDraft

/**
 * Round 8 P4 (PHONE_TRIAGE F6): whether [peerAddress] already has a chat. Its INIT went out (or it wrote to us
 * first), E2E is on, or a key exchange with it is complete. New Chat then never sends: its send path has no ratchet,
 * so a message from there went out as a plaintext memo outside the end-to-end session (and on-chain for a Tunnel
 * chat). The text goes to that chat's draft, and the chat's own send path encrypts it or refuses.
 *
 * A conversation whose INIT was refused before submission still needs the INIT (R2-6 c), so it is not an
 * existing chat unless E2E says otherwise.
 */
internal fun ZchatPreferences.hasExistingChat(peerAddress: String): Boolean =
    !needsConversationInit(peerAddress) ||
        isE2EEnabled(peerAddress) ||
        isE2EKeyExchangeComplete(peerAddress)

/**
 * The draft a chat holds after New Chat hands it [typed], when it already held [existing]. Both texts are kept
 * (the earlier draft first) while they fit the composer's limit. When they do not fit, the typed text wins: it is
 * what the user just asked to send.
 */
internal fun mergedHandOffDraft(
    existing: String?,
    typed: String,
): String {
    val new = capDraft(typed)
    val old = existing.orEmpty()
    return when {
        old.isBlank() -> new
        // Handed over already (a second tap, or the same text again): nothing to add.
        old.endsWith(new) -> old
        old.length + 1 + new.length <= MAX_MESSAGE_INPUT_CHARS -> "$old\n$new"
        else -> new
    }
}
