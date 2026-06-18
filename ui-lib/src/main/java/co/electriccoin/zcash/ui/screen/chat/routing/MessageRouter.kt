package co.electriccoin.zcash.ui.screen.chat.routing

import co.electriccoin.zcash.ui.screen.chat.model.ConversationMode
import co.electriccoin.zcash.ui.screen.chat.model.ZBootMessage

/**
 * Pure decision function: given a conversation's mode + bootstrap state, what transport
 * should this outgoing message take?
 *
 * NOT a class hierarchy — there are exactly three transports and a fourth case ("send a
 * bootstrap shielded ZBOOT first"), so a sealed result + a function is enough.
 */
sealed interface SendPlan {
    /** Send the message via the existing shielded-Zcash + memo pipeline. */
    data class ShieldedMemo(val payload: String) : SendPlan

    /** Send via NIP-17 gift-wrap NOSTR DM to the recipient's pubkey, on [relayUrl]. */
    data class NostrDirect(
        val payload: String,
        val recipientPubkeyHex: String,
        val relayUrl: String,
    ) : SendPlan

    /**
     * Tunnel mode but the recipient hasn't been bootstrapped yet. We must first publish
     * a shielded ZBOOT memo carrying our NOSTR pubkey + relay. The original payload is
     * queued and re-routed (as [NostrDirect]) once the ZBOOT has been mined.
     */
    data class BootstrapThenQueue(
        val bootMemo: String,
        val queuedPayload: String,
    ) : SendPlan
}

/**
 * Decide the transport for an outgoing message.
 *
 * @param payload   the already-serialized chat-protocol payload (e.g. a ZMSG v4 line).
 * @param mode      the conversation's current [ConversationMode].
 * @param peerNostrPubkeyHex peer's NOSTR pubkey if known (set after their ZBOOT was processed).
 * @param peerRelayUrl peer's preferred NOSTR relay if known.
 * @param ourBootSent true iff we've already published a ZBOOT to this peer (Tunnel only).
 * @param ourNostrPubkeyHex our own NOSTR pubkey (for embedding in ZBOOT).
 * @param ourRelayUrl       our own preferred relay (for embedding in ZBOOT).
 * @param convId            v4 conversation id for the ZBOOT memo.
 */
fun routeOutgoing(
    payload: String,
    mode: ConversationMode,
    peerNostrPubkeyHex: String?,
    peerRelayUrl: String?,
    ourBootSent: Boolean,
    ourNostrPubkeyHex: String,
    ourRelayUrl: String,
    convId: String,
    // Signed-ZBOOT signature over (convId|ourNostrPubkeyHex|ourRelayUrl), produced by the caller
    // with our E2E identity key. Required so the emitted bootstrap is authenticated.
    ourBootSignature: String = "",
): SendPlan = when (mode) {
    ConversationMode.VAULT -> SendPlan.ShieldedMemo(payload)

    ConversationMode.OPEN -> {
        // Open requires the peer's pubkey + relay to be known out-of-band. If either is
        // missing the caller is misusing the API; fall back to a shielded memo so the
        // message isn't silently dropped.
        if (peerNostrPubkeyHex != null && peerRelayUrl != null) {
            SendPlan.NostrDirect(payload, peerNostrPubkeyHex, peerRelayUrl)
        } else {
            SendPlan.ShieldedMemo(payload)
        }
    }

    ConversationMode.TUNNEL -> {
        // Tunnel: first message MUST be a ZBOOT, subsequent messages go over NOSTR.
        val peerKnown = peerNostrPubkeyHex != null && peerRelayUrl != null
        when {
            peerKnown && ourBootSent ->
                SendPlan.NostrDirect(payload, peerNostrPubkeyHex, peerRelayUrl)
            else -> {
                // Bootstrap not complete yet. Emit a ZBOOT shielded memo and queue the
                // payload until the peer publishes their own ZBOOT back.
                // NOTE: this builder path is currently unused (ChatViewModel constructs ZBOOTs directly with
                // the live rotation epoch). It defaults epoch to 0; if revived, thread the epoch + sign over
                // the v3 signedData (ZBootMessage.signedDataFor(..., epoch)) so the signature verifies.
                val boot = ZBootMessage(
                    convId = convId,
                    senderNostrPubkeyHex = ourNostrPubkeyHex,
                    relayUrl = ourRelayUrl,
                    signature = ourBootSignature,
                ).serialize()
                SendPlan.BootstrapThenQueue(bootMemo = boot, queuedPayload = payload)
            }
        }
    }
}
