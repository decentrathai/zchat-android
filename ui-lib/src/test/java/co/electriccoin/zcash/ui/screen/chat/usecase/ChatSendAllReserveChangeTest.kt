package co.electriccoin.zcash.ui.screen.chat.usecase

import cash.z.ecc.android.sdk.Synchronizer
import cash.z.ecc.android.sdk.fixture.AccountFixture
import cash.z.ecc.android.sdk.model.Proposal
import cash.z.ecc.android.sdk.model.WalletAddress
import cash.z.ecc.android.sdk.model.WalletBalance
import cash.z.ecc.android.sdk.model.Zatoshi
import co.electriccoin.zcash.ui.common.datasource.ProposalDataSourceImpl
import co.electriccoin.zcash.ui.common.model.FeeProbe
import co.electriccoin.zcash.ui.common.model.FeeProbeBuilt
import co.electriccoin.zcash.ui.common.model.PROPOSAL_POOL_IRONWOOD
import co.electriccoin.zcash.ui.common.model.PROPOSAL_POOL_ORCHARD
import co.electriccoin.zcash.ui.common.model.ProposalChange
import co.electriccoin.zcash.ui.common.model.SaplingInfo
import co.electriccoin.zcash.ui.common.model.SendMaxRecipient
import co.electriccoin.zcash.ui.common.model.SendMaxResult
import co.electriccoin.zcash.ui.common.model.SendMaxSearch
import co.electriccoin.zcash.ui.common.model.SendMaxUnavailable
import co.electriccoin.zcash.ui.common.model.SweepProposalBytes
import co.electriccoin.zcash.ui.common.model.SynchronizerError
import co.electriccoin.zcash.ui.common.model.TransparentInfo
import co.electriccoin.zcash.ui.common.model.UnifiedInfo
import co.electriccoin.zcash.ui.common.model.ZashiAccount
import co.electriccoin.zcash.ui.common.provider.SynchronizerProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.lang.reflect.Proxy
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The owner's C-8 rule (2026-09-10), enforced from the proposal itself: a chat Send All that keeps the
 * handshake reserve is offered only when the proposal leaves that reserve as ONE Ironwood change note of
 * exactly the reserve. A change that is split, in another pool, or cannot be read is
 * [SendMaxUnavailable.CouldNotPrice]; the same rule stops the send when the proposal built at send time
 * differs. A Send All that keeps nothing is unchanged. All addresses are synthetic.
 */
class ChatSendAllReserveChangeTest {
    @Test
    fun only_one_ironwood_note_of_exactly_the_reserve_is_accepted() =
        runTest {
            val cases =
                mapOf(
                    listOf(ironwood(KEEPS)) to SendMaxResult.Max(Zatoshi(410_002), Zatoshi(FEE)),
                    null to COULD_NOT_PRICE,
                    emptyList<ProposalChange>() to COULD_NOT_PRICE,
                    listOf(ironwood(12_000), ironwood(5_000)) to COULD_NOT_PRICE,
                    listOf(ironwood(KEEPS - 1)) to COULD_NOT_PRICE,
                    listOf(ProposalChange(KEEPS, PROPOSAL_POOL_ORCHARD)) to COULD_NOT_PRICE,
                )

            cases.forEach { (change, expected) ->
                assertEquals(expected, search(keeps = KEEPS, change = change), "change $change")
            }
        }

    /**
     * F9 (fix-all 2.13.0, train T1): a Send All that keeps nothing still needs a change it can read, with no value in
     * it (SendAllRejectsChangeTest). An unreadable change used to be accepted here.
     */
    @Test
    fun a_send_all_that_keeps_nothing_needs_a_readable_change_without_value() =
        runTest {
            assertEquals(SendMaxResult.Max(Zatoshi(427_002), Zatoshi(FEE)), search(keeps = 0, change = emptyList()))
            assertEquals(COULD_NOT_PRICE, search(keeps = 0, change = null))
        }

    @Test
    fun the_send_time_guard_applies_the_same_rule() {
        val quote =
            ChatSendAllQuote(
                recipient = PEER,
                chunkCount = 1,
                keeps = Zatoshi(KEEPS),
                amount = Zatoshi(410_002),
                fee = Zatoshi(FEE),
            )

        assertTrue(matches(quote, listOf(ironwood(KEEPS))))
        assertFalse(matches(quote, null), "an unreadable change")
        assertFalse(matches(quote, listOf(ironwood(5_000))), "a split reserve")
        assertTrue(matches(quote.copy(keeps = Zatoshi(0), amount = Zatoshi(427_002)), emptyList()), "nothing kept")
        assertFalse(
            matches(quote.copy(keeps = Zatoshi(0), amount = Zatoshi(427_002)), null),
            "nothing kept, but a change that cannot be read",
        )
    }

    /** The production probe reads the change from the proposal the SDK built. */
    @Test
    fun the_zip321_probe_reports_the_proposal_change() =
        runTest {
            val change = listOf(ironwood(KEEPS))
            val proposal =
                SweepProposalBytes.proposal(
                    inputs = listOf(SweepProposalBytes.ironwood(443_002)),
                    fee = FEE,
                    paymentPools = listOf(PROPOSAL_POOL_IRONWOOD, PROPOSAL_POOL_IRONWOOD),
                    change = change,
                )
            val dataSource = ProposalDataSourceImpl(AnsweringSynchronizerProvider(proposal))

            val probe = dataSource.probeZip321Fee(account(), "zcash:$PEER?amount=0.00410002")

            val built = probe as FeeProbe.Built
            assertEquals(Zatoshi(FEE), built.fee)
            assertFalse(built.usesOrchardInputs)
            assertEquals(1, built.transactionCount)
            assertEquals(change, built.change)
            // F9: the probe also reports the proposal's input pools.
            assertEquals(listOf(PROPOSAL_POOL_IRONWOOD), FeeProbeBuilt.inputPoolsOf(built))
        }

    private suspend fun search(
        keeps: Long,
        change: List<ProposalChange>?
    ): SendMaxResult =
        SendMaxSearch.search(
            recipient = SendMaxRecipient.SHIELDED,
            readAccount = { account() },
            // A one-chunk send of one 443,002 note: 15,000 is the only fee it is ever charged.
            probe = { _, _ -> FeeProbeBuilt.of(FEE, listOf(PROPOSAL_POOL_IRONWOOD), change) },
            shape = CreateChunkedMessageProposalUseCase.sendAllShape(1, Zatoshi(keeps)),
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

    /** Answers the ZIP 321 proposal call with [proposal]; any other call is a fixture bug. */
    private class AnsweringSynchronizerProvider(
        proposal: Proposal
    ) : SynchronizerProvider {
        override val error: StateFlow<SynchronizerError?> = MutableStateFlow(null)

        private val answering: Synchronizer =
            Proxy.newProxyInstance(
                Synchronizer::class.java.classLoader,
                arrayOf(Synchronizer::class.java),
            ) { _, method, _ ->
                if (method.name == "proposeFulfillingPaymentUri") proposal else error("unexpected ${method.name}")
            } as Synchronizer

        override val synchronizer: StateFlow<Synchronizer?> = MutableStateFlow(answering)

        override suspend fun getSynchronizer(): Synchronizer = answering

        override fun resetSynchronizer() = Unit
    }

    private companion object {
        const val KEEPS = 17_000L
        const val FEE = 15_000L
        const val IRONWOOD_BALANCE = 443_002L

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
                        balance(139_000),
                        balance(IRONWOOD_BALANCE),
                    ),
                sapling = SaplingInfo(WalletAddress.Sapling.new("zsynthetic-self"), balance(0)),
                transparent = TransparentInfo(WalletAddress.Transparent.new("tsynthetic-self"), Zatoshi(0)),
                isSelected = true,
            )
    }
}
