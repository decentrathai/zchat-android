package co.electriccoin.zcash.ui.screen.send

import cash.z.ecc.android.sdk.Synchronizer
import cash.z.ecc.android.sdk.fixture.AccountFixture
import cash.z.ecc.android.sdk.model.WalletAddress
import cash.z.ecc.android.sdk.model.WalletBalance
import cash.z.ecc.android.sdk.model.Zatoshi
import cash.z.ecc.android.sdk.model.ZecSend
import cash.z.ecc.android.sdk.type.AddressType
import co.electriccoin.zcash.ui.common.datasource.ProposalDataSource
import co.electriccoin.zcash.ui.common.model.FeeProbe
import co.electriccoin.zcash.ui.common.model.SaplingInfo
import co.electriccoin.zcash.ui.common.model.SendMaxUnavailable
import co.electriccoin.zcash.ui.common.model.SynchronizerError
import co.electriccoin.zcash.ui.common.model.TransparentInfo
import co.electriccoin.zcash.ui.common.model.UnifiedInfo
import co.electriccoin.zcash.ui.common.model.WalletAccount
import co.electriccoin.zcash.ui.common.model.ZashiAccount
import co.electriccoin.zcash.ui.common.provider.SynchronizerProvider
import co.electriccoin.zcash.ui.common.repository.ZashiProposalRepositoryImpl
import co.electriccoin.zcash.ui.common.usecase.ComputeSendMaxUseCase
import co.electriccoin.zcash.ui.common.usecase.CreateProposalUseCase
import co.electriccoin.zcash.ui.common.usecase.GetSelectedWalletAccountUseCase
import co.electriccoin.zcash.ui.common.usecase.GetWalletAccountsUseCase
import co.electriccoin.zcash.ui.common.usecase.NavigateToSelectRecipientUseCase
import co.electriccoin.zcash.ui.common.usecase.ObserveABContactPickedUseCase
import co.electriccoin.zcash.ui.common.usecase.ObserveContactByAddressUseCase
import co.electriccoin.zcash.ui.common.usecase.Zc101FakeAccountDataSource
import co.electriccoin.zcash.ui.common.usecase.Zc101FakeAddressBookRepository
import co.electriccoin.zcash.ui.common.usecase.Zc101FakeExchangeRateRepository
import co.electriccoin.zcash.ui.common.usecase.Zc101FakeKeystoneProposalRepository
import co.electriccoin.zcash.ui.common.usecase.Zc101FakeZashiSpendingKeyDataSource
import co.electriccoin.zcash.ui.common.usecase.Zc101RecordingNavigationRouter
import co.electriccoin.zcash.ui.common.usecase.Zc101RecordingProposalDataSource
import co.electriccoin.zcash.ui.common.usecase.Zc101SuspendingBiometricRepository
import co.electriccoin.zcash.ui.common.wallet.ExchangeRateState
import co.electriccoin.zcash.ui.screen.chat.usecase.zip317ChatPrice
import co.electriccoin.zcash.ui.screen.send.model.AmountState
import co.electriccoin.zcash.ui.screen.send.model.RecipientAddressState
import co.electriccoin.zcash.ui.screen.send.model.SendAllState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The wallet Send-all path from the tap to the fill (W-1, W-2, W-3; review follow-up).
 *
 * The RED pin (androidTest SendViewSendAllTest) hosts SendFormAmountTextField without an onSendAllClick,
 * so since the fill moved into SendViewModel its tap does nothing and its assertions pass on an empty
 * field. This covers what the composable no longer does: real SendViewModel, real ComputeSendMaxUseCase,
 * and a ProposalDataSource whose probeFee prices like the SDK ([zip317ChatPrice]). Main is an unconfined
 * test dispatcher; the use case runs on Dispatchers.Default, so outcomes are awaited, never slept on.
 *
 * Owner state: 443,002 zatoshi of Ironwood in one note, 139,000 of sealed Orchard. All addresses are
 * synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SendViewModelSendAllTest {
    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun a_tap_fills_ironwood_minus_the_real_fee_and_submits_nothing() =
        harness { vm, dataSource ->
            val filled = CompletableDeferred<Zatoshi>()

            vm.onSendAllClick { filled.complete(it) }

            assertEquals(Zatoshi(433_002), withTimeout(TIMEOUT_MS) { filled.await() })
            assertTrue(dataSource.probed.isNotEmpty())
            dataSource.probed.forEach { send ->
                assertEquals(PEER, send.destination.address, "every probe prices the entered recipient")
            }
            assertTrue(dataSource.recording.submittedProposals.isEmpty(), "a probe is never submitted")
            withTimeout(TIMEOUT_MS) { vm.sendAllState.first { it == SendAllState.Ready } }
        }

    @Test
    fun editing_the_form_while_pricing_cancels_the_fill() =
        harness { vm, dataSource ->
            val gate = dataSource.holdNextProbe()
            val filled = CompletableDeferred<Zatoshi>()

            vm.onSendAllClick { filled.complete(it) }
            withTimeout(TIMEOUT_MS) { vm.sendAllState.first { it == SendAllState.Calculating } }
            vm.onFormEdited()

            withTimeout(TIMEOUT_MS) { gate.cancelled.await() }
            assertFalse(filled.isCompleted, "an edited form is never overwritten by a late fill")
            withTimeout(TIMEOUT_MS) { vm.sendAllState.first { it == SendAllState.Ready } }
        }

    @Test
    fun changing_the_recipient_while_pricing_cancels_the_fill() =
        harness { vm, dataSource ->
            val gate = dataSource.holdNextProbe()
            val filled = CompletableDeferred<Zatoshi>()

            vm.onSendAllClick { filled.complete(it) }
            withTimeout(TIMEOUT_MS) { vm.sendAllState.first { it == SendAllState.Calculating } }
            vm.onRecipientAddressChanged(RecipientAddressState(PEER_TRANSPARENT, AddressType.Transparent))

            withTimeout(TIMEOUT_MS) { gate.cancelled.await() }
            assertFalse(filled.isCompleted, "a fill priced for another recipient is never applied")
        }

    /** A fill priced for a shielded recipient is cleared, once, when the recipient turns transparent. */
    @Test
    fun a_fill_priced_for_another_kind_of_recipient_is_cleared_once() =
        harness { vm, _ ->
            val filled = CompletableDeferred<Zatoshi>()
            vm.onSendAllClick { filled.complete(it) }
            val fill = withTimeout(TIMEOUT_MS) { filled.await() }
            val transparent = RecipientAddressState(PEER_TRANSPARENT, AddressType.Transparent)

            assertFalse(
                vm.consumeStaleSendAllFill(RecipientAddressState(PEER, AddressType.Unified), amount(fill)),
                "same kind of recipient: the fill still applies",
            )
            assertTrue(vm.consumeStaleSendAllFill(transparent, amount(fill)))
            assertFalse(vm.consumeStaleSendAllFill(transparent, amount(fill)), "cleared once, not again")
        }

    @Test
    fun an_amount_the_user_replaced_is_never_cleared() =
        harness { vm, _ ->
            val filled = CompletableDeferred<Zatoshi>()
            vm.onSendAllClick { filled.complete(it) }
            withTimeout(TIMEOUT_MS) { filled.await() }

            assertFalse(
                vm.consumeStaleSendAllFill(
                    RecipientAddressState(PEER_TRANSPARENT, AddressType.Transparent),
                    amount(Zatoshi(100_000)),
                ),
            )
        }

    /** W-3: every Ironwood note still confirming. The link says so; a tap neither probes nor fills 0. */
    @Test
    fun a_confirming_wallet_is_never_filled() =
        harness(ironwood = WalletBalance(Zatoshi(0), Zatoshi(443_002), Zatoshi(0))) { vm, dataSource ->
            val filled = CompletableDeferred<Zatoshi>()

            vm.onSendAllClick { filled.complete(it) }

            assertEquals(
                SendAllState.Unavailable(SendMaxUnavailable.StillConfirming(Zatoshi(443_002))),
                vm.sendAllState.value,
            )
            assertFalse(filled.isCompleted)
            assertTrue(dataSource.probed.isEmpty())
        }

    // --------------------------------------------------------------------------------- harness

    /** A probe the test can hold, and learn that it was cancelled while held. */
    private class HeldProbe {
        val release = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
    }

    /** Prices every probe like the SDK over [ironwoodNotes]; keeps and submits nothing. */
    private class PricingProposalDataSource(
        private val ironwoodNotes: List<Long>,
        val recording: Zc101RecordingProposalDataSource = Zc101RecordingProposalDataSource(),
    ) : ProposalDataSource by recording {
        val probed = mutableListOf<ZecSend>()

        @Volatile
        private var held: HeldProbe? = null

        fun holdNextProbe(): HeldProbe = HeldProbe().also { held = it }

        override suspend fun probeFee(
            account: WalletAccount,
            send: ZecSend,
        ): FeeProbe {
            held?.let { probe ->
                try {
                    probe.release.await()
                } catch (e: CancellationException) {
                    probe.cancelled.complete(Unit)
                    throw e
                }
            }
            probed += send
            return zip317ChatPrice(ironwoodNotes, listOf(send.amount.value))
        }
    }

    private class NoSynchronizer : SynchronizerProvider {
        override val error: StateFlow<SynchronizerError?> = MutableStateFlow(null)
        override val synchronizer: StateFlow<Synchronizer?> = MutableStateFlow(null)

        override suspend fun getSynchronizer(): Synchronizer = awaitCancellation()

        override fun resetSynchronizer() = Unit
    }

    private fun harness(
        ironwood: WalletBalance = WalletBalance(Zatoshi(IRONWOOD), Zatoshi(0), Zatoshi(0)),
        block: suspend CoroutineScope.(SendViewModel, PricingProposalDataSource) -> Unit,
    ) = runBlocking {
        val account = account(ironwood)
        val accountDataSource = Zc101FakeAccountDataSource(account)
        val dataSource = PricingProposalDataSource(ironwoodNotes = listOf(ironwood.available.value))
        val router = Zc101RecordingNavigationRouter()
        val zashiRepository =
            ZashiProposalRepositoryImpl(
                accountDataSource = accountDataSource,
                proposalDataSource = dataSource,
                zashiSpendingKeyDataSource = Zc101FakeZashiSpendingKeyDataSource(),
            )
        val vm =
            SendViewModel(
                exchangeRateRepository = Zc101FakeExchangeRateRepository(),
                observeContactByAddress = ObserveContactByAddressUseCase(Zc101FakeAddressBookRepository()),
                observeContactPicked = ObserveABContactPickedUseCase(NoSynchronizer()),
                createProposal =
                    CreateProposalUseCase(
                        keystoneProposalRepository = Zc101FakeKeystoneProposalRepository(),
                        zashiProposalRepository = zashiRepository,
                        accountDataSource = accountDataSource,
                        navigationRouter = router,
                    ),
                observeWalletAccounts = GetWalletAccountsUseCase(accountDataSource),
                navigateToSelectRecipient =
                    NavigateToSelectRecipientUseCase(router, Zc101SuspendingBiometricRepository()),
                navigationRouter = router,
                getSelectedWalletAccount = GetSelectedWalletAccountUseCase(accountDataSource),
                computeSendMax = ComputeSendMaxUseCase(accountDataSource, dataSource),
            )
        // The screen collects the link state; onSendAllClick reads it.
        val screen = launch(Dispatchers.Unconfined) { vm.sendAllState.collect {} }
        vm.onRecipientAddressChanged(RecipientAddressState(PEER, AddressType.Unified))
        withTimeout(TIMEOUT_MS) { vm.sendAllState.first { it != SendAllState.Hidden } }
        try {
            block(vm, dataSource)
        } finally {
            screen.cancel()
        }
    }

    private companion object {
        const val IRONWOOD = 443_002L
        const val TIMEOUT_MS = 5_000L

        /** Bare-address shaped. Synthetic, never a real address. */
        val PEER = "u1synthetic" + "peer".repeat(25)
        val PEER_TRANSPARENT = "t1synthetic" + "peer".repeat(6)

        fun amount(zatoshi: Zatoshi): AmountState =
            AmountState.newFromZec(
                locale = Locale.US,
                value =
                    zatoshi.value
                        .toBigDecimal()
                        .movePointLeft(8)
                        .toPlainString(),
                fiatValue = "",
                isTransparentOrTextRecipient = false,
                exchangeRateState = ExchangeRateState.OptedOut,
            )

        fun balance(available: Long) = WalletBalance(Zatoshi(available), Zatoshi(0), Zatoshi(0))

        suspend fun account(ironwood: WalletBalance) =
            ZashiAccount(
                sdkAccount = AccountFixture.new(),
                unified = UnifiedInfo(WalletAddress.Unified.new("usynthetic-self"), balance(139_000), ironwood),
                sapling = SaplingInfo(WalletAddress.Sapling.new("zsynthetic-self"), balance(0)),
                transparent = TransparentInfo(WalletAddress.Transparent.new("tsynthetic-self"), Zatoshi(0)),
                isSelected = true,
            )
    }
}
