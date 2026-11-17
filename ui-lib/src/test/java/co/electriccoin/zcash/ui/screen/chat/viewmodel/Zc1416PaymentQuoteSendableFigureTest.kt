package co.electriccoin.zcash.ui.screen.chat.viewmodel

import cash.z.ecc.android.sdk.model.WalletBalance
import cash.z.ecc.android.sdk.model.Zatoshi
import co.electriccoin.zcash.ui.common.model.FeeProbe
import co.electriccoin.zcash.ui.common.repository.BiometricRepository
import co.electriccoin.zcash.ui.common.repository.BiometricRequest
import co.electriccoin.zcash.ui.common.repository.BiometricResult
import co.electriccoin.zcash.ui.common.repository.ZashiProposalRepositoryImpl
import co.electriccoin.zcash.ui.common.usecase.Zc101FakeAccountDataSource
import co.electriccoin.zcash.ui.common.usecase.Zc101FakeKeystoneProposalRepository
import co.electriccoin.zcash.ui.common.usecase.Zc101FakeZashiSpendingKeyDataSource
import co.electriccoin.zcash.ui.common.usecase.Zc101RecordingNavigationRouter
import co.electriccoin.zcash.ui.common.usecase.syntheticZashiAccount
import co.electriccoin.zcash.ui.screen.chat.model.PaymentQuote
import co.electriccoin.zcash.ui.screen.chat.model.paymentOutlay
import co.electriccoin.zcash.ui.screen.chat.usecase.CreateChunkedMessageProposalUseCase
import co.electriccoin.zcash.ui.screen.chat.viewmodel.PaymentHarness.paymentViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * ZC-1416 (device-qa round 4 on c39a0f61c, Seeker release payment form, TIMELINE4 12:31-12:34): one 18,000-zatoshi Ironwood
 * note, a payment of 1,000 (outlay 17,000). The SDK refuses (the 1,000 change would be dust), so quotePayment answers
 * `NotEnough(floor = 17,000, spendable = ironwood.available = 18,000)` and the form says "this payment needs at least
 * 0.00017 ZEC, and 0.00018 ZEC can be sent now" while Send stays off: the figure shown as sendable is not what the SDK
 * will accept.
 *
 * Wanted: when the SDK refuses, the quote never names a sendable figure that covers the floor it says is missing. Either
 * NotEnough with `spendable < floor` (what the SDK really accepts: available minus notes it cannot use), or a quote that
 * says why (an Unavailable with its own reason, e.g. the change-would-be-dust rule) - never a silent Unavailable.
 *
 * Drives the real ChatViewModel.quotePayment, CreateChunkedMessageProposalUseCase and ZashiProposalRepositoryImpl through
 * [PaymentHarness]; the SDK's refusal is the seam's FeeProbe.Short. All addresses are synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class Zc1416PaymentQuoteSendableFigureTest {
    private val sendScopes = SendHarnessScopes()

    @Before
    fun setUp() {
        sendScopes.open()
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        sendScopes.close()
        Dispatchers.resetMain()
    }

    @Test
    fun `ZC-1416 an SDK refusal on one 18,000 note never says 18,000 can be sent for a 17,000 payment`() =
        runBlocking {
            val (vm, seam) = fixture(PEER, ironwoodAvailable = 18_000L)
            seam.feeProbe = { FeeProbe.Short }

            val quote = vm.quotePayment(PEER, 1_000L, "", rawMemo = false)
            val floor = paymentOutlay(Zatoshi(1_000L), 1)
            assertEquals(Zatoshi(17_000L), floor, "fixture: a 1,000 payment's outlay is 17,000")

            when (quote) {
                is PaymentQuote.NotEnough ->
                    assertTrue(
                        quote.spendable < quote.floor,
                        "ZC-1416: the SDK refused, yet the quote says ${quote.spendable.value} zatoshi can be sent now " +
                            "against a floor of ${quote.floor.value} (the form reads 'needs at least 0.00017 ZEC, and " +
                            "0.00018 ZEC can be sent now')",
                    )
                is PaymentQuote.Unavailable ->
                    assertNotNull(quote.reason, "ZC-1416: a refused payment must say why, not 'could not calculate the price'")
                else -> fail("ZC-1416: the SDK refused this payment, so the quote cannot be $quote")
            }
            assertEquals(0, seam.submits.get(), "a quote submits nothing")
            assertEquals(0, seam.proposalUris.size, "a quote builds no proposal")
        }

    @Test
    fun `control - a wallet that really has less than the floor is told both figures`() =
        runBlocking {
            val (vm, seam) = fixture(PEER_2, ironwoodAvailable = 12_000L)
            seam.feeProbe = { FeeProbe.Short }

            val quote = vm.quotePayment(PEER_2, 1_000L, "", rawMemo = false)

            val notEnough = assertNotNull(quote as? PaymentQuote.NotEnough, "a short wallet is not told so: $quote")
            assertEquals(Zatoshi(17_000L), notEnough.floor)
            assertEquals(Zatoshi(12_000L), notEnough.spendable)
        }

    // ── fixture (as PaymentConfirmsQuoteExactPriceTest) ─────────────────────────────────────────────

    private suspend fun fixture(
        peer: String,
        ironwoodAvailable: Long,
    ): Pair<ChatViewModel, PaymentSeam> {
        val prefs = PaymentPrefs(peer)
        val seam = PaymentSeam(peer)
        val vm = paymentViewModel(prefs, seam)
        val base = syntheticZashiAccount()
        val account =
            base.copy(
                unified = base.unified.copy(ironwood = WalletBalance(Zatoshi(ironwoodAvailable), Zatoshi(0), Zatoshi(0))),
            )
        val accounts = Zc101FakeAccountDataSource(account)
        val useCase =
            CreateChunkedMessageProposalUseCase(
                keystoneProposalRepository = Zc101FakeKeystoneProposalRepository(),
                zashiProposalRepository =
                    ZashiProposalRepositoryImpl(
                        accountDataSource = accounts,
                        proposalDataSource = seam,
                        zashiSpendingKeyDataSource = Zc101FakeZashiSpendingKeyDataSource(),
                    ).also(SendHarnessScopes.current()::own),
                accountDataSource = accounts,
                navigationRouter = Zc101RecordingNavigationRouter(),
                biometricRepository = NoBiometrics,
            )
        PaymentHarness.setField(vm, "createChunkedMessageProposal", useCase)
        PaymentHarness.setField(vm, "accountDataSource", accounts)
        return vm to seam
    }

    private object NoBiometrics : BiometricRepository {
        override val allowedAuthenticators: Int = 0

        override fun onBiometricResult(result: BiometricResult) = Unit

        override suspend fun requestBiometrics(request: BiometricRequest) = Unit
    }

    private companion object {
        const val PEER = "usyntheticzc1416quote00000001"
        const val PEER_2 = "usyntheticzc1416quote00000002"
    }
}
