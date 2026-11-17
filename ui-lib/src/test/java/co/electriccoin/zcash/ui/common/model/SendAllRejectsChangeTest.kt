package co.electriccoin.zcash.ui.common.model

import cash.z.ecc.android.sdk.fixture.AccountFixture
import cash.z.ecc.android.sdk.model.WalletAddress
import cash.z.ecc.android.sdk.model.WalletBalance
import cash.z.ecc.android.sdk.model.Zatoshi
import co.electriccoin.zcash.ui.screen.chat.usecase.ChatSendAllQuote
import co.electriccoin.zcash.ui.screen.chat.usecase.CreateChunkedMessageProposalUseCase
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * F9 (fix-all 2.13.0, train T1): a Send all that keeps nothing is accepted only when the proposal itself leaves no
 * change.
 *
 * `SendMaxSearch.keepsWhole` checks the change only when the shape keeps value back (C-8). For the wallet shape
 * (keeps == 0) the "no change" proof rests on a balance snapshot: the inputs are Ironwood notes summing to at most V,
 * and they must cover `A + fee = V`. If V moved between the read and the probe (a note arrived), the SDK can build a
 * proposal at exactly the expected fee that leaves change, and it is accepted as "Send all" although it does not send
 * everything. A change that cannot be read is accepted too.
 *
 * Wanted: with keeps == 0 the proposal's own change must be readable and carry no value (no change output, or only a
 * zero-value one, as the SDK may add); anything else is CouldNotPrice. The chat send-time guard
 * (`proposalMatchesSendAll`) applies the same rule. What a reserve-keeping Send all needs is unchanged
 * (ChatSendAllReserveChangeTest). All addresses are synthetic.
 */
class SendAllRejectsChangeTest {
    @Test
    fun a_send_all_whose_proposal_leaves_change_is_not_filled() =
        runTest {
            assertEquals(COULD_NOT_PRICE, search(change = listOf(ironwood(5_000))))
        }

    @Test
    fun a_send_all_whose_change_cannot_be_read_is_not_filled() =
        runTest {
            assertEquals(COULD_NOT_PRICE, search(change = null))
        }

    /** Control: no change output, or a zero-value one, is a sweep. */
    @Test
    fun no_change_or_a_zero_value_change_is_filled() =
        runTest {
            assertEquals(MAX, search(change = emptyList()))
            assertEquals(MAX, search(change = listOf(ironwood(0))))
        }

    @Test
    fun the_chat_send_time_guard_applies_the_same_rule() {
        val quote =
            ChatSendAllQuote(
                recipient = PEER,
                chunkCount = 1,
                keeps = Zatoshi(0),
                amount = Zatoshi(428_002),
                fee = Zatoshi(FEE),
            )

        assertFalse(matches(quote, listOf(ironwood(5_000))), "a proposal that leaves change")
        assertFalse(matches(quote, null), "a change that cannot be read")
        assertTrue(matches(quote, emptyList()), "no change")
        assertTrue(matches(quote, listOf(ironwood(0))), "a zero-value change")
    }

    private suspend fun search(change: List<ProposalChange>?): SendMaxResult =
        SendMaxSearch.search(
            recipient = SendMaxRecipient.SHIELDED,
            readAccount = { account() },
            // Two Ironwood notes swept at the two-action fee: the only fee this wallet is ever charged.
            probe = { _, _ -> FeeProbeBuilt.of(FEE, listOf(PROPOSAL_POOL_IRONWOOD, PROPOSAL_POOL_IRONWOOD), change) },
        )

    private fun matches(
        quote: ChatSendAllQuote,
        change: List<ProposalChange>?
    ) = CreateChunkedMessageProposalUseCase.proposalMatchesSendAll(
        quote = quote,
        destination = PEER,
        amount = quote.amount,
        fee = quote.fee,
        transactionCount = 1,
        usesOrchardInputs = false,
        memoCount = 1,
        change = change,
    )

    private companion object {
        const val FEE = 10_000L
        const val IRONWOOD_BALANCE = 443_002L

        val MAX = SendMaxResult.Max(Zatoshi(IRONWOOD_BALANCE - FEE), Zatoshi(FEE))
        val COULD_NOT_PRICE = SendMaxResult.Unavailable(SendMaxUnavailable.CouldNotPrice)

        /** Bare-address shaped. Synthetic, never a real address. */
        val PEER = "u1synthetic" + "peer".repeat(25)

        fun ironwood(value: Long) = ProposalChange(value, PROPOSAL_POOL_IRONWOOD)

        fun balance(available: Long) = WalletBalance(Zatoshi(available), Zatoshi(0), Zatoshi(0))

        suspend fun account() =
            ZashiAccount(
                sdkAccount = AccountFixture.new(),
                unified =
                    UnifiedInfo(
                        WalletAddress.Unified.new("usynthetic-self"),
                        balance(0),
                        balance(IRONWOOD_BALANCE),
                    ),
                sapling = SaplingInfo(WalletAddress.Sapling.new("zsynthetic-self"), balance(0)),
                transparent = TransparentInfo(WalletAddress.Transparent.new("tsynthetic-self"), Zatoshi(0)),
                isSelected = true,
            )
    }
}
