package co.electriccoin.zcash.ui.screen.chat.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cash.z.ecc.android.sdk.model.Proposal
import cash.z.ecc.android.sdk.model.UnifiedSpendingKey
import co.electriccoin.zcash.ui.common.datasource.ProposalDataSource
import co.electriccoin.zcash.ui.common.datasource.Zip321TransactionProposal
import co.electriccoin.zcash.ui.common.model.SubmitResult
import co.electriccoin.zcash.ui.common.model.WalletAccount
import co.electriccoin.zcash.ui.common.repository.BiometricRepository
import co.electriccoin.zcash.ui.common.repository.BiometricRequest
import co.electriccoin.zcash.ui.common.repository.BiometricResult
import co.electriccoin.zcash.ui.common.repository.ZashiProposalRepositoryImpl
import co.electriccoin.zcash.ui.common.usecase.Zc101FakeAccountDataSource
import co.electriccoin.zcash.ui.common.usecase.Zc101FakeKeystoneProposalRepository
import co.electriccoin.zcash.ui.common.usecase.Zc101FakeZashiSpendingKeyDataSource
import co.electriccoin.zcash.ui.common.usecase.Zc101RecordingNavigationRouter
import co.electriccoin.zcash.ui.common.usecase.Zc101RecordingProposalDataSource
import co.electriccoin.zcash.ui.common.usecase.syntheticZashiAccount
import co.electriccoin.zcash.ui.common.usecase.syntheticZip321Proposal
import co.electriccoin.zcash.ui.screen.chat.crypto.E2EEncryption
import co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferences
import co.electriccoin.zcash.ui.screen.chat.usecase.CreateChunkedMessageProposalUseCase
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.lang.reflect.Proxy
import java.util.Collections

/**
 * DOUBLE-PAY (automatic, no user gesture) — backing out of a chat while its KEX is being sent.
 *
 * Opening a TUNNEL chat whose key exchange has not completed sets `isOwnBootSent = true` and calls
 * [ChatViewModel.sendKEXMessage], which pays a carrier output + fee on `viewModelScope`. The
 * repository runs the proving and broadcast in its OWN detached scope
 * (`ZashiProposalRepositoryImpl.submit` = `scope.async { … }.await()` on a private SupervisorJob), so
 * when the user backs out and the ViewModel is cleared, only the `await()` is cancelled: the KEX
 * still reaches the network. At bc68c80f2 the KEX catch treats that CancellationException as
 * "delivery unproven" and clears `isOwnBootSent`, so reopening the chat before the peer's KEXACK
 * lands sends and pays for a SECOND KEX. The ZBOOT sender was fixed for exactly this on 2026-09-08
 * (`mayReArmHandshakeMarker`); the KEX sender was missed.
 *
 * This drives the real KEX CALL SITE — `sendKEXMessage` → `sendHandshakeMemoWithRetry` → the real
 * [CreateChunkedMessageProposalUseCase] → the real [ZashiProposalRepositoryImpl] with its detached
 * scope — with the settlement seam holding the broadcast open until after the ViewModel scope has
 * been cancelled. The pure `mayReArmHandshakeMarker` predicate test cannot see this path.
 *
 * Fixture idiom mirrors RouteBootPubkeyReuseTest / RotateAndNotifyRaceTest: a ChatViewModel
 * allocated without its init-heavy constructor, a dynamic-Proxy [ZchatPreferences] that records
 * every `setOwnBootSent`, and the Main dispatcher set to an UnconfinedTestDispatcher. The bare
 * instance gets a real lifecycle `ViewModelImpl`, so `viewModelScope` is the one scope a clear
 * cancels. All keys are generated per run, all addresses are synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class KexBackOutMidSendNeverReArmsTest {
    private companion object {
        const val PEER = "usynthetickexbackoutpeer0000"
        const val SELF = "usynthetickexbackoutself0000"
        const val CONV_ID = "CONV1234" // 8 chars from ZMSGConstants.CONV_ID_CHARS (upper-case + digits)
        const val TXID = "synthetic-kex-txid"
        const val TIMEOUT_MS = 10_000L
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `backing out while the KEX broadcast is in flight never re-arms the KEX`() =
        runBlocking {
            val prefs = RecordingPrefs()
            val seam = HeldBroadcastSeam(SubmitResult.Success(txIds = listOf(TXID)))
            val vm = kexViewModel(prefs, seam)

            // ensureNostrBootstrapSent sets the marker, then launches the paid KEX.
            prefs.ownBootSent = true
            vm.sendKEXMessage(PEER, SELF)

            withTimeout(TIMEOUT_MS) { seam.entered.await() }
            // The user backs out: the ViewModel is cleared and its scope cancelled mid-broadcast.
            vm.viewModelScope.coroutineContext.job
                .cancelAndJoin()
            // The detached repository scope carries on and the KEX reaches the network anyway.
            seam.release.complete(Unit)
            withTimeout(TIMEOUT_MS) { seam.done.await() }

            assertEquals("fixture sanity: the KEX must have been broadcast", listOf(TXID), seam.broadcasts)
            assertEquals(
                "the KEX was broadcast and paid for, but backing out of the chat cleared isOwnBootSent. " +
                    "Reopening the chat before the peer's KEXACK lands then sends and pays for a second KEX. " +
                    "Cancellation is not failure: never re-arm on it (mayReArmHandshakeMarker).",
                emptyList<Boolean>(),
                prefs.ownBootSentWrites.filter { !it }
            )
            assertTrue("isOwnBootSent must still be set", prefs.ownBootSent)
        }

    /**
     * Control, GREEN before and after the fix: a KEX the SDK rejected outright (nothing landed) must
     * still re-arm, or a failed KEX leaves the handshake stuck forever.
     */
    @Test
    fun `control - a KEX the network rejected is still re-armed`() =
        runBlocking {
            val prefs = RecordingPrefs()
            val seam =
                HeldBroadcastSeam(SubmitResult.Failure(txIds = listOf(TXID), code = 18, description = "rejected"))
            val vm = kexViewModel(prefs, seam)

            prefs.ownBootSent = true
            seam.release.complete(Unit)
            vm.sendKEXMessage(PEER, SELF)
            withTimeout(TIMEOUT_MS) { seam.done.await() }
            vm.viewModelScope.coroutineContext.job.children
                .forEach { it.join() }

            assertEquals(listOf(false), prefs.ownBootSentWrites)
            vm.viewModelScope.coroutineContext.job
                .cancelAndJoin()
        }

    /**
     * Control, GREEN before and after the fix: a genuine exception in the KEX path before anything
     * is sent (here, the conversation id cannot be read) still re-arms through the catch.
     */
    @Test
    fun `control - a genuine error before the send is still re-armed`() =
        runBlocking {
            val prefs = RecordingPrefs(conversationIdFailure = IllegalStateException("synthetic prefs failure"))
            val seam = HeldBroadcastSeam(SubmitResult.Success(txIds = listOf(TXID)))
            val vm = kexViewModel(prefs, seam)

            prefs.ownBootSent = true
            vm.sendKEXMessage(PEER, SELF)
            vm.viewModelScope.coroutineContext.job.children
                .forEach { it.join() }

            assertEquals("the send must not have been reached", false, seam.entered.isCompleted)
            assertEquals(listOf(false), prefs.ownBootSentWrites)
            vm.viewModelScope.coroutineContext.job
                .cancelAndJoin()
        }

    // ── Harness ──────────────────────────────────────────────────────────────────────────────────

    private suspend fun kexViewModel(prefs: RecordingPrefs, seam: HeldBroadcastSeam): ChatViewModel {
        val accounts = Zc101FakeAccountDataSource(syntheticZashiAccount())
        val useCase =
            CreateChunkedMessageProposalUseCase(
                keystoneProposalRepository = Zc101FakeKeystoneProposalRepository(),
                zashiProposalRepository =
                    ZashiProposalRepositoryImpl(
                        accountDataSource = accounts,
                        proposalDataSource = seam,
                        zashiSpendingKeyDataSource = Zc101FakeZashiSpendingKeyDataSource()
                    ),
                accountDataSource = accounts,
                navigationRouter = Zc101RecordingNavigationRouter(),
                biometricRepository = ApprovingBiometricRepository
            )

        val vm = allocateChatViewModel()
        // A real lifecycle ViewModelImpl, so viewModelScope is stored and cancelled like a clear.
        val impl =
            Class
                .forName("androidx.lifecycle.viewmodel.internal.ViewModelImpl")
                .getDeclaredConstructor()
                .newInstance()
        ViewModel::class.java
            .getDeclaredField("impl")
            .apply { isAccessible = true }
            .set(vm, impl)
        setField(vm, "zchatPreferences", prefs.proxy)
        setField(vm, "createChunkedMessageProposal", useCase)
        setField(vm, "convIdMutex", Mutex())
        return vm
    }

    private fun allocateChatViewModel(): ChatViewModel {
        val unsafeClass = Class.forName("sun.misc.Unsafe")
        val unsafe = unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)
        val allocate = unsafeClass.getMethod("allocateInstance", Class::class.java)
        return allocate.invoke(unsafe, ChatViewModel::class.java) as ChatViewModel
    }

    private fun setField(vm: ChatViewModel, name: String, value: Any) {
        ChatViewModel::class.java
            .getDeclaredField(name)
            .apply { isAccessible = true }
            .set(vm, value)
    }

    /** Answers only the calls the KEX send path makes, and records every isOwnBootSent write. */
    private class RecordingPrefs(
        private val conversationIdFailure: Exception? = null
    ) {
        private val keys = E2EEncryption.generateKeyPair()

        @Volatile var ownBootSent = false

        @Volatile var paidRecord: String? = null
        val ownBootSentWrites: MutableList<Boolean> = Collections.synchronizedList(mutableListOf())

        val proxy: ZchatPreferences =
            Proxy.newProxyInstance(
                ZchatPreferences::class.java.classLoader,
                arrayOf(ZchatPreferences::class.java),
            ) { p, method, args ->
                when (method.name) {
                    "getE2EOurPublicKey" -> keys.publicKey
                    "getE2EPrivateKey" -> keys.privateKey
                    "getOrCreateConversationId" -> conversationIdFailure?.let { throw it } ?: (CONV_ID to false)
                    // R2-6 (c): the per-message call answers like the control-message one.
                    "getOrCreateConversationIdForMessage" -> conversationIdFailure?.let { throw it } ?: (CONV_ID to false)
                    // FC-ID (ZC-892): our KEX is built only under an id this phone made for the address; this fixture's id is that id.
                    "ownConversationId", "ownConversationIdForStart" -> conversationIdFailure?.let { throw it } ?: CONV_ID
                    "hasConsentedToHandshakeSpend" -> true
                    // T2-extra (a): the automatic-setup door asks whether the address is one of our own; this peer is not.
                    "isSelfAddress" -> false
                    // R2-2 (a): no chat request from this peer waits for Accept.
                    "getInboundKexAwaitingAccept" -> null
                    // P2b: the KEX re-reads the exchange state and its paid-setup record before each attempt.
                    "isE2EKeyExchangeComplete" -> false
                    "getHandshakePaidRecord" -> paidRecord
                    "setHandshakePaidRecord" -> {
                        paidRecord = args!![1] as String?
                        // R4-1: a working store reports its write.
                        true
                    }
                    "isOwnBootSent" -> ownBootSent
                    "setOwnBootSent" -> {
                        require(args!![0] == PEER) { "unexpected peer" }
                        val sent = args[1] as Boolean
                        ownBootSentWrites.add(sent)
                        ownBootSent = sent
                        // R5-2: a working store reports its write.
                        true
                    }
                    // T7BH-N2: the chat is the Tunnel chat this test describes (the automatic-handshake funnel reads the
                    // mode once T7BH-N2 lands; at earlier commits nothing asks it).
                    "getConversationMode" -> co.electriccoin.zcash.ui.screen.chat.model.ConversationMode.TUNNEL
                    // ZC-739-R1: each attempt re-reads the key-change flag; no new key waits here.
                    "isE2EKeyChanged" -> false
                    // ZC-1352: the KEX gate reads the mark before every attempt; no first message was deleted here.
                    "isAutomaticSetupWithdrawn" -> false
                    // The automatic-setup door reads the stored "Not now" marks and the wallet generation; none here.
                    "getDeclinedAutomaticSetups" -> emptySet<String>()
                    "walletScopeGeneration" -> 0L
                    "toString" -> "RecordingZchatPreferences"
                    "hashCode" -> System.identityHashCode(p)
                    "equals" -> p === args?.get(0)
                    else -> throw UnsupportedOperationException("unexpected ZchatPreferences call: ${method.name}")
                }
            } as ZchatPreferences
    }

    /**
     * The settlement seam under the real repository. Signals when the broadcast starts, holds it
     * until [release], then records it and returns [outcome].
     */
    private class HeldBroadcastSeam(
        private val outcome: SubmitResult,
        private val recording: Zc101RecordingProposalDataSource = Zc101RecordingProposalDataSource(),
    ) : ProposalDataSource by recording {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val done = CompletableDeferred<Unit>()
        val broadcasts: MutableList<String> = Collections.synchronizedList(mutableListOf())

        override suspend fun createZip321Proposal(
            account: WalletAccount,
            zip321Uri: String,
            allowMultiPayment: Boolean
        ): Zip321TransactionProposal = syntheticZip321Proposal(PEER, amount = 1_000L, memo = "synthetic-kex-memo")

        // F4: the 1:1 chat senders now call this overload (to record the stored ids before the submit). This fake
        // reports no stored ids: it settles through its own two-argument submit below, exactly as before F4.
        override suspend fun submitTransaction(
            proposal: Proposal,
            usk: UnifiedSpendingKey,
            onStored: co.electriccoin.zcash.ui.common.datasource.OnTransactionsStored,
        ): SubmitResult = submitTransaction(proposal, usk)

        override suspend fun submitTransaction(proposal: Proposal, usk: UnifiedSpendingKey): SubmitResult {
            entered.complete(Unit)
            release.await()
            if (outcome is SubmitResult.Success) broadcasts += TXID
            done.complete(Unit)
            return outcome
        }
    }

    private object ApprovingBiometricRepository : BiometricRepository {
        override val allowedAuthenticators: Int = 0

        override fun onBiometricResult(result: BiometricResult) = Unit

        override suspend fun requestBiometrics(request: BiometricRequest) = Unit
    }
}
