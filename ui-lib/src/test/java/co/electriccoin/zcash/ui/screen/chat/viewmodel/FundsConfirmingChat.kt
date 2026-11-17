package co.electriccoin.zcash.ui.screen.chat.viewmodel

import androidx.lifecycle.ViewModelStore
import cash.z.ecc.android.sdk.model.Proposal
import cash.z.ecc.android.sdk.model.UnifiedSpendingKey
import co.electriccoin.zcash.ui.common.datasource.OnTransactionsStored
import co.electriccoin.zcash.ui.common.datasource.ProposalDataSource
import co.electriccoin.zcash.ui.common.datasource.Zip321TransactionProposal
import co.electriccoin.zcash.ui.common.model.SubmitResult
import co.electriccoin.zcash.ui.common.model.WalletAccount
import co.electriccoin.zcash.ui.common.repository.ZashiProposalRepositoryImpl
import co.electriccoin.zcash.ui.common.usecase.Zc101FakeAccountDataSource
import co.electriccoin.zcash.ui.common.usecase.Zc101FakeKeystoneProposalRepository
import co.electriccoin.zcash.ui.common.usecase.Zc101FakeZashiSpendingKeyDataSource
import co.electriccoin.zcash.ui.common.usecase.Zc101RecordingNavigationRouter
import co.electriccoin.zcash.ui.common.usecase.syntheticZashiAccount
import co.electriccoin.zcash.ui.screen.chat.crypto.E2EEncryption
import co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferences
import co.electriccoin.zcash.ui.screen.chat.model.ChatMessage
import co.electriccoin.zcash.ui.screen.chat.model.ConversationMode
import co.electriccoin.zcash.ui.screen.chat.model.MessageStatus
import co.electriccoin.zcash.ui.screen.chat.model.SendMessageState
import co.electriccoin.zcash.ui.screen.chat.usecase.CreateChunkedMessageProposalUseCase
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * ZC-1271, ZC-1289, ZC-1290: one new contact in a Shielded chat over the real ChatViewModel send path, with the wallet's
 * funds under test control. Built on [FirstMessagePlanChat]; this adds:
 * - [plan], the plan check's answer now (WaitForFunds while the funds confirm, Fundable once they can be spent);
 * - [funds], the wallet under the real use case: the network fee of each build, what each submit paid and to whom, and
 *   a build that can be held open;
 * - [block], [leave] and [restart], which do what a new tip, leaving the chat and the end of the process do;
 * - optionally [competingSetupTo], a second contact whose automatic ZBOOT is due on every block.
 *
 * The seam the fix must add is reached by name so this compiles before it exists: `PendingMessageData.acceptedPriceZatoshi`,
 * the total the user accepted for the row, null on every other row. Every address and key is synthetic; nothing leaves the
 * fakes.
 */
internal class FundsConfirmingChat(
    val peer: String,
) {
    val chat = FirstMessagePlanChat(peer)
    val seam: HandshakeSeam get() = chat.fixture.seam
    val funds = MeteredFunds(seam)

    /** What the plan check answers now: WaitForFunds, Fundable, NotFundable. */
    @Volatile var plan: String = "WaitForFunds"

    @Volatile var consent: Boolean = true

    private var height = START_HEIGHT
    private var competing: String? = null
    private val sentBoot = ConcurrentHashMap<String, String>()
    private val paidRecords = ConcurrentHashMap<String, String>()

    /** A Tunnel contact with a finished key exchange and no ZBOOT sent yet: every block offers to pay it. */
    fun competingSetupTo(other: String) {
        competing = other
        val ours = E2EEncryption.generateKeyPair()
        chat.fixture.proxy.setE2EOurKeys(other, ours.publicKey, ours.privateKey)
    }

    /** One chat screen (a ChatViewModel) over this phone's store and wallet. Each screen has its own scope and queue. */
    suspend fun screen(): ChatViewModel {
        val vm = chat.viewModel(null, leaveCheckNull = true)
        FirstMessagePlanApi.install(vm, planCheck())
        PaymentHarness.setField(vm, "zchatPreferences", store)
        PaymentHarness.setField(vm, "createChunkedMessageProposal", useCase())
        AutoSpendVm.setField(vm, "addressCache", NoOpAddressCache.proxy)
        return vm
    }

    /** A new tip reaches [screens], in the order observeBlockHeight does it: the height, then the tip's work. */
    fun block(vararg screens: ChatViewModel) {
        height++
        for (vm in screens) {
            PaymentHarness.setBlockHeight(vm, height)
            chat.tick(vm, height)
        }
    }

    /** The screen goes away: its ViewModelStore is cleared (scope cancelled, then onCleared). */
    fun leave(vm: ChatViewModel) {
        ViewModelStore().apply { put("chat", vm) }.clear()
    }

    /**
     * The process ended and started again: every job and every process-wide set is gone, only the store is left. The new
     * screen has run its loader, as a fresh ChatViewModel does on start.
     */
    suspend fun restart(vararg oldScreens: ChatViewModel): ChatViewModel {
        oldScreens.forEach(::leave)
        HandshakeHarness.cancelHeldAcceptSends()
        AutoSpendVm.resetProcessState()
        AutoSpendVm.setHistoryRead(true)
        FirstMessagePlanChat.resetOwedKexState()
        LiveSenders.resetForTest()
        return screen().also(PaymentHarness::loadPendingMessagesFromPrefs)
    }

    /** The first-message question on screen, as (transactions, total zatoshi); fails when the send was refused instead. */
    fun question(vm: ChatViewModel): Pair<Int, Long> {
        val state = HandshakeHarness.sendMessageState(vm)
        val asked =
            (state as? SendMessageState.NeedsPaidActionConfirmation)
                ?: throw AssertionError("a first message with funds still confirming was not asked its price: the state is $state")
        return asked.confirmation.onChainMessages to asked.confirmation.totalCost.value
    }

    /** Sends [text] with the funds still confirming and answers Pay; returns the saved row's id and the accepted total. */
    suspend fun acceptWhileConfirming(
        vm: ChatViewModel,
        text: String,
    ): AcceptedRow {
        chat.typeAndSend(vm, text)
        val total = question(vm).second
        vm.confirmPaidAction()
        if (!PaymentHarness.eventually(SETTLE_MS) { row() != null }) {
            throw AssertionError("the accepted first message was not saved as a row; the state is ${HandshakeHarness.sendMessageState(vm)}")
        }
        return AcceptedRow(row()!!.id, total)
    }

    /** The one outgoing on-chain row of [peer] on disk, or null. */
    fun row(): ZchatPreferences.PendingMessageData? =
        chat.prefs.getPendingMessages().firstOrNull { it.peerAddress == peer && it.isOutgoing && it.id.startsWith("pending_") }

    fun shown(
        vm: ChatViewModel,
        id: String,
    ): ChatMessage? = PaymentHarness.pendingMessages(vm).firstOrNull { it.id == id }

    /** The row stands as waiting: on disk in flight and not FAILED (a row the loader or a refusal failed reads as FAILED). */
    fun isWaiting(id: String): Boolean = row()?.let { it.id == id && it.isPending && it.status != MessageStatus.FAILED.name } == true

    /** The row stopped and asks the user: not sent, with its reason on the row, or a question on screen. */
    fun needsDecision(
        vm: ChatViewModel,
        id: String,
    ): Boolean {
        val stored = row()?.takeIf { it.id == id }
        val failedWithReason =
            stored != null &&
                stored.status == MessageStatus.FAILED.name &&
                !stored.isPending &&
                !stored.failureReason.isNullOrBlank()
        return failedWithReason || HandshakeHarness.sendMessageState(vm) is SendMessageState.NeedsPaidActionConfirmation
    }

    /** Whether no further submit arrives after [count] within a short real-time window. */
    suspend fun staysAt(count: Int): Boolean {
        PaymentHarness.eventually(QUIET_MS) { seam.submits.get() > count }
        return seam.submits.get() == count
    }

    /** Waits until submits stop arriving, so a money assertion after it reads the final figure. */
    suspend fun paymentsSettled() {
        while (!staysAt(seam.submits.get())) Unit
    }

    private fun planCheck(): Any {
        val iface = FirstMessagePlanApi.cls(FirstMessagePlanApi.CHECK)
        return Proxy.newProxyInstance(iface.classLoader, arrayOf(iface)) { p, method, args ->
            when (method.name) {
                "check" -> FirstMessagePlanApi.answer(plan)
                "toString" -> "SwitchablePlan"
                "hashCode" -> System.identityHashCode(p)
                "equals" -> p === args?.get(0)
                else -> throw UnsupportedOperationException("unexpected plan check call: ${method.name}")
            }
        }
    }

    private suspend fun useCase(): CreateChunkedMessageProposalUseCase {
        val accounts = Zc101FakeAccountDataSource(syntheticZashiAccount())
        return CreateChunkedMessageProposalUseCase(
            keystoneProposalRepository = Zc101FakeKeystoneProposalRepository(),
            zashiProposalRepository =
                ZashiProposalRepositoryImpl(
                    accountDataSource = accounts,
                    proposalDataSource = funds,
                    zashiSpendingKeyDataSource = Zc101FakeZashiSpendingKeyDataSource(),
                ).also { SendHarnessScopes.current().own(it) },
            accountDataSource = accounts,
            navigationRouter = Zc101RecordingNavigationRouter(),
            biometricRepository = HandshakeHarness.ApprovingBiometricRepository,
        )
    }

    /** [FirstMessagePlanChat.prefs], with the standing consent under test control and the competing contact's records. */
    @Suppress("CyclomaticComplexMethod")
    private val store: ZchatPreferences =
        Proxy.newProxyInstance(
            ZchatPreferences::class.java.classLoader,
            arrayOf(ZchatPreferences::class.java),
        ) { _, method, args ->
            fun arg(i: Int) = args!![i]
            val other = competing
            val forOther = other != null && args?.firstOrNull() == other
            when {
                method.name == "hasConsentedToHandshakeSpend" -> consent
                method.name == "getTunnelModePeers" -> listOfNotNull(other)
                method.name == "getSentNostrBootPubkey" -> sentBoot[arg(0) as String]
                method.name == "setSentNostrBootPubkey" -> {
                    val value = arg(1) as String?
                    if (value == null) sentBoot.remove(arg(0) as String) else sentBoot[arg(0) as String] = value
                    true
                }
                method.name == "getHandshakePaidRecord" -> paidRecords[arg(0) as String]
                method.name == "setHandshakePaidRecord" -> {
                    val value = arg(1) as String?
                    if (value == null) paidRecords.remove(arg(0) as String) else paidRecords[arg(0) as String] = value
                    true
                }
                forOther && method.name == "isE2EKeyExchangeComplete" -> true
                forOther && method.name == "getE2EPeerPublicKey" -> otherPeerKey
                forOther && (method.name == "getConversationMode" || method.name == "getConversationModeOrNull") ->
                    ConversationMode.TUNNEL
                else ->
                    try {
                        method.invoke(chat.prefs, *(args ?: emptyArray()))
                    } catch (e: InvocationTargetException) {
                        throw e.targetException
                    }
            }
        } as ZchatPreferences

    private val otherPeerKey: String by lazy { E2EEncryption.generateKeyPair().publicKey }

    data class AcceptedRow(
        val id: String,
        val acceptedTotal: Long,
    )

    /**
     * The wallet under the use case. [feeFor] decides the network fee of each build from its ZIP 321 request, [paid] holds
     * (request, what that submit cost) in submit order, and with [parkFirstBuild] the first build stays open until
     * [firstBuildGate] completes.
     */
    internal class MeteredFunds(
        private val seam: HandshakeSeam,
    ) : ProposalDataSource by seam {
        @Volatile var feeFor: (String) -> Long = { NORMAL_FEE }

        @Volatile var parkFirstBuild: Boolean = false
        val firstBuildEntered = CompletableDeferred<Unit>()
        val firstBuildGate = CompletableDeferred<Unit>()

        /** ZC-1992: with [parkBuild] = n (> 0), the n-th build stays open until [buildGate] completes ([buildEntered] then). */
        @Volatile var parkBuild: Int = 0
        val buildEntered = CompletableDeferred<Unit>()
        val buildGate = CompletableDeferred<Unit>()
        val paid: MutableList<Pair<String, Long>> = Collections.synchronizedList(mutableListOf())
        private val builds = AtomicInteger(0)
        private val built = Collections.synchronizedMap(IdentityHashMap<Proposal, Pair<String, Long>>())
        private val buildLock = Mutex()

        /** What was paid to an address whose request names [address]. */
        fun paidTo(address: String): Long = paid.toList().filter { address in it.first }.sumOf { it.second }

        override suspend fun createZip321Proposal(
            account: WalletAccount,
            zip321Uri: String,
            allowMultiPayment: Boolean,
        ): Zip321TransactionProposal {
            val build = builds.incrementAndGet()
            if (build == 1 && parkFirstBuild) {
                firstBuildEntered.complete(Unit)
                firstBuildGate.await()
            }
            if (build == parkBuild) {
                buildEntered.complete(Unit)
                buildGate.await()
            }
            val fee = feeFor(zip321Uri)
            return buildLock.withLock {
                seam.proposalFee = fee
                seam.createZip321Proposal(account, zip321Uri, allowMultiPayment).also {
                    built[it.proposal] = zip321Uri to (fee + LEG_OUTPUTS)
                }
            }
        }

        // A memo sender with no ledger comes through this overload, a message through the one below.
        override suspend fun submitTransaction(
            proposal: Proposal,
            usk: UnifiedSpendingKey,
        ): SubmitResult {
            paid += built[proposal] ?: ("unknown" to 0L)
            return seam.submitTransaction(proposal, usk)
        }

        override suspend fun submitTransaction(
            proposal: Proposal,
            usk: UnifiedSpendingKey,
            onStored: OnTransactionsStored,
        ): SubmitResult {
            paid += built[proposal] ?: ("unknown" to 0L)
            return seam.submitTransaction(proposal, usk, onStored)
        }
    }

    companion object {
        const val SETTLE_MS = 2_000L
        const val QUIET_MS = 300L
        const val START_HEIGHT = 3_500_000L
        const val NORMAL_FEE = 15_000L

        /** A message leg's outputs on top of its fee: the 1,000 zatoshi delivered and the 1,000 zatoshi ZChat fee. */
        const val LEG_OUTPUTS = 2_000L

        /** The fix's new row field, by name so this compiles before it exists. */
        fun acceptedPrice(row: ZchatPreferences.PendingMessageData): Long? {
            val getter =
                ZchatPreferences.PendingMessageData::class.java.methods.firstOrNull { it.name == "getAcceptedPriceZatoshi" }
                    ?: throw AssertionError("the saved row has no accepted price (no PendingMessageData.acceptedPriceZatoshi)")
            return getter.invoke(row) as Long?
        }
    }
}
