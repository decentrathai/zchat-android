package co.electriccoin.zcash.ui.screen.chat.model

/**
 * Per-conversation transport + privacy mode.
 *
 * - [VAULT]   Every message is a shielded Zcash transaction with our ratchet E2E on
 *             top. Maximum metadata privacy + forward secrecy. Slow + costs ~0.00001
 *             ZEC per message. Voice/video calls are disabled in Vault conversations
 *             because each ICE candidate would cost a separate shielded tx.
 *
 * - [TUNNEL]  First message is a shielded ZBOOT memo that hands the recipient our
 *             NOSTR pubkey and a preferred relay. All subsequent messages travel
 *             through NIP-17 gift-wrapped NOSTR DMs (free, instant) using the
 *             keys exchanged in the bootstrap. Voice/video calls allowed.
 *
 * - [OPEN]    NIP-17 gift-wrapped NOSTR DMs from message one — no Zcash bootstrap.
 *             The peer's NOSTR pubkey is exchanged out of band (QR scan, npub paste).
 *             Free + instant. Voice/video calls allowed.
 *
 * Default for any conversation we don't have a stored mode for is [VAULT] — this
 * preserves the pre-Phase-B behavior for every existing chat.
 */
enum class ConversationMode {
    VAULT,
    TUNNEL,
    OPEN;

    val isShieldedOnlyTransport: Boolean get() = this == VAULT
    val supportsCalls: Boolean get() = this != VAULT
    val isNostrTransport: Boolean get() = this == TUNNEL || this == OPEN
    val needsBootstrap: Boolean get() = this == TUNNEL

    companion object {
        val DEFAULT = VAULT
    }
}

/**
 * A peer-initiated switch to a PAID mode (Vault) that we deliberately do NOT auto-adopt (money-drain
 * guard, #187: a peer must never be able to silently move us onto per-message on-chain spends).
 * Instead of the old passive "your side is unchanged" note, the chat screen surfaces this as an
 * accept/keep prompt so the user decides WITH the cost consequences in front of them.
 */
data class PendingModeSwitch(
    val peerAddress: String,
    val peerName: String,
    val newMode: ConversationMode,
    val currentMode: ConversationMode,
)
