package co.electriccoin.zcash.ui.screen.chat.viewmodel

import cash.z.ecc.android.sdk.Synchronizer
import cash.z.ecc.android.sdk.model.BlockHeight
import cash.z.ecc.android.sdk.model.TransactionId
import cash.z.ecc.android.sdk.model.TransactionOverview
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
import kotlinx.coroutines.flow.MutableStateFlow
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.time.Instant

/**
 * Runs the REAL chat-history conversion (convertToConversations), including its pending-store reconciliation,
 * against a synthetic wallet of SENT transactions. Shared by the P2a follow-up regressions; the older copy
 * inside RetryNeverPaysTwiceTest is left as it is.
 *
 * Every transaction is outgoing to its recipients and carries plaintext memos, so no key recovery or
 * decryption side effect runs. Transaction [SentTx.number] n gets the ID [txId] (n).
 */
internal object PendingReconcileHarness {
    const val AMOUNT = 50_000L
    const val SCANNED = 3_000_200L

    class SentTx(
        val number: Int,
        val recipient: String,
        /** Null: the SDK returned no memo for it (not yet read), which is not a read failure. */
        val memo: String?,
        val minedHeight: Long?,
        val blockTime: Instant? = minedHeight?.let { Instant.now() },
        val expiryHeight: Long? = null,
        /** The transaction's position in its block (TransactionOverview.index); null as before when not set. */
        val txIndex: Long? = null,
        /** Round 8 P2: the SDK reports it as expired unmined (SendTransaction.Failed). */
        val expired: Boolean = false,
        /** Round 8 P2: the value the transaction moved (Transaction.amount); [AMOUNT] as before when not set. */
        val amount: Long = AMOUNT,
    )

    fun txId(n: Int): String = TransactionId.new("%064x".format(n)).txIdString()

    /**
     * Reconciles [vm]'s in-memory pending rows against [txs] and returns every row the conversion rendered.
     * Durable deletions land in [prefs] exactly as in the app.
     */
    suspend fun reconcile(
        vm: ChatViewModel,
        prefs: PaymentPrefs,
        txs: List<SentTx>,
    ): List<ChatMessage> = reconcileConversations(vm, prefs, txs).flatMap { it.messages }

    /**
     * The same pass as [reconcile], returning the conversations themselves — so a test can also read what the
     * CHAT LIST shows (Conversation.lastMessage) next to what the thread shows (Conversation.messages).
     */
    suspend fun reconcileConversations(
        vm: ChatViewModel,
        prefs: PaymentPrefs,
        txs: List<SentTx>,
        // The paid-request set the conversations combine hands this pass (C3, B2 review item 8).
        paidReqIds: Set<String> = emptySet(),
        // Round 8 P2: whether the history scan is complete (the pass can then be stable); false as before.
        historyScanComplete: Boolean = false,
    ): List<Conversation> {
        val scanPrefs =
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
                    else -> method.invoke(prefs.proxy, *(args ?: emptyArray()))
                }
            } as ZchatPreferences
        PaymentHarness.setField(vm, "zchatPreferences", scanPrefs)
        val contactBook =
            Proxy.newProxyInstance(ContactBook::class.java.classLoader, arrayOf(ContactBook::class.java)) { _, method, _ ->
                when (method.name) {
                    "getContact" -> null
                    else -> error("unexpected ContactBook.${method.name}")
                }
            }
        PaymentHarness.setField(vm, "contactBook", contactBook)
        val transactions = install(vm, txs)
        val method =
            ChatViewModel::class.java.declaredMethods
                .single { it.name == "convertToConversations" && it.parameterCount == 7 }
                .apply { isAccessible = true }
        val conversations =
            kotlin.coroutines.intrinsics
                .suspendCoroutineUninterceptedOrReturn<List<Conversation>> { continuation ->
                    try {
                        method.invoke(
                            vm,
                            transactions,
                            PaymentHarness.SELF,
                            emptySet<String>(),
                            PaymentHarness.pendingMessages(vm),
                            paidReqIds,
                            historyScanComplete,
                            continuation,
                        )
                    } catch (e: InvocationTargetException) {
                        throw e.targetException
                    }
                }
        return conversations
    }

    private fun install(
        vm: ChatViewModel,
        txs: List<SentTx>,
    ): List<Transaction> {
        val byTxId = txs.associateBy { txId(it.number) }
        val transactions: List<Transaction> =
            txs.map { tx ->
                val id = TransactionId.new("%064x".format(tx.number))
                if (tx.expired) {
                    SendTransaction.Failed(
                        id = id,
                        amount = Zatoshi(tx.amount),
                        timestamp = tx.blockTime,
                        memoCount = 1,
                        fee = Zatoshi(10_000),
                        transactionOutputs = emptyList(),
                        overview = overview(id, tx),
                        recipient = null,
                    )
                } else {
                    SendTransaction.Success(
                        id = id,
                        amount = Zatoshi(tx.amount),
                        timestamp = tx.blockTime,
                        memoCount = 1,
                        fee = Zatoshi(10_000),
                        transactionOutputs = emptyList(),
                        overview = overview(id, tx),
                        recipient = null,
                    )
                }
            }
        val repository =
            Proxy.newProxyInstance(
                TransactionRepository::class.java.classLoader,
                arrayOf(TransactionRepository::class.java),
            ) { p, method, args ->
                fun txOf(i: Int) = byTxId[(args!![i] as Transaction).id.txIdString()]
                when (method.name) {
                    "getTransactions" -> transactions
                    "getAllRecipientAddresses" -> txOf(0)?.let { listOf(it.recipient) } ?: emptyList<String>()
                    "getMemos" -> txOf(0)?.memo?.let { listOf(it) } ?: emptyList<String>()
                    "hashCode" -> System.identityHashCode(p)
                    "equals" -> p === args?.get(0)
                    "toString" -> "PendingReconcileRepository"
                    else -> error("unexpected TransactionRepository.${method.name}")
                }
            } as TransactionRepository
        val synchronizer =
            Proxy.newProxyInstance(
                Synchronizer::class.java.classLoader,
                arrayOf(Synchronizer::class.java),
            ) { p, method, args ->
                when (method.name) {
                    "getFullyScannedHeight", "getNetworkHeight" -> MutableStateFlow(BlockHeight.new(SCANNED))
                    "hashCode" -> System.identityHashCode(p)
                    "equals" -> p === args?.get(0)
                    "toString" -> "PendingReconcileSynchronizer"
                    else -> error("unexpected Synchronizer.${method.name}")
                }
            } as Synchronizer
        val provider =
            Proxy.newProxyInstance(
                SynchronizerProvider::class.java.classLoader,
                arrayOf(SynchronizerProvider::class.java),
            ) { p, method, args ->
                when (method.name) {
                    "getSynchronizer" -> if (args.isNullOrEmpty()) MutableStateFlow<Synchronizer?>(synchronizer) else synchronizer
                    "hashCode" -> System.identityHashCode(p)
                    "equals" -> p === args?.get(0)
                    "toString" -> "PendingReconcileSynchronizerProvider"
                    else -> error("unexpected SynchronizerProvider.${method.name}")
                }
            } as SynchronizerProvider
        PaymentHarness.setField(vm, "transactionRepository", repository)
        PaymentHarness.setField(vm, "synchronizerProvider", provider)
        return transactions
    }

    private fun overview(
        id: TransactionId,
        tx: SentTx,
    ): TransactionOverview =
        TransactionOverview(
            id,
            tx.minedHeight?.let { BlockHeight.new(it) },
            tx.expiryHeight?.let { BlockHeight.new(it) },
            tx.txIndex,
            null,
            true,
            Zatoshi(AMOUNT + 10_000),
            Zatoshi(AMOUNT + 10_000),
            Zatoshi(0),
            Zatoshi(10_000),
            false,
            0,
            1,
            1,
            tx.blockTime?.epochSecond,
            when {
                tx.minedHeight != null -> TransactionState.Confirmed
                tx.expired -> TransactionState.Expired
                else -> TransactionState.Pending
            },
            false,
            1,
            Zatoshi(0),
            true,
            Zip318Kind.NOT_CLASSIFIED,
        )
}
