package co.electriccoin.zcash.ui.screen.chat.model

import cash.z.ecc.android.sdk.model.Zatoshi
import org.junit.Test
import kotlin.test.assertEquals

/**
 * C-8 (owner decision, 2026-09-10): a New-Chat Send All to a contact whose handshake has not run yet
 * keeps the handshake cost back, and that cost is the SAME figure the handshake-consent dialog quotes.
 */
class SendAllHandshakeReserveTest {
    @Test
    fun the_reserve_is_the_consent_dialogs_quote_for_one_peer() {
        val consentQuote =
            (decideHandshakeSpend(consentGranted = false, pendingPeers = 1) as HandshakeSpendDecision.NeedsConsent)
                .estimatedCost

        assertEquals(consentQuote, handshakeSpendQuote(peers = 1))
        assertEquals(
            consentQuote,
            sendAllHandshakeReserve(ConversationMode.TUNNEL, isFreeOpenSend = false, isKeyExchangeComplete = false),
        )
        assertEquals(Zatoshi(HANDSHAKE_COST_ZAT), consentQuote)
    }

    /** An Open chat whose peer key is unknown still sends an on-chain KEX when it opens. */
    @Test
    fun a_paid_open_first_send_keeps_the_reserve_too() {
        assertEquals(
            handshakeSpendQuote(peers = 1),
            sendAllHandshakeReserve(ConversationMode.OPEN, isFreeOpenSend = false, isKeyExchangeComplete = false),
        )
    }

    /** Existing contacts and chats that fire no paid handshake send everything minus fees. */
    @Test
    fun nothing_is_kept_when_no_paid_handshake_follows() {
        assertEquals(
            Zatoshi(0),
            sendAllHandshakeReserve(ConversationMode.VAULT, isFreeOpenSend = false, isKeyExchangeComplete = false),
        )
        assertEquals(
            Zatoshi(0),
            sendAllHandshakeReserve(ConversationMode.TUNNEL, isFreeOpenSend = false, isKeyExchangeComplete = true),
        )
        assertEquals(
            Zatoshi(0),
            sendAllHandshakeReserve(ConversationMode.OPEN, isFreeOpenSend = true, isKeyExchangeComplete = false),
        )
    }

    @Test
    fun the_consent_quote_scales_with_peers_as_before() {
        assertEquals(Zatoshi(3 * HANDSHAKE_COST_ZAT), handshakeSpendQuote(peers = 3))
        assertEquals(Zatoshi(HANDSHAKE_COST_ZAT), handshakeSpendQuote(peers = 0))
    }
}
