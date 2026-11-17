package co.electriccoin.zcash.ui.screen.moveoldbalance

import cash.z.ecc.android.sdk.fixture.AccountFixture
import cash.z.ecc.android.sdk.model.Proposal
import cash.z.ecc.android.sdk.model.UnifiedSpendingKey
import cash.z.ecc.android.sdk.model.WalletAddress
import cash.z.ecc.android.sdk.model.WalletBalance
import cash.z.ecc.android.sdk.model.Zatoshi
import co.electriccoin.zcash.ui.common.datasource.OrchardSweepTransactionProposal
import co.electriccoin.zcash.ui.common.datasource.ProposalDataSource
import co.electriccoin.zcash.ui.common.model.SaplingInfo
import co.electriccoin.zcash.ui.common.model.SubmitResult
import co.electriccoin.zcash.ui.common.model.SweepProposalBytes
import co.electriccoin.zcash.ui.common.model.TransparentInfo
import co.electriccoin.zcash.ui.common.model.UnifiedInfo
import co.electriccoin.zcash.ui.common.model.WalletAccount
import co.electriccoin.zcash.ui.common.model.ZashiAccount
import co.electriccoin.zcash.ui.common.provider.PersistableWalletTorProvider
import co.electriccoin.zcash.ui.common.provider.TorState
import co.electriccoin.zcash.ui.common.repository.ZashiProposalRepositoryImpl
import co.electriccoin.zcash.ui.common.usecase.IsTorEnabledUseCase
import co.electriccoin.zcash.ui.common.usecase.Zc101FakeAccountDataSource
import co.electriccoin.zcash.ui.common.usecase.Zc101FakeZashiSpendingKeyDataSource
import co.electriccoin.zcash.ui.common.usecase.Zc101RecordingNavigationRouter
import co.electriccoin.zcash.ui.common.usecase.Zc101RecordingProposalDataSource
import co.electriccoin.zcash.ui.common.usecase.Zc101SuspendingBiometricRepository
import co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.lang.reflect.Proxy
import kotlin.reflect.KClass
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * R4-1 (round 3 report, section 4 item 2) — the move of the old balance is sent although the consent reset
 * it depends on never reached the disk.
 *
 * Owner decision 2 (2026-09-10): confirming a move clears the handshake-spend consent BEFORE the move is
 * sent, so the queued KEX/ZBOOT/GROUP_ACCEPT handshakes must ask again before they can spend the moved
 * funds. `clearConsentedToHandshakeSpend` is written in the background (`apply()`) and its outcome is not
 * looked at, so a process kill in that window — or any failed write — leaves consent standing on disk while
 * the move goes out. The queued handshakes then flush against the moved balance with nothing on screen,
 * which is the 2026-09-07 incident this consent exists to prevent.
 *
 * Wanted: no move is handed to the SDK until the store confirms the consent reset. Nothing is sent, the
 * sheet says so, and the user can confirm again.
 *
 * All addresses, keys and txids are synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MoveOldBalanceConsentClearMustReachDiskTest {
    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun a_move_is_not_sent_when_the_consent_reset_did_not_reach_the_disk() =
        runBlocking {
            val prefs = Prefs(consented = true, clearReachesDisk = false)
            val source = SweepSource()
            val vm = sheet(source, prefs)

            vm.await(MoveOldBalanceState.Consent::class).onContinue()
            vm.current<MoveOldBalanceState.Review>().onConfirm()
            val failed = vm.await(MoveOldBalanceState.Failed::class)

            assertTrue(
                source.settled.isEmpty(),
                "the move was handed to the SDK although the consent reset never reached the disk; " +
                    "queued handshakes can spend the moved funds without asking",
            )
            assertFalse(failed.isOutcomeUnknown, "nothing was sent, so the outcome is not unknown")
            assertFalse(failed.isNetworkFailure)
        }

    @Test
    fun the_refused_move_can_be_confirmed_again_once_the_store_works() =
        runBlocking {
            val prefs = Prefs(consented = true, clearReachesDisk = false)
            val source = SweepSource()
            val vm = sheet(source, prefs)

            vm.await(MoveOldBalanceState.Consent::class).onContinue()
            val review = vm.current<MoveOldBalanceState.Review>()
            review.onConfirm()
            vm.await(MoveOldBalanceState.Failed::class)

            prefs.clearReachesDisk = true
            review.onConfirm()
            vm.await(MoveOldBalanceState.Success::class)

            assertEquals(listOf(source.proposal), source.settled, "the move went out once, after the reset stuck")
            assertFalse(prefs.consented)
        }

    /** Control (passes before and after): a reset that reaches the disk lets the move go out as before. */
    @Test
    fun control_a_reset_that_reached_the_disk_sends_the_move() =
        runBlocking {
            val prefs = Prefs(consented = true, clearReachesDisk = true)
            val source = SweepSource()
            val vm = sheet(source, prefs)

            vm.await(MoveOldBalanceState.Consent::class).onContinue()
            vm.current<MoveOldBalanceState.Review>().onConfirm()
            vm.await(MoveOldBalanceState.Success::class)

            assertEquals(listOf(source.proposal), source.settled)
            assertFalse(prefs.consented)
        }

    // --------------------------------------------------------------------------------- harness

    private suspend fun sheet(
        source: SweepSource,
        prefs: Prefs,
    ): MoveOldBalanceVM {
        val accounts = Zc101FakeAccountDataSource(account())
        return MoveOldBalanceVM(
            accountDataSource = accounts,
            zashiProposalRepository =
                ZashiProposalRepositoryImpl(
                    accountDataSource = accounts,
                    proposalDataSource = source,
                    zashiSpendingKeyDataSource = Zc101FakeZashiSpendingKeyDataSource(),
                ),
            biometricRepository = Zc101SuspendingBiometricRepository().apply { approve() },
            navigationRouter = Zc101RecordingNavigationRouter(),
            isTorEnabled = IsTorEnabledUseCase(TorOn),
            zchatPreferences = prefs.proxy(),
        )
    }

    private fun <T : MoveOldBalanceState> MoveOldBalanceVM.await(type: KClass<T>): T =
        runBlocking {
            @Suppress("UNCHECKED_CAST")
            withTimeout(TIMEOUT_MS) { state.first { type.isInstance(it) } } as T
        }

    private inline fun <reified T : MoveOldBalanceState> MoveOldBalanceVM.current(): T {
        val value = state.value
        assertTrue(value is T, "expected ${T::class.simpleName}, got $value")
        return value
    }

    private class SweepSource(
        recording: Zc101RecordingProposalDataSource = Zc101RecordingProposalDataSource(),
    ) : ProposalDataSource by recording {
        val proposal: Proposal = SweepProposalBytes.proposal(listOf(SweepProposalBytes.orchard(139_000)), fee = 20_000)
        val settled = mutableListOf<Proposal>()

        override suspend fun createOrchardSweepProposal(account: WalletAccount): OrchardSweepTransactionProposal =
            OrchardSweepTransactionProposal.fromProposal(proposal)

        override suspend fun submitTransaction(
            proposal: Proposal,
            usk: UnifiedSpendingKey
        ): SubmitResult {
            synchronized(settled) { settled += proposal }
            return SubmitResult.Success(listOf("synthetic-sweep-txid"))
        }
    }

    private object TorOn : PersistableWalletTorProvider {
        override fun observe(): Flow<TorState?> = flowOf(TorState.EXPLICITLY_ENABLED)

        override suspend fun get(): TorState = TorState.EXPLICITLY_ENABLED
    }

    /**
     * Handshake consent behind a [ZchatPreferences] proxy whose reset reaches the disk only when
     * [clearReachesDisk]. A reset that did not reach the disk leaves the stored value standing, which is
     * what the next process reads.
     */
    private class Prefs(
        @Volatile var consented: Boolean,
        @Volatile var clearReachesDisk: Boolean,
    ) {
        fun proxy(): ZchatPreferences =
            Proxy.newProxyInstance(
                ZchatPreferences::class.java.classLoader,
                arrayOf(ZchatPreferences::class.java),
            ) { _, method, _ ->
                when (method.name) {
                    "hasConsentedToHandshakeSpend" -> consented
                    "clearConsentedToHandshakeSpend" -> {
                        if (clearReachesDisk) consented = false
                        clearReachesDisk
                    }
                    "getTunnelModePeers" -> emptyList<String>()
                    "toString" -> "Prefs"
                    "hashCode" -> 0
                    "equals" -> false
                    else ->
                        when (method.returnType) {
                            java.lang.Boolean.TYPE -> false
                            java.lang.Integer.TYPE -> 0
                            java.lang.Long.TYPE -> 0L
                            else -> null
                        }
                }
            } as ZchatPreferences
    }

    private companion object {
        const val TIMEOUT_MS = 5_000L

        fun balance(available: Long) = WalletBalance(Zatoshi(available), Zatoshi(0), Zatoshi(0))

        suspend fun account() =
            ZashiAccount(
                sdkAccount = AccountFixture.new(),
                unified = UnifiedInfo(WalletAddress.Unified.new("usynthetic-self"), balance(139_000), balance(443_002)),
                sapling = SaplingInfo(WalletAddress.Sapling.new("zsynthetic-self"), balance(0)),
                transparent = TransparentInfo(WalletAddress.Transparent.new("tsynthetic-self"), Zatoshi(0)),
                isSelected = true,
            )
    }
}
