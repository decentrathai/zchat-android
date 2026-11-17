package co.electriccoin.zcash.ui.common.usecase

import co.electriccoin.zcash.ui.common.repository.SwapQuoteData
import co.electriccoin.zcash.ui.common.repository.SwapRepository
import co.electriccoin.zcash.ui.keystone.RouteRecordingNavigationRouter
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Clock

/**
 * ZC-767 (review of 12 Oct, ZC-540 claim 2) — a swap quote is left in the swap repository when the send result screen
 * is closed after View Transaction.
 *
 * View Transaction (ViewTransactionDetailAfterSuccessfulProposalUseCase) clears both proposal repositories and not
 * the quote. Back returns to the result screen; its Close runs CancelProposalFlowUseCase, which now finds no proposal
 * and takes its `null` branch. That branch left the flow without `swapRepository.clearQuote()`, unlike the two swap
 * branches. The leftover quote cannot be confirmed again (the proposal it was bound to is gone), so nothing is paid
 * twice; it is a stale price kept in memory with no flow that owns it.
 *
 * Wanted: after View Transaction, Back, Close the swap repository holds no quote. The real two use cases over
 * repositories with the real clear semantics; every address is synthetic.
 */
class ClosingAfterViewTransactionDropsTheSwapQuoteTest {
    /** The swap repository's quote cell with SwapRepositoryImpl's clearQuote: the quote becomes null. */
    private class QuoteHoldingSwapRepository(
        private val fake: Zc101FakeSwapRepository = Zc101FakeSwapRepository(),
    ) : SwapRepository by fake {
        val quoteState get() = fake.quoteState

        override fun clearQuote() {
            fake.quoteState.value = null
        }
    }

    private class Flow(
        private val clearSendForm: Boolean,
    ) {
        private val shownQuote = Zc101FakeSwapQuote(Clock.System.now())
        val swap = QuoteHoldingSwapRepository().apply { quoteState.value = SwapQuoteData.Success(shownQuote) }
        val zashi = ClearableZashiProposalRepository(runBlocking { syntheticSwapProposal(shownQuote) }, null)
        val keystone = ClearableKeystoneProposalRepository(null)
        val router = RouteRecordingNavigationRouter()
        private val close =
            CancelProposalFlowUseCase(
                zashiProposalRepository = zashi,
                keystoneProposalRepository = keystone,
                navigationRouter = router,
                observeClearSend = ObserveClearSendUseCase(),
                accountDataSource = Zc101FakeAccountDataSource(runBlocking { syntheticZashiAccount() }),
                swapRepository = swap,
            )
        private val viewTransaction =
            ViewTransactionDetailAfterSuccessfulProposalUseCase(
                keystoneProposalRepository = keystone,
                zashiProposalRepository = zashi,
                navigationRouter = router,
            )

        fun viewTransactionThenBack() {
            viewTransaction("synthetic-zc767-swap-txid")
            assertNull(zashi.transactionProposal.value, "fixture: View Transaction cleared the proposal")
            assertNotNull(swap.quoteState.value, "fixture: View Transaction leaves the quote")
            router.back()
        }

        suspend fun close() = withTimeout(TIMEOUT_MS) { close(clearSendForm = clearSendForm) }
    }

    @Test
    fun `after View Transaction, Back and Close the swap repository holds no quote`() =
        runBlocking {
            val flow = Flow(clearSendForm = true)
            flow.viewTransactionThenBack()

            flow.close()

            assertNull(flow.swap.quoteState.value, "a swap quote was left behind by the exit of a finished flow")
            assertEquals("backToRoot", flow.router.commands.last(), "the exit itself is unchanged")
        }

    @Test
    fun `the exit that keeps the form drops the leftover quote too`() =
        runBlocking {
            val flow = Flow(clearSendForm = false)
            flow.viewTransactionThenBack()

            flow.close()

            assertNull(flow.swap.quoteState.value, "a swap quote was left behind by the exit of a finished flow")
            assertEquals("backTo(Send)", flow.router.commands.last(), "the exit itself is unchanged")
        }

    private companion object {
        const val TIMEOUT_MS = 3_000L
    }
}
