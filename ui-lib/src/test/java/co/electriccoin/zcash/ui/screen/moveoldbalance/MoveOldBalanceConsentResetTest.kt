package co.electriccoin.zcash.ui.screen.moveoldbalance

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
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
import co.electriccoin.zcash.ui.common.repository.BiometricRepository
import co.electriccoin.zcash.ui.common.repository.BiometricRequest
import co.electriccoin.zcash.ui.common.repository.BiometricResult
import co.electriccoin.zcash.ui.common.repository.BiometricsCancelledException
import co.electriccoin.zcash.ui.common.repository.ZashiProposalRepositoryImpl
import co.electriccoin.zcash.ui.common.usecase.IsTorEnabledUseCase
import co.electriccoin.zcash.ui.common.usecase.Zc101FakeAccountDataSource
import co.electriccoin.zcash.ui.common.usecase.Zc101FakeZashiSpendingKeyDataSource
import co.electriccoin.zcash.ui.common.usecase.Zc101RecordingNavigationRouter
import co.electriccoin.zcash.ui.common.usecase.Zc101RecordingProposalDataSource
import co.electriccoin.zcash.ui.common.usecase.Zc101SuspendingBiometricRepository
import co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferences
import kotlinx.coroutines.CompletableDeferred
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
import org.w3c.dom.Element
import java.io.File
import java.lang.reflect.Proxy
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.reflect.KClass
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Review follow-up, 2026-09-11, on owner decision 2 (a move resets handshake-spend consent).
 *
 * The reset used to run only when the sheet settled a result that was not a Failure. Two paths skipped
 * it after the move may already have gone out: the sheet going away while the submit keeps running in
 * the repository's scope, and an exception after the broadcast (the balance refresh runs after it),
 * which the repository turns into a Failure that names no transaction. Queued KEX/ZBOOT handshakes would
 * then spend the moved Ironwood without asking, and the sheet said "nothing was sent".
 *
 * Now consent is reset once the user has passed the biometric prompt and before the SDK sees the move,
 * and such an exception settles as SentUnconfirmed, shown as an unknown outcome (a proven non-send stays
 * a Failure: MoveOldBalanceNothingSentTest). All addresses, keys and txids are synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MoveOldBalanceConsentResetTest {
    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun consent_is_reset_before_the_sdk_sees_the_move() =
        runBlocking {
            val prefs = Prefs(consented = true)
            val source = SweepSource(prefs = prefs, gated = true)
            val vm = sheet(source, prefs)

            vm.await(MoveOldBalanceState.Consent::class).onContinue()
            vm.current<MoveOldBalanceState.Review>().onConfirm()
            withTimeout(TIMEOUT_MS) { source.entered.await() }

            assertEquals(false, source.consentWhenSubmitted, "the SDK saw the move while consent still stood")
            source.gate.complete(Unit)
            vm.await(MoveOldBalanceState.Success::class)
            assertFalse(prefs.consented)
        }

    @Test
    fun the_sheet_going_away_mid_submit_still_leaves_consent_reset() =
        runBlocking {
            val prefs = Prefs(consented = true)
            val source = SweepSource(prefs = prefs, gated = true)
            val store = ViewModelStore()
            val vm = ViewModelProvider(store, SingleFactory(sheet(source, prefs)))[MoveOldBalanceVM::class.java]

            vm.await(MoveOldBalanceState.Consent::class).onContinue()
            vm.current<MoveOldBalanceState.Review>().onConfirm()
            vm.await(MoveOldBalanceState.Sending::class)
            withTimeout(TIMEOUT_MS) { source.entered.await() }

            // The sheet is destroyed while the move is being sent; the repository finishes it anyway.
            store.clear()
            source.gate.complete(Unit)
            withTimeout(TIMEOUT_MS) { source.returned.await() }

            assertEquals(listOf(source.proposal), source.settled, "the move went out once")
            assertTrue(vm.state.value is MoveOldBalanceState.Sending, "the sheet never settled: ${vm.state.value}")
            assertFalse(prefs.consented, "queued handshakes would spend the moved funds without asking")
        }

    @Test
    fun an_exception_after_the_broadcast_is_an_unknown_outcome_not_nothing_sent() =
        runBlocking {
            val prefs = Prefs(consented = true)
            val source = SweepSource(prefs = prefs, failure = IllegalStateException("synthetic refresh failure"))
            val vm = sheet(source, prefs)

            vm.await(MoveOldBalanceState.Consent::class).onContinue()
            vm.current<MoveOldBalanceState.Review>().onConfirm()
            val failed = vm.await(MoveOldBalanceState.Failed::class)

            assertEquals(listOf(source.proposal), source.settled, "the SDK was handed the move")
            assertTrue(failed.isOutcomeUnknown, "an exception after the broadcast must not read as nothing sent")
            assertFalse(failed.isNetworkFailure)
            assertNull(failed.onViewTransaction, "no transaction is named, so there is none to show")
            assertFalse(prefs.consented)
        }

    @Test
    fun a_result_that_names_its_transaction_keeps_its_own_copy() =
        runBlocking {
            val rejected = settle(SubmitResult.Failure(listOf("synthetic-rejected-txid"), 18, "rejected"))
            assertFalse(rejected.isOutcomeUnknown)
            assertFalse(rejected.isNetworkFailure)

            val network = settle(SubmitResult.GrpcFailure(listOf("synthetic-grpc-txid")))
            assertFalse(network.isOutcomeUnknown)
            assertTrue(network.isNetworkFailure)
        }

    @Test
    fun a_cancelled_prompt_commits_to_nothing_and_leaves_consent_alone() =
        runBlocking {
            val prefs = Prefs(consented = true)
            val source = SweepSource(prefs = prefs)
            val vm = sheet(source, prefs, bio = CancellingBiometricRepository())

            vm.await(MoveOldBalanceState.Consent::class).onContinue()
            vm.current<MoveOldBalanceState.Review>().onConfirm()
            vm.await(MoveOldBalanceState.Review::class)

            assertTrue(source.settled.isEmpty())
            assertTrue(prefs.consented, "nothing was confirmed, so nothing is reset")
            assertFalse("clearConsentedToHandshakeSpend" in prefs.calls)
        }

    @Test
    fun the_unknown_outcome_copy_never_says_nothing_was_sent() {
        val english = strings("values")
        val spanish = strings("values-es")

        listOf("move_old_balance_unknown_title", "move_old_balance_unknown_body").forEach { key ->
            assertTrue(english[key].orEmpty().isNotBlank(), "values is missing $key")
            assertTrue(spanish[key].orEmpty().isNotBlank(), "values-es is missing $key")
            assertTrue(english[key] != spanish[key], "$key is not translated")
        }
        val en = english.getValue("move_old_balance_unknown_body")
        val es = spanish.getValue("move_old_balance_unknown_body")
        assertFalse(en.contains("nothing was sent", ignoreCase = true), en)
        assertFalse(es.contains("no se envió nada", ignoreCase = true), es)
        assertTrue(en.contains("can\\'t tell"), en)
        assertTrue(es.contains("no puede saber"), es)
    }

    // --------------------------------------------------------------------------------- harness

    private suspend fun settle(result: SubmitResult): MoveOldBalanceState.Failed {
        val prefs = Prefs(consented = true)
        val vm = sheet(SweepSource(prefs = prefs, result = result), prefs)
        vm.await(MoveOldBalanceState.Consent::class).onContinue()
        vm.current<MoveOldBalanceState.Review>().onConfirm()
        return vm.await(MoveOldBalanceState.Failed::class)
    }

    private suspend fun sheet(
        source: SweepSource,
        prefs: Prefs,
        bio: BiometricRepository = Zc101SuspendingBiometricRepository().apply { approve() },
    ): MoveOldBalanceVM {
        val accounts = Zc101FakeAccountDataSource(account())
        val repository =
            ZashiProposalRepositoryImpl(
                accountDataSource = accounts,
                proposalDataSource = source,
                zashiSpendingKeyDataSource = Zc101FakeZashiSpendingKeyDataSource(),
            )
        return MoveOldBalanceVM(
            accountDataSource = accounts,
            zashiProposalRepository = repository,
            biometricRepository = bio,
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

    /**
     * Builds the sweep and settles it: parked on [gate] when gated, then [result], or [failure] thrown
     * after the "broadcast" (as a balance refresh that throws would). Records the consent it saw.
     */
    private class SweepSource(
        private val prefs: Prefs,
        private val result: SubmitResult = SubmitResult.Success(listOf("synthetic-sweep-txid")),
        private val failure: Exception? = null,
        gated: Boolean = false,
        recording: Zc101RecordingProposalDataSource = Zc101RecordingProposalDataSource(),
    ) : ProposalDataSource by recording {
        val proposal: Proposal = SweepProposalBytes.proposal(listOf(SweepProposalBytes.orchard(139_000)), fee = 20_000)
        val settled = mutableListOf<Proposal>()
        val entered = CompletableDeferred<Unit>()
        val returned = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>().apply { if (!gated) complete(Unit) }

        @Volatile
        var consentWhenSubmitted: Boolean? = null

        override suspend fun createOrchardSweepProposal(account: WalletAccount): OrchardSweepTransactionProposal =
            OrchardSweepTransactionProposal.fromProposal(proposal)

        override suspend fun submitTransaction(
            proposal: Proposal,
            usk: UnifiedSpendingKey
        ): SubmitResult {
            synchronized(settled) { settled += proposal }
            consentWhenSubmitted = prefs.consented
            entered.complete(Unit)
            gate.await()
            returned.complete(Unit)
            failure?.let { throw it }
            return result
        }
    }

    private class SingleFactory(
        private val vm: ViewModel
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = vm as T
    }

    private class CancellingBiometricRepository : BiometricRepository {
        override val allowedAuthenticators: Int = 0

        override fun onBiometricResult(result: BiometricResult) = Unit

        override suspend fun requestBiometrics(request: BiometricRequest): Unit = throw BiometricsCancelledException()
    }

    private object TorOn : PersistableWalletTorProvider {
        override fun observe(): Flow<TorState?> = flowOf(TorState.EXPLICITLY_ENABLED)

        override suspend fun get(): TorState = TorState.EXPLICITLY_ENABLED
    }

    /** Handshake consent behind a recording [ZchatPreferences] proxy; no queued peers. */
    private class Prefs(
        @Volatile var consented: Boolean,
    ) {
        val calls: MutableList<String> = java.util.Collections.synchronizedList(mutableListOf())

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

    private fun strings(qualifier: String): Map<String, String> {
        val file = uiLibDir.resolve("src/main/res/ui/send_confirmation/$qualifier/move_old_balance_strings.xml")
        assertTrue(file.isFile, "missing ${file.path}")
        val nodes =
            DocumentBuilderFactory
                .newInstance()
                .newDocumentBuilder()
                .parse(file)
                .getElementsByTagName("string")
        return (0 until nodes.length)
            .map { nodes.item(it) as Element }
            .associate { it.getAttribute("name") to it.textContent }
    }

    private companion object {
        const val TIMEOUT_MS = 5_000L

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

        val uiLibDir: File by lazy {
            val start = File(System.getProperty("user.dir") ?: ".").absoluteFile
            generateSequence(start) { it.parentFile }
                .take(8)
                .flatMap { sequenceOf(it, it.resolve("ui-lib")) }
                .firstOrNull { it.resolve("src/main/res/ui/send_confirmation").isDirectory }
                ?: fail("could not locate the ui-lib module from ${start.path}")
        }
    }
}
