package co.electriccoin.zcash.ui.screen.moveoldbalance

import cash.z.ecc.android.sdk.fixture.AccountFixture
import cash.z.ecc.android.sdk.model.Account
import cash.z.ecc.android.sdk.model.Proposal
import cash.z.ecc.android.sdk.model.UnifiedSpendingKey
import cash.z.ecc.android.sdk.model.WalletAddress
import cash.z.ecc.android.sdk.model.WalletBalance
import cash.z.ecc.android.sdk.model.Zatoshi
import co.electriccoin.zcash.ui.common.datasource.AccountDataSource
import co.electriccoin.zcash.ui.common.datasource.OrchardSweepTransactionProposal
import co.electriccoin.zcash.ui.common.datasource.ProposalDataSource
import co.electriccoin.zcash.ui.common.model.KeystoneAccount
import co.electriccoin.zcash.ui.common.model.NetworkUpgradeRequiredException
import co.electriccoin.zcash.ui.common.model.OrchardSweepTooSmallException
import co.electriccoin.zcash.ui.common.model.SaplingInfo
import co.electriccoin.zcash.ui.common.model.SubmitResult
import co.electriccoin.zcash.ui.common.model.SweepProposalBytes
import co.electriccoin.zcash.ui.common.model.TransparentInfo
import co.electriccoin.zcash.ui.common.model.UnifiedInfo
import co.electriccoin.zcash.ui.common.model.WalletAccount
import co.electriccoin.zcash.ui.common.model.ZashiAccount
import co.electriccoin.zcash.ui.common.provider.PersistableWalletTorProvider
import co.electriccoin.zcash.ui.common.provider.TorState
import co.electriccoin.zcash.ui.common.repository.BiometricRepository
import co.electriccoin.zcash.ui.common.repository.BiometricRequest
import co.electriccoin.zcash.ui.common.repository.BiometricResult
import co.electriccoin.zcash.ui.common.repository.BiometricsCancelledException
import co.electriccoin.zcash.ui.common.repository.ZashiProposalRepositoryImpl
import co.electriccoin.zcash.ui.common.usecase.IsTorEnabledUseCase
import co.electriccoin.zcash.ui.common.usecase.Zc101FakeZashiSpendingKeyDataSource
import co.electriccoin.zcash.ui.common.usecase.Zc101RecordingNavigationRouter
import co.electriccoin.zcash.ui.common.usecase.Zc101RecordingProposalDataSource
import co.electriccoin.zcash.ui.common.usecase.Zc101SuspendingBiometricRepository
import co.electriccoin.zcash.ui.common.usecase.syntheticZip321Proposal
import co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferences
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The "Move old balance" sheet (sweep spec §1.2, §5 MoveOldBalanceVMTest, owner decisions 2026-09-10).
 *
 * It asks consent every time, reviews the proposal it built, confirms only after the biometric prompt,
 * settles exactly the proposal it showed and awaits its own result. Nothing another flow does to the
 * shared proposal cell (a chat send, a `clear()`) can change what it shows. Keystone is blocked, the Tor
 * line only informs, and a move that may have gone out resets handshake consent. All addresses, keys and
 * txids are synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MoveOldBalanceVMTest {
    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun consent_then_review_then_sending_then_success() =
        runBlocking {
            val source = SweepSource(gated = true)
            val bio = Zc101SuspendingBiometricRepository()
            val harness = harness(source = source, bio = bio)
            val vm = harness.vm

            val consent = vm.await(MoveOldBalanceState.Consent::class)
            assertEquals(Zatoshi(139_000), consent.amount)
            assertEquals(Zatoshi(20_000), consent.fee)
            assertEquals(Zatoshi(119_000), consent.arrives)
            assertFalse(bio.promptShown.isCompleted, "no prompt before consent")
            assertTrue(
                MoveOldBalanceState.Consent::class.java.methods.none { it.name == "getOnConfirm" },
                "consent cannot confirm a move",
            )

            consent.onContinue()
            val review = vm.current<MoveOldBalanceState.Review>()
            assertEquals(Zatoshi(139_000), review.amount)
            assertEquals(Zatoshi(119_000), review.arrives)
            assertFalse(review.isFeeHigh)

            review.onConfirm()
            withTimeout(TIMEOUT_MS) { bio.promptShown.await() }
            assertTrue(source.settled.isEmpty(), "nothing is signed before the prompt passes")
            bio.approve()

            val sending = vm.await(MoveOldBalanceState.Sending::class)
            withTimeout(TIMEOUT_MS) { source.entered.await() }
            val commandsBefore = harness.router.commands.toList()
            sending.onBack()
            assertEquals(commandsBefore, harness.router.commands, "Back does nothing while sending")

            source.gate.complete(Unit)
            val success = vm.await(MoveOldBalanceState.Success::class)
            assertSame(source.proposal, source.settled.single(), "the displayed proposal is the one settled")
            assertTrue("clearConsentedToHandshakeSpend" in harness.prefs.calls, "owner decision 2")

            assertNotNull(success.onViewTransaction).invoke()
            assertEquals("replaceAll", harness.router.commands.last())
        }

    @Test
    fun a_chat_send_and_a_clear_during_the_sweep_change_nothing_it_shows() =
        runBlocking {
            val source = SweepSource(gated = true)
            val bio = Zc101SuspendingBiometricRepository().apply { approve() }
            val harness = harness(source = source, bio = bio)
            val vm = harness.vm

            vm.await(MoveOldBalanceState.Consent::class).onContinue()
            vm.current<MoveOldBalanceState.Review>().onConfirm()
            vm.await(MoveOldBalanceState.Sending::class)
            withTimeout(TIMEOUT_MS) { source.entered.await() }

            // Another flow uses the shared repository while the sweep is in flight.
            val chat = syntheticZip321Proposal(destination = "usynthetic-chat-peer", amount = 1_000, memo = "hi")
            harness.repository.clear()
            harness.repository.submit(chat)
            harness.repository.clear()
            assertTrue(vm.state.value is MoveOldBalanceState.Sending, "${vm.state.value}")

            source.gate.complete(Unit)
            vm.await(MoveOldBalanceState.Success::class)
            assertEquals(listOf(source.proposal, chat.proposal), source.settled)
            assertNull(harness.repository.submitState.value, "the sweep never writes the shared submit state")
        }

    @Test
    fun a_cancelled_prompt_returns_to_the_same_review_and_may_try_again() =
        runBlocking {
            val source = SweepSource()
            val bio = CancelFirstBiometricRepository()
            val vm = harness(source = source, bio = bio).vm

            vm.await(MoveOldBalanceState.Consent::class).onContinue()
            vm.current<MoveOldBalanceState.Review>().onConfirm()

            val again = vm.await(MoveOldBalanceState.Review::class)
            assertTrue(source.settled.isEmpty(), "a cancelled prompt signs nothing")
            again.onConfirm()

            vm.await(MoveOldBalanceState.Success::class)
            assertEquals(2, bio.requests)
            assertSame(source.proposal, source.settled.single())
        }

    @Test
    fun a_double_tap_confirms_once() =
        runBlocking {
            val source = SweepSource()
            val bio = Zc101SuspendingBiometricRepository()
            val vm = harness(source = source, bio = bio).vm

            vm.await(MoveOldBalanceState.Consent::class).onContinue()
            val review = vm.current<MoveOldBalanceState.Review>()
            review.onConfirm()
            review.onConfirm()
            bio.approve()

            vm.await(MoveOldBalanceState.Success::class)
            review.onConfirm()
            assertEquals(1, source.settled.size, "one submit per sheet")
        }

    @Test
    fun back_from_review_is_the_same_consent_and_not_now_closes() =
        runBlocking {
            val harness = harness(source = SweepSource())
            val vm = harness.vm

            vm.await(MoveOldBalanceState.Consent::class).onContinue()
            vm.current<MoveOldBalanceState.Review>().onBackToConsent()
            val consent = vm.current<MoveOldBalanceState.Consent>()
            assertEquals(Zatoshi(139_000), consent.amount)

            consent.onNotNow()
            assertEquals(listOf("back"), harness.router.commands)
        }

    @Test
    fun keystone_and_the_other_blocked_states_never_build_a_proposal() =
        runBlocking {
            val cases =
                listOf(
                    keystone(orchard = 139_000) to MoveOldBalanceBlock.Keystone,
                    zashi(orchard = 20_000) to MoveOldBalanceBlock.TooSmall(Zatoshi(20_000), Zatoshi(20_000)),
                    zashi(orchard = 139_000, orchardPending = 1_000) to MoveOldBalanceBlock.WaitForSync,
                    zashi(orchard = 0) to MoveOldBalanceBlock.NothingToMove,
                )
            cases.forEach { (account, expected) ->
                val source = SweepSource()
                val harness = harness(source = source, account = account)

                val blocked = harness.vm.await(MoveOldBalanceState.Blocked::class)

                assertEquals(expected, blocked.reason, "$account")
                assertEquals(0, source.createCalls, "$expected reached the funnel")
                blocked.onOk()
                assertEquals(listOf("back"), harness.router.commands)
            }
        }

    /**
     * ZC-782 (review of 12 Oct, finding 2): in the update-required state the sweep proposal is refused with the update
     * refusal, and the sheet showed its general error ("Nothing was sent. Try again later."). Wanted: its own block,
     * which the sheet shows as the update title and sentence (pinned in UpdateRequiredSendDoorsShowTheUpdateSentenceTest).
     * The block is read by name, so this file compiles before it exists.
     */
    @Test
    fun the_update_refusal_gets_its_own_block_and_moves_nothing() =
        runBlocking {
            val source = SweepSource(createFailure = NetworkUpgradeRequiredException())
            val harness = harness(source = source)

            val blocked = harness.vm.await(MoveOldBalanceState.Blocked::class)

            assertEquals("UpdateRequired", blocked.reason.toString(), "the update refusal must not read as a general error")
            assertTrue(source.settled.isEmpty(), "nothing is signed or sent")
            assertFalse("clearConsentedToHandshakeSpend" in harness.prefs.calls, "nothing moved, so consent is untouched")
        }

    @Test
    fun a_refused_proposal_is_explained() =
        runBlocking {
            val cases =
                listOf(
                    OrchardSweepTooSmallException(Zatoshi(30_000), Zatoshi(25_000)) to
                        MoveOldBalanceBlock.TooSmall(Zatoshi(30_000), Zatoshi(25_000)),
                    IllegalStateException("The Ironwood pool is not active yet") to MoveOldBalanceBlock.Error,
                    IllegalStateException("Must scan blocks first") to MoveOldBalanceBlock.WaitForSync,
                )
            cases.forEach { (failure, expected) ->
                val vm = harness(source = SweepSource(createFailure = failure)).vm
                assertEquals(expected, vm.await(MoveOldBalanceState.Blocked::class).reason, "$failure")
            }
        }

    /** Owner decision 1: the Tor line informs; it never blocks. */
    @Test
    fun the_tor_line_shows_only_when_tor_is_off() =
        runBlocking {
            mapOf(
                TorState.EXPLICITLY_ENABLED to false,
                TorState.EXPLICITLY_DISABLED to true,
                TorState.IMPLICITLY_DISABLED to true,
            ).forEach { (tor, isTorOff) ->
                val consent = harness(source = SweepSource(), tor = tor).vm.await(MoveOldBalanceState.Consent::class)
                assertEquals(isTorOff, consent.isTorOff, "$tor")
            }
        }

    @Test
    fun the_handshake_line_shows_only_with_consent_and_queued_peers() =
        runBlocking {
            val shown =
                harness(source = SweepSource(), prefs = Prefs(consented = true, tunnelPeers = listOf("a", "b")))
                    .vm
                    .await(MoveOldBalanceState.Consent::class)
            assertEquals(PendingHandshakes(2, Zatoshi(34_000)), shown.handshakes)

            val hidden =
                listOf(
                    Prefs(consented = false, tunnelPeers = listOf("a")),
                    Prefs(consented = true, tunnelPeers = emptyList()),
                )
            hidden.forEach { prefs ->
                val vm = harness(source = SweepSource(), prefs = prefs).vm
                val consent = vm.await(MoveOldBalanceState.Consent::class)
                assertNull(consent.handshakes, "${prefs.consented} ${prefs.tunnelPeers}")
            }
        }

    @Test
    fun the_success_screen_repeats_the_handshake_line() =
        runBlocking {
            val prefs = Prefs(consented = true, tunnelPeers = listOf("a"))
            val bio = Zc101SuspendingBiometricRepository().apply { approve() }
            val vm = harness(source = SweepSource(), bio = bio, prefs = prefs).vm

            vm.await(MoveOldBalanceState.Consent::class).onContinue()
            vm.current<MoveOldBalanceState.Review>().onConfirm()

            assertEquals(PendingHandshakes(1, Zatoshi(17_000)), vm.await(MoveOldBalanceState.Success::class).handshakes)
            assertFalse(prefs.consented, "consent was reset")
        }

    @Test
    fun failures_offer_the_transaction_only_when_there_is_one() =
        runBlocking {
            val withTx = settle(SubmitResult.Failure(listOf("synthetic-failed-txid"), 1, "rejected"))
            assertFalse(withTx.state.isNetworkFailure)
            assertNotNull(withTx.state.onViewTransaction)
            assertFalse(withTx.prefs.consented, "consent is reset before the submit, whatever it returns")

            val withoutTx = settle(SubmitResult.Failure(emptyList(), 0, "rejected"))
            assertNull(withoutTx.state.onViewTransaction)

            val network = settle(SubmitResult.GrpcFailure(listOf("synthetic-grpc-txid")))
            assertTrue(network.state.isNetworkFailure)
            assertNotNull(network.state.onViewTransaction)
            assertFalse(network.prefs.consented, "it may still go through: consent is reset")
        }

    /** Owner decision 5: a fee above a quarter of the amount is called out on the review. */
    @Test
    fun a_fee_above_a_quarter_of_the_amount_is_flagged() =
        runBlocking {
            val source = SweepSource(proposal = sweepProposal(26_000))
            val vm = harness(source = source, account = zashi(orchard = 26_000)).vm

            vm.await(MoveOldBalanceState.Consent::class).onContinue()
            val review = vm.current<MoveOldBalanceState.Review>()

            assertEquals(Zatoshi(6_000), review.arrives)
            assertTrue(review.isFeeHigh)
        }

    /**
     * F9 (fix-all 2.13.0, train T1): no consent carries a "a more private way is planned" flag at any amount. Round 8
     * D10 showed that line from 1,000,000 zat, but the private route already ships in SDK 3.0.0 and at 1,000,000 to
     * 1,094,999 zat it can move nothing, so the line is gone (MoveOldBalanceNoPlannedLineTest). Reached by name, so
     * this file compiles either way.
     */
    @Test
    fun no_amount_gets_a_wait_for_private_line() =
        runBlocking {
            for (orchard in listOf(999_999L, 1_000_000L, 5_000_000L)) {
                val source = SweepSource(proposal = sweepProposal(orchard))
                val vm = harness(source = source, account = zashi(orchard = orchard)).vm
                val consent = vm.await(MoveOldBalanceState.Consent::class)

                assertEquals(Zatoshi(orchard), consent.amount)
                assertFalse(
                    consent.javaClass.methods.any { it.name == "getShowWaitForPrivateLine" },
                    "at $orchard zat the consent still carries showWaitForPrivateLine",
                )
            }
        }

    // --------------------------------------------------------------------------------- harness

    private class Settled(
        val state: MoveOldBalanceState.Failed,
        val prefs: Prefs
    )

    private suspend fun settle(result: SubmitResult): Settled {
        val prefs = Prefs(consented = true, tunnelPeers = emptyList())
        val bio = Zc101SuspendingBiometricRepository().apply { approve() }
        val vm = harness(source = SweepSource(result = result), bio = bio, prefs = prefs).vm
        vm.await(MoveOldBalanceState.Consent::class).onContinue()
        vm.current<MoveOldBalanceState.Review>().onConfirm()
        return Settled(vm.await(MoveOldBalanceState.Failed::class), prefs)
    }

    private class Harness(
        val vm: MoveOldBalanceVM,
        val repository: ZashiProposalRepositoryImpl,
        val router: Zc101RecordingNavigationRouter,
        val prefs: Prefs,
    )

    private suspend fun harness(
        source: SweepSource,
        bio: BiometricRepository = Zc101SuspendingBiometricRepository().apply { approve() },
        account: WalletAccount? = null,
        tor: TorState = TorState.EXPLICITLY_ENABLED,
        prefs: Prefs = Prefs(consented = false, tunnelPeers = emptyList()),
    ): Harness {
        val accounts = FakeAccounts(account ?: zashi(orchard = 139_000, ironwood = 443_002))
        val repository =
            ZashiProposalRepositoryImpl(
                accountDataSource = accounts,
                proposalDataSource = source,
                zashiSpendingKeyDataSource = Zc101FakeZashiSpendingKeyDataSource(),
            )
        val router = Zc101RecordingNavigationRouter()
        val vm =
            MoveOldBalanceVM(
                accountDataSource = accounts,
                zashiProposalRepository = repository,
                biometricRepository = bio,
                navigationRouter = router,
                isTorEnabled = IsTorEnabledUseCase(FakeTor(tor)),
                zchatPreferences = prefs.proxy(),
            )
        return Harness(vm, repository, router, prefs)
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

    /** Builds the sweep from [proposal]; settles it with [result], parked on [gate] when [gated]. */
    private class SweepSource(
        val proposal: Proposal = sweepProposal(139_000),
        val createFailure: Exception? = null,
        val result: SubmitResult = SubmitResult.Success(listOf("synthetic-sweep-txid")),
        gated: Boolean = false,
        val recording: Zc101RecordingProposalDataSource = Zc101RecordingProposalDataSource(),
    ) : ProposalDataSource by recording {
        var createCalls = 0
        val settled = mutableListOf<Proposal>()
        val entered = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>().apply { if (!gated) complete(Unit) }

        override suspend fun createOrchardSweepProposal(account: WalletAccount): OrchardSweepTransactionProposal {
            createCalls++
            createFailure?.let { throw it }
            return OrchardSweepTransactionProposal.fromProposal(proposal)
        }

        override suspend fun submitTransaction(
            proposal: Proposal,
            usk: UnifiedSpendingKey
        ): SubmitResult {
            synchronized(settled) { settled += proposal }
            if (proposal !== this.proposal) return SubmitResult.Success(listOf("synthetic-chat-txid"))
            entered.complete(Unit)
            gate.await()
            return result
        }
    }

    private class CancelFirstBiometricRepository : BiometricRepository {
        var requests = 0

        override val allowedAuthenticators: Int = 0

        override fun onBiometricResult(result: BiometricResult) = Unit

        override suspend fun requestBiometrics(request: BiometricRequest) {
            requests++
            if (requests == 1) throw BiometricsCancelledException()
        }
    }

    private class FakeTor(
        private val state: TorState
    ) : PersistableWalletTorProvider {
        override fun observe(): Flow<TorState?> = flowOf(state)

        override suspend fun get(): TorState = state
    }

    private class FakeAccounts(
        private val account: WalletAccount
    ) : AccountDataSource {
        override val allAccounts: StateFlow<List<WalletAccount>?> = MutableStateFlow(listOf(account))
        override val selectedAccount: Flow<WalletAccount?> = flowOf(account)
        override val zashiAccount: Flow<ZashiAccount?> = flowOf(account as? ZashiAccount)

        override suspend fun getAllAccounts(): List<WalletAccount> = listOf(account)

        override suspend fun getSelectedAccount(): WalletAccount = account

        override suspend fun getZashiAccount(): ZashiAccount = account as ZashiAccount

        override suspend fun selectAccount(account: Account) = Unit

        override suspend fun selectAccount(account: WalletAccount) = Unit

        override suspend fun importKeystoneAccount(
            ufvk: String,
            seedFingerprint: String,
            index: Long,
        ): Account = error("not used in this test")

        override suspend fun requestNextShieldedAddress() = Unit
    }

    /** Handshake consent and the queued Tunnel peers, behind a recording [ZchatPreferences] proxy. */
    private class Prefs(
        var consented: Boolean,
        val tunnelPeers: List<String>,
    ) {
        val calls = mutableListOf<String>()

        fun proxy(): ZchatPreferences =
            Proxy.newProxyInstance(
                ZchatPreferences::class.java.classLoader,
                arrayOf(ZchatPreferences::class.java),
            ) { _, method, _ ->
                calls += method.name
                when (method.name) {
                    "hasConsentedToHandshakeSpend" -> consented
                    "clearConsentedToHandshakeSpend" -> {
                        consented = false
                        // R4-1: a working store reports its write.
                        true
                    }
                    "getTunnelModePeers" -> tunnelPeers
                    "getSentNostrBootPubkey" -> null
                    "isE2EKeyExchangeComplete" -> true
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

        fun sweepProposal(orchard: Long): Proposal =
            SweepProposalBytes.proposal(listOf(SweepProposalBytes.orchard(orchard)), fee = 20_000)

        fun balance(
            available: Long,
            pending: Long = 0
        ) = WalletBalance(Zatoshi(available), Zatoshi(0), Zatoshi(pending))

        suspend fun zashi(
            orchard: Long,
            orchardPending: Long = 0,
            ironwood: Long = 0,
        ) = ZashiAccount(
            sdkAccount = AccountFixture.new(),
            unified =
                UnifiedInfo(
                    WalletAddress.Unified.new("usynthetic-self"),
                    balance(orchard, orchardPending),
                    balance(ironwood),
                ),
            sapling = SaplingInfo(WalletAddress.Sapling.new("zsynthetic-self"), balance(0)),
            transparent = TransparentInfo(WalletAddress.Transparent.new("tsynthetic-self"), Zatoshi(0)),
            isSelected = true,
        )

        suspend fun keystone(orchard: Long) =
            KeystoneAccount(
                sdkAccount = AccountFixture.new(),
                unified = UnifiedInfo(WalletAddress.Unified.new("usynthetic-self"), balance(orchard)),
                transparent = TransparentInfo(WalletAddress.Transparent.new("tsynthetic-self"), Zatoshi(0)),
                isSelected = true,
            )
    }
}
