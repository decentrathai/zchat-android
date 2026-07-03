package co.electriccoin.zcash.ui.screen.chat.model

/**
 * Honest tri-state for the ⋮-menu E2E status row (#257). The old menu derived "On" purely from
 * isE2EKeyExchangeComplete (= we hold both keys), which stayed "On" for a DIVERGED chat that couldn't
 * actually decrypt, and for a responder whose KEXACK hadn't been delivered yet (the peer might not hold
 * our key). This models the real handshake progress.
 */
enum class E2EHandshakeState { OFF, FINISHING, ON }

/** Whether OUR KEXACK (the responder's reply that hands the peer our key) is settled with the peer. */
enum class Settlement { SETTLED, SETTLED_BACKFILL, PENDING }

/**
 * Pure decision for whether our side of the handshake is settled — no Android deps, unit-tested.
 *
 * - receivedKexPubkey == null  → we are the INITIATOR (or a legacy E2E_INIT peer whose receive path never
 *   records a received-KEX marker, or a pre-#233 responder): we owe no KEXACK → SETTLED.
 * - sentKexAckPubkey == receivedKexPubkey → we already paid + submitted the KEXACK for THIS exact key → SETTLED.
 * - peerNostrPubkey != null && !keyChangeFlagged → pre-marker-era responder: the peer's ZBOOT self-gates on
 *   holding OUR E2E key, so a stored peer NOSTR pubkey proves our ack WAS delivered → SETTLED_BACKFILL
 *   (caller durably stamps sentKexAckPubkey once so this converges). keyChangeFlagged blocks a false stamp
 *   while a key change is in progress (the key-change branch nulls sentKexAckPubkey + re-sets receivedKex).
 * - else → PENDING (responder holds both keys but our KEXACK hasn't landed — the exact "lying On" case).
 */
object E2EAckSettlement {
    fun settle(
        receivedKexPubkey: String?,
        sentKexAckPubkey: String?,
        peerNostrPubkey: String?,
        keyChangeFlagged: Boolean,
    ): Settlement = when {
        receivedKexPubkey == null -> Settlement.SETTLED
        sentKexAckPubkey == receivedKexPubkey -> Settlement.SETTLED
        peerNostrPubkey != null && !keyChangeFlagged -> Settlement.SETTLED_BACKFILL
        else -> Settlement.PENDING
    }
}
