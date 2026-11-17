package co.electriccoin.zcash.ui.common.usecase

import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.common.repository.KeystoneProposalRepository
import co.electriccoin.zcash.ui.common.repository.SwapQuoteData
import co.electriccoin.zcash.ui.common.repository.SwapRepository
import co.electriccoin.zcash.ui.common.repository.ZashiProposalRepository
import co.electriccoin.zcash.ui.keystone.RouteRecordingNavigationRouter
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.lang.reflect.InvocationTargetException
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Clock

/**
 * ZC-960 (review 4 of train T7a1, 14 Oct, nit 6; orchestrator's decision in the register): the send result screen's
 * exit to the transaction history (ViewTransactionsAfterSuccessfulProposalUseCase) clears both proposal repositories and
 * goes to the root, but leaves the swap quote in the swap repository. Since ZC-611 a stored swap or Pay failure leaves
 * through this exit too, and a successful send always did. The proposal is gone, so the quote cannot be paid again; it
 * is a stale price with no flow that owns it.
 *
 * Wanted: the shared exit to the history also drops the swap quote, for every result that leaves through it (success
 * and stored failure alike); the exit itself (both proposals cleared, back to the root) is unchanged.
 *
 * The real use case over repositories with the real clear semantics. Its constructor is read by parameter type, so this
 * file compiles before the use case takes the swap repository. Every value is synthetic.
 */
class ExitToTheHistoryDropsTheSwapQuoteTest {
    /** The swap repository's quote cell with SwapRepositoryImpl's clearQuote: the quote becomes null. */
    private class QuoteHoldingSwapRepository(
        private val fake: Zc101FakeSwapRepository = Zc101FakeSwapRepository(),
    ) : SwapRepository by fake {
        val quoteState get() = fake.quoteState

        override fun clearQuote() {
            fake.quoteState.value = null
        }
    }

    @Test
    fun `the exit to the history after a swap result drops the swap quote`() {
        val shownQuote = Zc101FakeSwapQuote(Clock.System.now())
        val swap = QuoteHoldingSwapRepository().apply { quoteState.value = SwapQuoteData.Success(shownQuote) }
        val zashi = ClearableZashiProposalRepository(runBlocking { syntheticSwapProposal(shownQuote) }, null)
        val keystone = ClearableKeystoneProposalRepository(null)
        val router = RouteRecordingNavigationRouter()

        exitToTheHistory(swap, zashi, keystone, router).invoke()

        assertNull(zashi.transactionProposal.value, "the exit no longer drops the proposal")
        assertEquals(listOf("backToRoot"), router.commands, "the exit itself changed")
        assertNull(swap.quoteState.value, "the swap quote outlived the exit to the history")
    }

    @Test
    fun `control - the fixture holds a quote until something clears it`() {
        val swap = QuoteHoldingSwapRepository().apply { quoteState.value = SwapQuoteData.Success(Zc101FakeSwapQuote(Clock.System.now())) }
        assertNotNull(swap.quoteState.value)
        swap.clearQuote()
        assertNull(swap.quoteState.value)
    }

    /** The use case built from its widest constructor, each parameter by its type. */
    private fun exitToTheHistory(
        swap: SwapRepository,
        zashi: ZashiProposalRepository,
        keystone: KeystoneProposalRepository,
        router: NavigationRouter,
    ): ViewTransactionsAfterSuccessfulProposalUseCase {
        val constructor =
            ViewTransactionsAfterSuccessfulProposalUseCase::class.java.constructors
                .filterNot { it.isSynthetic }
                .maxBy { it.parameterCount }
        val args =
            constructor.parameterTypes.map { type ->
                when (type) {
                    SwapRepository::class.java -> swap
                    ZashiProposalRepository::class.java -> zashi
                    KeystoneProposalRepository::class.java -> keystone
                    NavigationRouter::class.java -> router
                    else -> throw AssertionError("fixture: unexpected constructor parameter ${type.name}")
                }
            }
        return try {
            constructor.newInstance(*args.toTypedArray()) as ViewTransactionsAfterSuccessfulProposalUseCase
        } catch (e: InvocationTargetException) {
            throw e.targetException
        }
    }
}
