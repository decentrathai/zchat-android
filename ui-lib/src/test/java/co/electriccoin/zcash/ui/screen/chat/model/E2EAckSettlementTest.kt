package co.electriccoin.zcash.ui.screen.chat.model

import org.junit.Test
import kotlin.test.assertEquals

/**
 * Edge/precedence coverage for [E2EAckSettlement.settle] that COMPLEMENTS the happy-path matrix in
 * [E2EHandshakeStateTest] (which pins the five canonical branches). Here we pin the branch PRECEDENCE
 * and the "stale ack for a rotated key" case — the ones a refactor of the `when` could silently break,
 * since settle() drives the honest ⋮-menu E2E status (a wrong SETTLED shows a "lying On").
 *
 * Pure Kotlin (no Android deps) → src/test.
 */
class E2EAckSettlementTest {

    // received == null short-circuits FIRST, even if a key change is flagged: an initiator (no received
    // KEX) owes no KEXACK regardless of any other field.
    @Test
    fun initiatorShortCircuitsSettled_evenWhenKeyChangeFlagged() {
        assertEquals(
            Settlement.SETTLED,
            E2EAckSettlement.settle(
                receivedKexPubkey = null,
                sentKexAckPubkey = "keyA",
                peerNostrPubkey = "npubX",
                keyChangeFlagged = true,
            ),
        )
    }

    // A KEXACK that was sent for the PREVIOUS key (peer rotated: received != sent) must NOT count as
    // settled-for-this-key. With no peer NOSTR pubkey to backfill, it falls to PENDING (we owe a fresh ack).
    @Test
    fun staleAckForRotatedKey_isPending() {
        assertEquals(
            Settlement.PENDING,
            E2EAckSettlement.settle(
                receivedKexPubkey = "keyNEW",
                sentKexAckPubkey = "keyOLD",
                peerNostrPubkey = null,
                keyChangeFlagged = false,
            ),
        )
    }

    // Stale ack + a stored peer NOSTR pubkey + no key-change-in-progress → backfill (the pre-marker-era
    // proof-of-delivery path still applies to the new key). Distinct from staleAckForRotatedKey above.
    @Test
    fun staleAckButPeerNostrKnown_backfills() {
        assertEquals(
            Settlement.SETTLED_BACKFILL,
            E2EAckSettlement.settle(
                receivedKexPubkey = "keyNEW",
                sentKexAckPubkey = "keyOLD",
                peerNostrPubkey = "npubX",
                keyChangeFlagged = false,
            ),
        )
    }

    // Exact-match ack takes precedence over the key-change flag: if we already sent the ack for THIS
    // exact received key, we are settled even mid key-change bookkeeping.
    @Test
    fun exactAckMatchSettles_regardlessOfKeyChangeFlag() {
        assertEquals(
            Settlement.SETTLED,
            E2EAckSettlement.settle(
                receivedKexPubkey = "keyA",
                sentKexAckPubkey = "keyA",
                peerNostrPubkey = null,
                keyChangeFlagged = true,
            ),
        )
    }
}
