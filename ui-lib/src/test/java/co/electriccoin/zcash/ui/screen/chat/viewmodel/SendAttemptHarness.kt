package co.electriccoin.zcash.ui.screen.chat.viewmodel

import androidx.lifecycle.viewModelScope
import cash.z.ecc.android.sdk.Synchronizer
import cash.z.ecc.android.sdk.model.BlockHeight
import cash.z.ecc.android.sdk.model.TransactionId
import cash.z.ecc.android.sdk.model.TransactionOverview
import cash.z.ecc.android.sdk.model.TransactionRecipient
import cash.z.ecc.android.sdk.model.TransactionState
import cash.z.ecc.android.sdk.model.Zatoshi
import cash.z.ecc.android.sdk.model.Zip318Kind
import co.electriccoin.zcash.ui.common.provider.SynchronizerProvider
import co.electriccoin.zcash.ui.common.repository.SendTransaction
import co.electriccoin.zcash.ui.common.repository.Transaction
import co.electriccoin.zcash.ui.common.repository.TransactionRepository
import co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferences
import co.electriccoin.zcash.ui.screen.chat.model.ChatMessage
import co.electriccoin.zcash.ui.screen.chat.model.ContactBook
import co.electriccoin.zcash.ui.screen.chat.model.Conversation
import co.electriccoin.zcash.ui.screen.chat.model.MessageStatus
import co.electriccoin.zcash.ui.screen.chat.model.ZMSGProtocol
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.job
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.time.Instant
import java.util.Collections
import java.util.concurrent.atomic.AtomicLong

/**
 * F4 (fix-all 2.13.0, train T4): a wallet for the send-attempt tests (SPECS §1.3).
 *
 * It runs the REAL chat history pass (convertToConversations, with the history scan complete) against a synthetic
 * wallet whose fully scanned height, network height and transactions a test sets per pass. The wallet answers both
 * the pass's own transaction list and a fresh read of the ZCHAT account's transactions from the SDK
 * (Synchronizer.getTransactions(account) and getRecipients()), so a resolver that reads the chain itself sees the
 * same facts. The view model is [PaymentHarness.paymentViewModel]'s (the real send, retry and claim paths over the
 * map-backed [PaymentPrefs]); nothing here changes a line of PendingReconcileHarness or RetryNeverPaysTwiceTest.
 *
 * Every address, txid and memo is synthetic.
 */
internal object SendAttemptHarness {
    const val AMOUNT = 50_000L

    /** A height well past the Ironwood activation, as on mainnet today; every test offsets from it. */
    const val H = 3_200_000L

    /** One of our own sent transactions (the harness wallet holds only sent ones). */
    class ChainTx(
        val number: Int,
        val recipient: String?,
        /** Null: the SDK returned no memo for it (not read yet). */
        val memo: String?,
        val minedHeight: Long?,
        val expiryHeight: Long?,
        val amount: Long = AMOUNT,
        val blockTime: Instant? = minedHeight?.let { Instant.now() },
    )

    /** What the wallet shows at the next pass. */
    class Wallet(
        @Volatile var scanned: Long,
        @Volatile var network: Long = scanned,
        @Volatile var txs: List<ChainTx> = emptyList(),
    )

    /** Counts the durable writes of one row through the view model (the store's own map is [PaymentPrefs.pending]). */
    class Writes {
        val rows: MutableList<ZchatPreferences.PendingMessageData> = Collections.synchronizedList(mutableListOf())

        /** The writes of [id] that turned it into a FAILED row with no submit record and no transaction id. */
        fun notSentConversions(id: String): Int =
            rows.count {
                it.id == id &&
                    it.status == MessageStatus.FAILED.name &&
                    !it.isPending &&
                    it.submitStartedAtMillis == null &&
                    it.submittedTxIds.isEmpty()
            }
    }

    fun txId(n: Int): String = TransactionId.new("%064x".format(n)).txIdString()

    private val rowCounter = AtomicLong(System.nanoTime())

    /**
     * A fresh "pending_" row id. The proof of a send attempt is kept per process (every test class of a Gradle
     * worker shares it), so every test uses its own ids.
     */
    fun newRowId(): String = "pending_" + rowCounter.incrementAndGet()

    /** An outgoing message memo the pass reads as [text] from us to the contact. */
    fun messageMemo(text: String): String = ZMSGProtocol.createLegacyMessage(PaymentHarness.SELF, text)

    /**
     * A row as a 2.13.0 send leaves it after its submit started and the outcome was ambiguous
     * (keepPossiblySentFailure): FAILED, tracked, with its marker and the ids it got.
     */
    fun possiblySentRow(
        id: String,
        peer: String,
        text: String,
        txIds: List<String>,
        attemptHeight: Long?,
        marker: Long = System.currentTimeMillis() - 60_000L,
    ): ZchatPreferences.PendingMessageData =
        ZchatPreferences.PendingMessageData(
            id = id,
            text = text,
            timestampMillis = marker - 1_000L,
            peerAddress = peer,
            isOutgoing = true,
            isPending = false,
            status = MessageStatus.FAILED.name,
            sendAmountZatoshi = AMOUNT,
            submitTracked = true,
            submitStartedAtMillis = marker,
            submitAttemptHeight = attemptHeight,
            submittedTxIds = txIds,
        )

    /** A row a 2.12.4 build wrote before its broadcast: no tracking flag, no status, still pending. */
    fun legacyRow(
        id: String,
        peer: String,
        text: String,
        writtenAt: Instant,
    ): ZchatPreferences.PendingMessageData =
        ZchatPreferences.PendingMessageData(
            id = id,
            text = text,
            timestampMillis = writtenAt.toEpochMilli(),
            peerAddress = peer,
        )

    /** The view model a chat screen runs, with the rows on disk loaded as a fresh screen loads them. */
    suspend fun openChat(
        prefs: PaymentPrefs,
        seam: PaymentSeam,
        wallet: Wallet,
        writes: Writes = Writes(),
    ): ChatViewModel {
        val vm = PaymentHarness.paymentViewModel(prefs, seam)
        install(vm, prefs, wallet, writes)
        PaymentHarness.loadPendingMessagesFromPrefs(vm)
        return vm
    }

    /** Installs [wallet] (and the store wrapper the history pass needs) on a view model made elsewhere. */
    fun install(
        vm: ChatViewModel,
        prefs: PaymentPrefs,
        wallet: Wallet,
        writes: Writes = Writes(),
    ) {
        PaymentHarness.setField(vm, "zchatPreferences", scanPrefs(prefs, writes))
        val contactBook =
            Proxy.newProxyInstance(ContactBook::class.java.classLoader, arrayOf(ContactBook::class.java)) { _, method, _ ->
                when (method.name) {
                    "getContact" -> null
                    else -> error("unexpected ContactBook.${method.name}")
                }
            }
        PaymentHarness.setField(vm, "contactBook", contactBook)
        PaymentHarness.setField(vm, "transactionRepository", repository(wallet))
        PaymentHarness.setField(vm, "synchronizerProvider", provider(wallet))
        PaymentHarness.setBlockHeight(vm, wallet.network)
    }

    /**
     * One complete history pass of [vm] at the wallet's current heights: the network height the view model knows is
     * set first (as the app's block-height flow would), then the real convertToConversations runs with the history
     * scan complete. Returns every row it rendered.
     */
    suspend fun runPass(
        vm: ChatViewModel,
        wallet: Wallet,
    ): List<ChatMessage> = runPassConversations(vm, wallet).flatMap { it.messages }

    suspend fun runPassConversations(
        vm: ChatViewModel,
        wallet: Wallet,
    ): List<Conversation> {
        PaymentHarness.setBlockHeight(vm, wallet.network)
        val transactions = transactions(wallet)
        val method =
            ChatViewModel::class.java.declaredMethods
                .single { it.name == "convertToConversations" && it.parameterCount == 7 }
                .apply { isAccessible = true }
        // A complete pass may schedule a delayed re-check of a restored wallet (RESTORE_RECHECK_DELAY_MS) on the
        // screen's scope; under the test Main dispatcher that delay never ends, so the jobs the pass started are
        // cancelled after it (they only re-run the pass, which each test drives itself).
        val before =
            vm.viewModelScope.coroutineContext.job.children
                .toSet()
        try {
            return runPassOnce(vm, method, transactions)
        } finally {
            vm.viewModelScope.coroutineContext.job.children
                .filter { it !in before }
                .forEach { it.cancel() }
        }
    }

    private suspend fun runPassOnce(
        vm: ChatViewModel,
        method: java.lang.reflect.Method,
        transactions: List<Transaction>,
    ): List<Conversation> =
        kotlin.coroutines.intrinsics
            .suspendCoroutineUninterceptedOrReturn { continuation ->
                try {
                    method.invoke(
                        vm,
                        transactions,
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
            }

    /** The rows of one message as the chat shows them: the pending row itself, or a chain row carrying one of [txIds]. */
    fun bubblesOf(
        rendered: List<ChatMessage>,
        rowId: String,
        txIds: Collection<String>,
    ): List<ChatMessage> = rendered.filter { it.id == rowId || it.txId?.txIdString() in txIds }

    /** A field F4 adds to the stored row, read by name (so this file compiles before F4 exists), or null. */
    fun storedField(
        row: ZchatPreferences.PendingMessageData,
        name: String,
    ): Any? =
        runCatching {
            row.javaClass.getMethod("get" + name.replaceFirstChar { it.uppercase() }).invoke(row)
        }.getOrNull()

    // ── the wallet ────────────────────────────────────────────────────────────────────────────────────

    private fun scanPrefs(
        prefs: PaymentPrefs,
        writes: Writes,
    ): ZchatPreferences =
        Proxy.newProxyInstance(
            ZchatPreferences::class.java.classLoader,
            arrayOf(ZchatPreferences::class.java),
        ) { _, method, args ->
            when (method.name) {
                "getConversationId", "getDisappearingTtl", "getNickname", "clearPendingTxFirstSeen" -> null
                "isE2ERecoveryFloorPending" -> false
                // KI-24 fixup G-KI24-2: the pass reports to the processed-KEX floor (no key offer in these tests).
                "limitKexFloor" -> null
                "getOrPutPendingTxFirstSeenMillis" -> args!![1]
                // C2: the wallet-scope generation a pass compares before it writes (no wallet switch in these tests).
                "walletScopeGeneration" -> 0L
                // A complete, stable pass settles stranded group announce payments; these wallets have no group.
                "getAllGroupIds" -> emptySet<String>()
                else -> {
                    if (method.name == "commitPendingMessage" || method.name == "addPendingMessage") {
                        writes.rows += args!![0] as ZchatPreferences.PendingMessageData
                    }
                    try {
                        method.invoke(prefs.proxy, *(args ?: emptyArray()))
                    } catch (e: InvocationTargetException) {
                        throw e.targetException
                    }
                }
            }
        } as ZchatPreferences

    private fun transactions(wallet: Wallet): List<Transaction> =
        wallet.txs.map { tx ->
            val id = TransactionId.new("%064x".format(tx.number))
            SendTransaction.Success(
                id = id,
                amount = Zatoshi(tx.amount),
                timestamp = tx.blockTime,
                memoCount = 1,
                fee = Zatoshi(FEE),
                transactionOutputs = emptyList(),
                overview = overview(id, tx, wallet),
                recipient = null,
            )
        }

    private fun repository(wallet: Wallet): TransactionRepository =
        Proxy.newProxyInstance(
            TransactionRepository::class.java.classLoader,
            arrayOf(TransactionRepository::class.java),
        ) { p, method, args ->
            fun txOf(i: Int) = wallet.txs.firstOrNull { txId(it.number) == (args!![i] as Transaction).id.txIdString() }
            when (method.name) {
                "getTransactions" -> transactions(wallet)
                "getAllRecipientAddresses" -> txOf(0)?.recipient?.let { listOf(it) } ?: emptyList<String>()
                "getMemos" -> txOf(0)?.memo?.let { listOf(it) } ?: emptyList<String>()
                "hashCode" -> System.identityHashCode(p)
                "equals" -> p === args?.get(0)
                "toString" -> "SendAttemptRepository"
                else -> error("unexpected TransactionRepository.${method.name}")
            }
        } as TransactionRepository

    private fun provider(wallet: Wallet): SynchronizerProvider {
        val synchronizer =
            Proxy.newProxyInstance(
                Synchronizer::class.java.classLoader,
                arrayOf(Synchronizer::class.java),
            ) { p, method, args ->
                when (method.name) {
                    "getFullyScannedHeight" -> MutableStateFlow(BlockHeight.new(wallet.scanned))
                    "getNetworkHeight" -> MutableStateFlow(BlockHeight.new(wallet.network))
                    // The SDK's own read of an account's transactions (suspend: the account, then the continuation).
                    "getTransactions" -> flowOf(wallet.txs.map { overview(TransactionId.new("%064x".format(it.number)), it, wallet) })
                    "getRecipients" ->
                        if (args?.firstOrNull() is TransactionOverview) {
                            val tx = wallet.txs.firstOrNull { txId(it.number) == (args[0] as TransactionOverview).txId.txIdString() }
                            flowOf(*listOfNotNull(tx?.recipient?.let { TransactionRecipient(it, null) }).toTypedArray())
                        } else {
                            // The bulk read (suspend, the continuation only): every transaction's outputs by id.
                            wallet.txs.associate { tx ->
                                TransactionId.new("%064x".format(tx.number)) to
                                    listOfNotNull(tx.recipient?.let { TransactionRecipient(it, null) })
                            }
                        }
                    "hashCode" -> System.identityHashCode(p)
                    "equals" -> p === args?.get(0)
                    "toString" -> "SendAttemptSynchronizer"
                    else -> error("unexpected Synchronizer.${method.name}")
                }
            } as Synchronizer
        return Proxy.newProxyInstance(
            SynchronizerProvider::class.java.classLoader,
            arrayOf(SynchronizerProvider::class.java),
        ) { p, method, args ->
            when (method.name) {
                "getSynchronizer" -> if (args.isNullOrEmpty()) MutableStateFlow<Synchronizer?>(synchronizer) else synchronizer
                "hashCode" -> System.identityHashCode(p)
                "equals" -> p === args?.get(0)
                "toString" -> "SendAttemptSynchronizerProvider"
                else -> error("unexpected SynchronizerProvider.${method.name}")
            }
        } as SynchronizerProvider
    }

    private fun overview(
        id: TransactionId,
        tx: ChainTx,
        wallet: Wallet,
    ): TransactionOverview =
        TransactionOverview(
            id,
            tx.minedHeight?.let { BlockHeight.new(it) },
            tx.expiryHeight?.let { BlockHeight.new(it) },
            null,
            null,
            true,
            // A sent transaction's net value (a magnitude in the SDK model) is what left the wallet: amount and fee.
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
                tx.expiryHeight != null && tx.expiryHeight in 1..wallet.scanned -> TransactionState.Expired
                else -> TransactionState.Pending
            },
            false,
            1,
            Zatoshi(0),
            true,
            Zip318Kind.NOT_CLASSIFIED,
        )

    private const val FEE = 10_000L
}
