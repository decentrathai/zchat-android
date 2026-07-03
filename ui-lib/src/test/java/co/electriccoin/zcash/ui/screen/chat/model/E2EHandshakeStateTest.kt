package co.electriccoin.zcash.ui.screen.chat.model

import org.junit.Test
import kotlin.test.assertEquals

/**
 * #257: the ⋮-menu E2E status must be honest. "On" only when we hold both keys AND our KEXACK is settled;
 * a responder that holds both keys but hasn't delivered its ack is FINISHING (the live "lying On" bug).
 */
class E2EHandshakeStateTest {

    private fun conv(complete: Boolean, enabled: Boolean, ackSettled: Boolean, kexInFlight: Boolean = false) =
        Conversation(
            peerAddress = "u1test",
            messages = emptyList(),
            lastMessage = null,
            e2eEnabled = enabled,
            e2eKeyExchangeComplete = complete,
            e2eAckSettled = ackSettled,
            e2eKexInFlight = kexInFlight,
        )

    @Test fun on_requires_keys_and_settled_ack() =
        assertEquals(E2EHandshakeState.ON, conv(complete = true, enabled = true, ackSettled = true).e2eHandshakeState)

    @Test fun responder_pending_ack_is_finishing_even_with_both_keys() = // the live "lying On" bug
        assertEquals(E2EHandshakeState.FINISHING, conv(complete = true, enabled = true, ackSettled = false).e2eHandshakeState)

    @Test fun initiator_toggle_window_is_finishing() =
        assertEquals(E2EHandshakeState.FINISHING, conv(complete = false, enabled = true, ackSettled = true).e2eHandshakeState)

    @Test fun silent_bootstrap_kex_is_finishing_not_off() = // e2eEnabled=false but isOwnBootSent=true
        assertEquals(E2EHandshakeState.FINISHING, conv(complete = false, enabled = false, ackSettled = true, kexInFlight = true).e2eHandshakeState)

    @Test fun no_keys_no_intent_is_off() =
        assertEquals(E2EHandshakeState.OFF, conv(complete = false, enabled = false, ackSettled = true).e2eHandshakeState)

    // Settlement matrix — pure, no Android deps.
    @Test fun initiator_or_legacy_e2einit_settles() =
        assertEquals(Settlement.SETTLED, E2EAckSettlement.settle(receivedKexPubkey = null, sentKexAckPubkey = null, peerNostrPubkey = null, keyChangeFlagged = false))

    @Test fun ack_submitted_for_this_key_settles() =
        assertEquals(Settlement.SETTLED, E2EAckSettlement.settle("keyA", "keyA", null, false))

    @Test fun stored_peer_nostr_backfills_legacy_responder() =
        assertEquals(Settlement.SETTLED_BACKFILL, E2EAckSettlement.settle("keyA", null, "npubX", false))

    @Test fun key_change_in_progress_stays_pending() =
        assertEquals(Settlement.PENDING, E2EAckSettlement.settle("keyA", null, "npubX", keyChangeFlagged = true))

    @Test fun responder_without_ack_or_transport_is_pending() =
        assertEquals(Settlement.PENDING, E2EAckSettlement.settle("keyA", null, null, false))
}
