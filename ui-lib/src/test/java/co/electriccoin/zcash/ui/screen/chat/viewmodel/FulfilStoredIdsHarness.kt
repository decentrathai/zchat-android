package co.electriccoin.zcash.ui.screen.chat.viewmodel

import androidx.lifecycle.viewModelScope
import cash.z.ecc.android.sdk.model.BlockHeight
import cash.z.ecc.android.sdk.model.Proposal
import cash.z.ecc.android.sdk.model.TransactionId
import cash.z.ecc.android.sdk.model.TransactionOverview
import cash.z.ecc.android.sdk.model.TransactionState
import cash.z.ecc.android.sdk.model.UnifiedSpendingKey
import cash.z.ecc.android.sdk.model.Zatoshi
import cash.z.ecc.android.sdk.model.Zip318Kind
import co.electriccoin.zcash.ui.common.datasource.OnTransactionsStored
import co.electriccoin.zcash.ui.common.datasource.ProposalDataSource
import co.electriccoin.zcash.ui.common.datasource.StoredTransaction
import co.electriccoin.zcash.ui.common.datasource.Zip321TransactionProposal
import co.electriccoin.zcash.ui.common.model.SubmitResult
import co.electriccoin.zcash.ui.common.model.WalletAccount
import co.electriccoin.zcash.ui.common.repository.SendTransaction
import co.electriccoin.zcash.ui.common.repository.Transaction
import co.electriccoin.zcash.ui.common.repository.ZashiProposalRepositoryImpl
import co.electriccoin.zcash.ui.common.usecase.Zc101FakeAccountDataSource
import co.electriccoin.zcash.ui.common.usecase.Zc101FakeKeystoneProposalRepository
import co.electriccoin.zcash.ui.common.usecase.Zc101FakeZashiSpendingKeyDataSource
import co.electriccoin.zcash.ui.common.usecase.Zc101RecordingNavigationRouter
import co.electriccoin.zcash.ui.common.usecase.Zc101RecordingProposalDataSource
import co.electriccoin.zcash.ui.common.usecase.syntheticZashiAccount
import co.electriccoin.zcash.ui.common.usecase.syntheticZip321Proposal
import co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferences
import co.electriccoin.zcash.ui.screen.chat.model.ChatMessage
import co.electriccoin.zcash.ui.screen.chat.model.ConversationMode
import co.electriccoin.zcash.ui.screen.chat.model.MessageStatus
import co.electriccoin.zcash.ui.screen.chat.model.PaymentRequestInfo
import co.electriccoin.zcash.ui.screen.chat.model.SysNotes
import co.electriccoin.zcash.ui.screen.chat.usecase.CreateChunkedMessageProposalUseCase
import co.electriccoin.zcash.ui.screen.chat.viewmodel.SendAttemptHarness.ChainTx
import co.electriccoin.zcash.ui.screen.chat.viewmodel.SendAttemptHarness.Wallet
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.job
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * F5 (fix-all 2.13.0, train T5; SPECS §1.3): a payment-request fulfil whose stored transaction ids reach the durable
 * started record, and the history passes that settle it from the chain.
 *
 * - [IdsSeam]: the settlement seam under the real use case and repository. Its three-argument submit (the one F2's
 *   onTransactionsStored rides) hands [IdsSeam.stored] to onStored before it answers with the scripted outcome; its
 *   two-argument submit (a caller that passes no onTransactionsStored) answers without it.
 * - [wrap]: a preferences wrapper over [PaymentPrefs] (or over the store [SendAttemptHarness.install] puts in front of
 *   it) that answers `recordFulfilStored(requestId, startedAtMillis, stored)` and serves `getFulfilStarted` with the
 *   recorded ids and expiries, built through FulfilStarted's widest constructor by reflection (so this file compiles
 *   while the record has only its five Round 8 fields).
 * - [passAt]: one complete history pass (the real convertToConversations) over a [SendAttemptHarness.Wallet], whose
 *   fresh read through the synchronizer (install) is what the id path reads. Unlike SendAttemptHarness.runPass, each
 *   transaction is listed as the repository lists it: mined = SendTransaction.Success, unmined and at or below the
 *   scanned height's expiry = SendTransaction.Failed (the SDK's "Expired"), else SendTransaction.Pending.
 *
 * No line of PaymentSendHarness, SendAttemptHarness or PendingReconcileHarness changes. Every address, txid and memo is
 * synthetic.
 */
internal class FulfilStoredIdsHarness(
    val peer: String,
) {
    val prefs = PaymentPrefs(peer, mode = ConversationMode.TUNNEL)

    /** Request id to the ids and expiries the product recorded through recordFulfilStored. */
    val recorded = ConcurrentHashMap<String, Pair<List<String>, Map<String, Long>>>()

    /** Makes every recordFulfilStored answer false (the write did not reach the disk). */
    @Volatile var recordFails = false

    val recordCalls = AtomicInteger()

    /** The store the view model reads: [base] with the fulfil's stored-ids record on top. */
    fun wrap(base: ZchatPreferences): ZchatPreferences =
        Proxy.newProxyInstance(
            ZchatPreferences::class.java.classLoader,
            arrayOf(ZchatPreferences::class.java),
        ) { p, method, args ->
            when (method.name) {
                "recordFulfilStored" -> record(args!![0] as String, args[1] as Long, args[2] as List<*>)
                "getFulfilStarted" -> {
                    @Suppress("UNCHECKED_CAST")
                    val records = forward(base, method, args) as Map<String, ZchatPreferences.FulfilStarted>
                    records.mapValues { (id, r) -> widen(r, recorded[id]) }
                }
                "markFulfilStarted", "clearFulfilStarted" -> {
                    recorded.remove(args!![0] as String)
                    forward(base, method, args)
                }
                "hashCode" -> System.identityHashCode(p)
                "equals" -> p === args?.get(0)
                "toString" -> "FulfilStoredIdsPrefs"
                else -> forward(base, method, args)
            }
        } as ZchatPreferences

    private fun record(
        requestId: String,
        startedAtMillis: Long,
        stored: List<*>,
    ): Boolean {
        recordCalls.incrementAndGet()
        if (recordFails) return false
        val started = prefs.started[requestId] ?: return false
        if (started.startedAtMillis != startedAtMillis) return false
        val list = stored.map { it as StoredTransaction }
        recorded[requestId] = list.map { it.txId } to list.mapNotNull { s -> s.expiryHeight?.let { s.txId to it } }.toMap()
        return true
    }

    /** The view model a chat screen runs for [requestId]'s free (relay) request row, paying through [seam]. */
    suspend fun viewModel(
        seam: ProposalDataSource,
        requestId: String,
        amount: Long,
    ): ChatViewModel {
        val vm = PaymentHarness.paymentViewModel(prefs, PaymentSeam(peer))
        PaymentHarness.setField(vm, "createChunkedMessageProposal", useCase(seam))
        PaymentHarness.setField(vm, "zchatPreferences", wrap(prefs.proxy))
        PaymentHarness.setField(vm, "restoreRecheck", MutableStateFlow(0))
        PaymentHarness.setField(vm, "pendingMessages", MutableStateFlow(listOf(nostrRequest(requestId, amount))))
        return vm
    }

    /** Puts [wallet] behind [vm] (SendAttemptHarness.install), keeping this harness's record on top of its store. */
    fun installWallet(
        vm: ChatViewModel,
        wallet: Wallet,
    ) {
        SendAttemptHarness.install(vm, prefs, wallet)
        val installed =
            ChatViewModel::class.java
                .getDeclaredField("zchatPreferences")
                .apply { isAccessible = true }
                .get(vm) as ZchatPreferences
        PaymentHarness.setField(vm, "zchatPreferences", wrap(installed))
    }

    /** One complete history pass at scanned (and network) height [tip]; every row it rendered. */
    suspend fun passAt(
        vm: ChatViewModel,
        wallet: Wallet,
        tip: Long,
    ): List<ChatMessage> {
        wallet.scanned = tip
        wallet.network = tip
        PaymentHarness.setBlockHeight(vm, tip)
        val method =
            ChatViewModel::class.java.declaredMethods
                .single { it.name == "convertToConversations" && it.parameterCount == 7 }
                .apply { isAccessible = true }
        val before =
            vm.viewModelScope.coroutineContext.job.children
                .toSet()
        try {
            return kotlin.coroutines.intrinsics
                .suspendCoroutineUninterceptedOrReturn<List<co.electriccoin.zcash.ui.screen.chat.model.Conversation>> { continuation ->
                    try {
                        method.invoke(
                            vm,
                            transactions(wallet),
                            PaymentHarness.SELF,
                            emptySet<String>(),
                            PaymentHarness.pendingMessages(vm),
                            emptySet<String>(),
                            true,
                            continuation,
                        )
                    } catch (e: InvocationTargetException) {
                        throw e.targetException
                    }
                }.flatMap { it.messages }
        } finally {
            vm.viewModelScope.coroutineContext.job.children
                .filter { it !in before }
                .forEach { it.cancel() }
        }
    }

    /** Our payment of [requestId] as the wallet holds it; [memoRead] false: the SDK returned no memo for it yet. */
    fun fulfilTx(
        number: Int,
        requestId: String,
        minedHeight: Long?,
        expiryHeight: Long,
        amount: Long,
        memoRead: Boolean = true,
    ): ChainTx =
        ChainTx(
            number = number,
            recipient = peer,
            memo = if (memoRead) "ZREQ_FULFILL|$requestId" else null,
            minedHeight = minedHeight,
            expiryHeight = expiryHeight,
            amount = amount,
        )

    /** The notes in the requester's chat. */
    fun notes(): List<ZchatPreferences.PendingMessageData> =
        prefs.pending.values.filter { SysNotes.isSystemNoteId(it.id) && it.peerAddress == peer }

    private fun transactions(wallet: Wallet): List<Transaction> =
        wallet.txs.map { tx ->
            val id = TransactionId.new("%064x".format(tx.number))
            val overview = overview(id, tx, wallet.scanned)
            val amount = Zatoshi(tx.amount)
            val fee = Zatoshi(FEE)
            when {
                tx.minedHeight != null ->
                    SendTransaction.Success(id, amount, tx.blockTime, 1, fee, emptyList(), overview, null)
                tx.expiryHeight != null && tx.expiryHeight in 1..wallet.scanned ->
                    SendTransaction.Failed(id, amount, tx.blockTime, 1, fee, emptyList(), overview, null)
                else -> SendTransaction.Pending(id, amount, tx.blockTime, 1, fee, emptyList(), overview, null)
            }
        }

    private fun overview(
        id: TransactionId,
        tx: ChainTx,
        scanned: Long,
    ): TransactionOverview =
        TransactionOverview(
            id,
            tx.minedHeight?.let { BlockHeight.new(it) },
            tx.expiryHeight?.let { BlockHeight.new(it) },
            null,
            null,
            true,
            Zatoshi(tx.amount + FEE),
            Zatoshi(tx.amount + FEE),
            Zatoshi(0),
            Zatoshi(FEE),
            false,
            0,
            1,
            if (tx.memo != null) 1 else 0,
            tx.blockTime?.epochSecond,
            when {
                tx.minedHeight != null -> TransactionState.Confirmed
                tx.expiryHeight != null && tx.expiryHeight in 1..scanned -> TransactionState.Expired
                else -> TransactionState.Pending
            },
            false,
            1,
            Zatoshi(0),
            true,
            Zip318Kind.NOT_CLASSIFIED,
        )

    private fun nostrRequest(
        id: String,
        amount: Long,
    ): ChatMessage =
        ChatMessage(
            id = id,
            txId = null,
            text = REASON,
            timestamp = Instant.now(),
            isOutgoing = false,
            peerAddress = peer,
            isPending = false,
            status = MessageStatus.SENT,
            paymentRequest = PaymentRequestInfo(amountZatoshi = amount, reason = REASON, isPaid = false),
            authenticated = true,
        )

    /** The real use case and repository over [seam]. */
    private suspend fun useCase(seam: ProposalDataSource) =
        CreateChunkedMessageProposalUseCase(
            keystoneProposalRepository = Zc101FakeKeystoneProposalRepository(),
            zashiProposalRepository =
                ZashiProposalRepositoryImpl(
                    accountDataSource = Zc101FakeAccountDataSource(syntheticZashiAccount()),
                    proposalDataSource = seam,
                    zashiSpendingKeyDataSource = Zc101FakeZashiSpendingKeyDataSource(),
                ).also(SendHarnessScopes.current()::own),
            accountDataSource = Zc101FakeAccountDataSource(syntheticZashiAccount()),
            navigationRouter = Zc101RecordingNavigationRouter(),
            biometricRepository = HandshakeHarness.ApprovingBiometricRepository,
        )

    /**
     * The settlement seam: the three-argument submit hands [stored] to onStored (F2's step before the first submit)
     * and then answers [outcome]; the two-argument submit answers [outcome] with no record. [submits] counts both.
     */
    class IdsSeam(
        private val peer: String,
        private val stored: List<StoredTransaction>,
        private val outcome: SubmitResult,
        private val recording: Zc101RecordingProposalDataSource = Zc101RecordingProposalDataSource(),
    ) : ProposalDataSource by recording {
        val submits = AtomicInteger(0)

        override suspend fun createZip321Proposal(
            account: WalletAccount,
            zip321Uri: String,
            allowMultiPayment: Boolean,
        ): Zip321TransactionProposal = syntheticZip321Proposal(peer, amount = 1_000L, memo = "synthetic-fulfil-memo")

        override suspend fun submitTransaction(
            proposal: Proposal,
            usk: UnifiedSpendingKey,
            onStored: OnTransactionsStored,
        ): SubmitResult {
            submits.incrementAndGet()
            onStored(stored)
            return outcome
        }

        override suspend fun submitTransaction(
            proposal: Proposal,
            usk: UnifiedSpendingKey,
        ): SubmitResult {
            submits.incrementAndGet()
            return outcome
        }
    }

    companion object {
        const val REASON = "synthetic dinner share"
        private const val FEE = 10_000L

        /** Whether the request row says "Payment pending" (null: PaymentRequestInfo has no such field yet). */
        fun paymentPending(row: ChatMessage): Boolean? {
            val request = row.paymentRequest ?: return null
            return runCatching { request.javaClass.getMethod("getPaymentPending").invoke(request) as Boolean }.getOrNull()
        }

        @Suppress("UNCHECKED_CAST")
        fun paidInMemory(vm: ChatViewModel): Set<String> =
            (
                ChatViewModel::class.java
                    .getDeclaredField("paidRequestIds")
                    .apply { isAccessible = true }
                    .get(vm) as MutableStateFlow<Set<String>>
            ).value

        /** The resource id of [name] (R.string), or null while it does not exist. */
        fun stringId(name: String): Int? =
            runCatching {
                co.electriccoin.zcash.ui.R.string::class.java
                    .getField(name)
                    .getInt(null)
            }.getOrNull()

        private fun forward(
            base: ZchatPreferences,
            method: Method,
            args: Array<out Any?>?,
        ): Any? =
            try {
                method.invoke(base, *(args ?: emptyArray()))
            } catch (e: InvocationTargetException) {
                throw e.targetException
            }

        /** [record] with [ids] through FulfilStarted's widest constructor, when it takes them; else [record] itself. */
        private fun widen(
            record: ZchatPreferences.FulfilStarted,
            ids: Pair<List<String>, Map<String, Long>>?,
        ): ZchatPreferences.FulfilStarted {
            if (ids == null) return record
            val widest =
                ZchatPreferences.FulfilStarted::class.java.constructors
                    .filter { c -> !c.isSynthetic && c.parameterTypes.none { it.name.endsWith("DefaultConstructorMarker") } }
                    .maxByOrNull { it.parameterCount }
                    ?: return record
            if (widest.parameterCount < WIDE_PARAMETERS) return record
            return widest.newInstance(
                record.attemptHeight,
                record.peerHash,
                record.amountZatoshi,
                record.selfHash,
                record.startedAtMillis,
                ids.first,
                ids.second,
            ) as ZchatPreferences.FulfilStarted
        }

        private const val WIDE_PARAMETERS = 7
    }
}
