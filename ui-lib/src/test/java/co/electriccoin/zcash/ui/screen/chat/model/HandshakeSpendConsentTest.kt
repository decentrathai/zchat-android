package co.electriccoin.zcash.ui.screen.chat.model

import cash.z.ecc.android.sdk.model.Zatoshi
import co.electriccoin.zcash.ui.screen.chat.usecase.CreateChunkedMessageProposalUseCase
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * RED pin for ZChat spending ZEC on automatic handshakes without the user's consent.
 *
 * Observed on the Seeker on 2026-09-07. The wallet had been stranded in the sealed Orchard pool, so
 * every automatic handshake memo (KEX / KEXACK / ZBOOT / time-lock / group-accept) threw
 * `OrchardSealedException`, was swallowed by design, and re-armed its retry marker — the work QUEUED.
 * The owner then refilled the wallet. The moment the new Ironwood note became spendable, a queued KEX
 * flushed and broadcast tx `03360a3ee106`: two 1,000-zatoshi carrier outputs plus a 15,000-zatoshi fee
 * — **17,000 zatoshi, with no user action and no biometric prompt** (automatic senders sign in the
 * background). Nothing on screen said a transaction had been paid for.
 *
 * Two triggers make this reachable without any deliberate spend: `retryPendingTunnelZboots` fires on
 * every block, and merely OPENING a chat sends a KEX. A peer that never completes re-arms forever
 * (`sendNostrBootHandshake`), so the drain is bounded only by the wallet.
 *
 * Every one of those paths funnels through `ChatViewModel.sendHandshakeMemoWithRetry`, and no
 * user-initiated message send does — so consent is required there, once, before the first such spend.
 * These tests pin the decision itself; it is pure so it can be pinned without the ViewModel.
 */
class HandshakeSpendConsentTest {
    @Test
    fun the_first_automatic_handshake_must_ask_before_spending() {
        val decision = decideHandshakeSpend(consentGranted = false, pendingPeers = 1)

        assertEquals(HandshakeSpendDecision.NeedsConsent(1, Zatoshi(HANDSHAKE_COST_ZAT)), decision)
    }

    /** The dialog has to state the whole bill, not the first instalment: this is what flushed at once. */
    @Test
    fun the_quoted_cost_covers_every_queued_peer() {
        val decision = decideHandshakeSpend(consentGranted = false, pendingPeers = 3)

        assertEquals(
            HandshakeSpendDecision.NeedsConsent(3, Zatoshi(3 * HANDSHAKE_COST_ZAT)),
            decision,
            "three queued handshakes cost three carrier outputs and three fees",
        )
    }

    /** Once the user has agreed, handshakes must proceed untouched — the tunnel cannot bootstrap otherwise. */
    @Test
    fun consent_lets_the_handshakes_through() {
        assertEquals(HandshakeSpendDecision.Allowed, decideHandshakeSpend(consentGranted = true, pendingPeers = 3))
        assertEquals(HandshakeSpendDecision.Allowed, decideHandshakeSpend(consentGranted = true, pendingPeers = 1))
    }

    /**
     * The caller asks only when it is ABOUT to spend for at least one peer, so a zero/negative count is
     * a bookkeeping slip, never a licence to spend silently.
     */
    @Test
    fun an_unknown_peer_count_still_asks_and_never_quotes_zero() {
        val decision = decideHandshakeSpend(consentGranted = false, pendingPeers = 0)

        assertEquals(
            HandshakeSpendDecision.NeedsConsent(1, Zatoshi(HANDSHAKE_COST_ZAT)),
            decision,
            "a zero count must still ask, and must never quote zero",
        )
    }

    /**
     * RED pin: the quote must match what the chain actually charges.
     *
     * `buildZip321Uri` ALWAYS appends a second, platform-fee output beside the peer's memo output, so a
     * one-chunk handshake pays 1,000 (peer) + 1,000 (platform fee) + 15,000 (ZIP 317) = 17,000 zatoshi.
     * That is precisely the shape of the unrequested tx observed on the Seeker on 2026-09-07
     * (03360a3ee106…: 1,000 to the peer, 1,000 to the platform-fee address, 15,000 fee). Quoting 16,000
     * understates every handshake by 1,000 zatoshi, so the consent dialog would ask the user to approve
     * a bill smaller than the one they actually pay.
     */
    @Test
    fun one_handshake_costs_the_carrier_plus_platform_fee_output_plus_the_zip317_fee() {
        assertEquals(1_000L, HANDSHAKE_CARRIER_ZAT)
        assertEquals(15_000L, HANDSHAKE_FEE_ZAT)
        assertEquals(17_000L, HANDSHAKE_COST_ZAT)
    }

    /**
     * The quote is pinned to the SAME arithmetic the sender uses, so a change to the platform-fee
     * output or the chunk-cost rule breaks this test instead of silently mis-quoting the user.
     */
    @Test
    fun the_quote_tracks_the_senders_own_cost_formula() {
        val outputs =
            CreateChunkedMessageProposalUseCase
                .totalCost(
                    chunkCount = 1,
                    amountPerOutput = CreateChunkedMessageProposalUseCase.DEFAULT_AMOUNT_PER_OUTPUT,
                )

        assertEquals(
            outputs.value + HANDSHAKE_FEE_ZAT,
            HANDSHAKE_COST_ZAT,
            "the consent quote drifted from CreateChunkedMessageProposalUseCase.totalCost",
        )
    }

    /**
     * Pin for the spend-and-re-arm hole found on 2026-09-07: the ZBOOT catch cleared the "already sent"
     * marker before rethrowing cancellation, so a scope torn down AFTER the broadcast (submission is
     * followed by more suspending work) dropped the latch on money that had already left the wallet and
     * the next block re-sent the same handshake.
     */
    @Test
    fun cancellation_never_re_arms_the_handshake_marker() {
        assertFalse(
            mayReArmHandshakeMarker(kotlinx.coroutines.CancellationException("scope torn down after submit")),
            "cancellation can arrive after the tx was broadcast — re-arming there re-charges the user",
        )
    }

    /** A genuine failure spent nothing, so the retry must be re-armed or the tunnel never bootstraps. */
    @Test
    fun a_real_failure_still_re_arms() {
        assertTrue(mayReArmHandshakeMarker(java.io.IOException("relay unreachable")))
        assertTrue(mayReArmHandshakeMarker(IllegalStateException("no proposal")))
    }
}
