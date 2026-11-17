package co.electriccoin.zcash.ui.screen.moveoldbalance

import cash.z.ecc.android.sdk.fixture.AccountFixture
import cash.z.ecc.android.sdk.model.Proposal
import cash.z.ecc.android.sdk.model.UnifiedSpendingKey
import cash.z.ecc.android.sdk.model.WalletAddress
import cash.z.ecc.android.sdk.model.WalletBalance
import cash.z.ecc.android.sdk.model.Zatoshi
import co.electriccoin.zcash.ui.common.datasource.NothingBroadcastException
import co.electriccoin.zcash.ui.common.datasource.OrchardSweepTransactionProposal
import co.electriccoin.zcash.ui.common.datasource.ProposalDataSource
import co.electriccoin.zcash.ui.common.datasource.ZashiSpendingKeyDataSource
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
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The Orchard sweep must say "nothing was sent" when nothing provably was (audit fable-20260915, A.7).
 *
 * `ZashiProposalRepositoryImpl.submit` splits a provable non-send ([NothingBroadcastException]: the key
 * could not be derived, or the SDK's create/prove/store step threw before anything reached the network)
 * from the ambiguous throw that may follow a broadcast. `submitOrchardSweep` never got that split: it
 * derived the key bare and turned EVERY throw into `Failure(txIds = [])`, and [MoveOldBalanceVM] reads
 * a Failure naming no transaction as "outcome unknown". So a move that never left the wallet told the
 * user "ZCHAT can't tell whether it went out ... wait about an hour".
 *
 * Pinned here: a proven non-send settles as a plain failure and the sheet says so; an ambiguous throw is
 * [SubmitResult.SentUnconfirmed] and the sheet keeps the unknown-outcome copy. All addresses, keys and
 * ids are synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MoveOldBalanceNothingSentTest {
    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun control_a_proven_non_send_from_the_sdk_stays_a_plain_failure() =
        runBlocking {
            val source = SweepSource(failure = NothingBroadcastException(IllegalStateException(PRE_SEND)))
            val repository = repository(source)

            val result = repository.submitOrchardSweep(OrchardSweepTransactionProposal.fromProposal(source.proposal))

            assertEquals(SubmitResult.Failure(txIds = emptyList(), code = 0, description = PRE_SEND), result)
        }

    @Test
    fun an_ambiguous_throw_is_sent_unconfirmed_not_a_failure() =
        runBlocking {
            val source = SweepSource(failure = IllegalStateException(POST_SEND))
            val repository = repository(source)

            val result = repository.submitOrchardSweep(OrchardSweepTransactionProposal.fromProposal(source.proposal))

            assertEquals(SubmitResult.SentUnconfirmed(txIds = emptyList(), description = POST_SEND), result)
        }

    @Test
    fun the_sheet_says_nothing_was_sent_when_the_sdk_proved_it() =
        runBlocking {
            val source = SweepSource(failure = NothingBroadcastException(IllegalStateException(PRE_SEND)))

            val failed = confirm(sheet(source))

            assertEquals(listOf(source.proposal), source.settled)
            assertFalse(failed.isOutcomeUnknown, "the move provably never left the wallet")
            assertFalse(failed.isNetworkFailure)
        }

    @Test
    fun the_sheet_says_nothing_was_sent_when_the_key_could_not_be_derived() =
        runBlocking {
            val source = SweepSource()

            val failed = confirm(sheet(source, keys = RefusingSpendingKeyDataSource))

            assertTrue(source.settled.isEmpty(), "without a key nothing can have reached the SDK")
            assertFalse(failed.isOutcomeUnknown, "the move provably never left the wallet")
            assertFalse(failed.isNetworkFailure)
        }

    @Test
    fun control_an_ambiguous_throw_keeps_the_unknown_outcome() =
        runBlocking {
            val source = SweepSource(failure = IllegalStateException(POST_SEND))

            val failed = confirm(sheet(source))

            assertTrue(failed.isOutcomeUnknown, "a throw that may follow the broadcast must not read as nothing sent")
        }

    // ── Harness ──────────────────────────────────────────────────────────────────────────────────

    private suspend fun repository(source: ProposalDataSource) =
        ZashiProposalRepositoryImpl(
            accountDataSource = Zc101FakeAccountDataSource(account()),
            proposalDataSource = source,
            zashiSpendingKeyDataSource = Zc101FakeZashiSpendingKeyDataSource(),
        )

    private suspend fun sheet(
        source: SweepSource,
        keys: ZashiSpendingKeyDataSource = Zc101FakeZashiSpendingKeyDataSource(),
    ): MoveOldBalanceVM {
        val accounts = Zc101FakeAccountDataSource(account())
        return MoveOldBalanceVM(
            accountDataSource = accounts,
            zashiProposalRepository =
                ZashiProposalRepositoryImpl(
                    accountDataSource = accounts,
                    proposalDataSource = source,
                    zashiSpendingKeyDataSource = keys,
                ),
            biometricRepository = Zc101SuspendingBiometricRepository().apply { approve() },
            navigationRouter = Zc101RecordingNavigationRouter(),
            isTorEnabled = IsTorEnabledUseCase(TorOn),
            zchatPreferences = prefs(),
        )
    }

    private suspend fun confirm(vm: MoveOldBalanceVM): MoveOldBalanceState.Failed {
        withTimeout(TIMEOUT_MS) { vm.state.first { it is MoveOldBalanceState.Consent } }
            .let { (it as MoveOldBalanceState.Consent).onContinue() }
        val review = vm.state.value
        assertTrue(review is MoveOldBalanceState.Review, "expected Review, got $review")
        review.onConfirm()
        return withTimeout(TIMEOUT_MS) { vm.state.first { it is MoveOldBalanceState.Failed } } as
            MoveOldBalanceState.Failed
    }

    /** Builds the sweep; its settlement records the proposal and throws [failure] (or succeeds). */
    private class SweepSource(
        private val failure: Exception? = null,
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
            failure?.let { throw it }
            return SubmitResult.Success(listOf("synthetic-sweep-txid"))
        }
    }

    /** A locked keystore / unusable persisted wallet: the key cannot be derived, nothing can be signed. */
    private object RefusingSpendingKeyDataSource : ZashiSpendingKeyDataSource {
        override suspend fun getZashiSpendingKey(): UnifiedSpendingKey = throw IllegalStateException(KEY_FAILURE)
    }

    private object TorOn : PersistableWalletTorProvider {
        override fun observe(): Flow<TorState?> = flowOf(TorState.EXPLICITLY_ENABLED)

        override suspend fun get(): TorState = TorState.EXPLICITLY_ENABLED
    }

    /** Handshake consent granted, no queued peers; every other preference reads as its default. */
    private fun prefs(): ZchatPreferences {
        var consented = true
        return Proxy.newProxyInstance(
            ZchatPreferences::class.java.classLoader,
            arrayOf(ZchatPreferences::class.java),
        ) { _, method, _ ->
            when (method.name) {
                "hasConsentedToHandshakeSpend" -> consented
                "clearConsentedToHandshakeSpend" -> {
                    consented = false
                    // R4-1: a working store reports its write.
                    true
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

        const val PRE_SEND = "synthetic proving failure before the send"

        const val POST_SEND = "synthetic settlement failure after the send"

        const val KEY_FAILURE = "synthetic spending-key derivation failure"

        fun balance(available: Long) = WalletBalance(Zatoshi(available), Zatoshi(0), Zatoshi(0))

        suspend fun account() =
            ZashiAccount(
                sdkAccount = AccountFixture.new(),
                unified =
                    UnifiedInfo(WalletAddress.Unified.new("usynthetic-self"), balance(139_000), balance(443_002)),
                sapling = SaplingInfo(WalletAddress.Sapling.new("zsynthetic-self"), balance(0)),
                transparent = TransparentInfo(WalletAddress.Transparent.new("tsynthetic-self"), Zatoshi(0)),
                isSelected = true,
            )
    }
}
