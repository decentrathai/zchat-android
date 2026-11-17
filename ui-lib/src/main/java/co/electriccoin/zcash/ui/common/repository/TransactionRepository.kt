package co.electriccoin.zcash.ui.common.repository

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import androidx.core.database.getBlobOrNull
import androidx.core.database.getStringOrNull
import cash.z.ecc.android.sdk.Synchronizer
import cash.z.ecc.android.sdk.model.BlockHeight
import cash.z.ecc.android.sdk.model.TransactionId
import cash.z.ecc.android.sdk.model.TransactionOutput
import cash.z.ecc.android.sdk.model.TransactionOverview
import cash.z.ecc.android.sdk.model.TransactionState
import cash.z.ecc.android.sdk.model.TransactionState.Confirmed
import cash.z.ecc.android.sdk.model.TransactionState.Expired
import cash.z.ecc.android.sdk.model.TransactionState.Pending
import cash.z.ecc.android.sdk.model.WalletAddress
import cash.z.ecc.android.sdk.model.Zatoshi
import cash.z.ecc.android.sdk.model.ZcashNetwork
import cash.z.ecc.android.sdk.type.AddressType
import cash.z.ecc.sdk.type.fromResources
import co.electriccoin.zcash.ui.common.datasource.AccountDataSource
import co.electriccoin.zcash.ui.common.provider.SynchronizerProvider
import co.electriccoin.zcash.ui.screen.chat.model.PaymentOutput
import co.electriccoin.zcash.ui.screen.chat.model.ZMSGConstants
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.onEmpty
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.withContext
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

interface TransactionRepository {
    val transactions: Flow<List<Transaction>?>

    suspend fun getMemos(transaction: Transaction): List<String>

    fun observeTransaction(txId: String): Flow<Transaction?>

    fun observeTransactionsByMemo(memo: String): Flow<List<TransactionId>?>

    suspend fun getTransactions(): List<Transaction>

    suspend fun getAllRecipientAddresses(transaction: Transaction): List<String>

    /** ZC-1273: the value of each output of [transaction], from the wallet's note tables. Null when they cannot be read. */
    suspend fun getOutputValues(transaction: Transaction): List<PaymentOutput>? = null

    /** ZC-946-3: forgets the memos held in memory (Reset). A repository that holds none has nothing to forget. */
    fun forgetMemos() = Unit
}

class TransactionRepositoryImpl(
    accountDataSource: AccountDataSource,
    private val synchronizerProvider: SynchronizerProvider,
    // ZC-1273: for the wallet database's path. Always given by Koin; optional so tests that only read memos need none.
    private val context: Context? = null,
) : TransactionRepository {
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private var minedOutputsWallet: Synchronizer? = null
    private val minedOutputs = ConcurrentHashMap<String, List<PaymentOutput>>()

    @OptIn(ExperimentalCoroutinesApi::class)
    override val transactions: Flow<List<Transaction>?> =
        accountDataSource
            .selectedAccount
            .map { it?.sdkAccount?.accountUuid }
            .distinctUntilChanged()
            .flatMapLatest { uuid ->
                if (uuid == null) {
                    flowOf(null)
                } else {
                    synchronizerProvider
                        .synchronizer
                        .flatMapLatest { synchronizer ->
                            if (synchronizer == null) {
                                // ZC-946-3 (review 4, nit 3): the wallet is gone; so are its decrypted memos.
                                memoCache.clear()
                                flowOf(null)
                            } else {
                                val normalizedTransactions =
                                    synchronizer
                                        .getTransactions(uuid)
                                        .mapLatest { transactions ->
                                            transactions
                                                .map {
                                                    if (it.isSentTransaction) {
                                                        it.copy(
                                                            transactionState =
                                                                createTransactionState(minedHeight = it.minedHeight)
                                                                    ?: it.transactionState
                                                        )
                                                    } else {
                                                        it
                                                    }
                                                }
                                        }.distinctUntilChanged()

                                normalizedTransactions
                                    .mapLatest { transactions ->
                                        val sentCount = transactions.count { it.isSentTransaction }
                                        val rcvCount = transactions.size - sentCount
                                        Log.d("ZCHAT_REPO", "SDK emitted ${transactions.size} txs (sent=$sentCount rcv=$rcvCount) heights=${transactions.mapNotNull { it.minedHeight?.value }.sorted().takeLast(3)}")
                                        // Hoist a single `now`: evaluating Instant.now() inside the
                                        // comparator re-reads the clock on every comparison, so with
                                        // several null-timestamp (pending) txs the ordering is not a
                                        // consistent total order and TimSort can throw "Comparison
                                        // method violates its general contract".
                                        val now = Instant.now()
                                        transactions
                                            .map { transaction ->
                                                createTransaction(transaction, synchronizer)
                                            }.sortedByDescending { transaction ->
                                                transaction.timestamp ?: now
                                            }
                                    }
                            }
                        }.onStart { emit(null) }
                }
            }.stateIn(
                scope = scope,
                started = SharingStarted.Lazily,
                initialValue = null
            )

    private suspend fun createTransaction(transaction: TransactionOverview, synchronizer: Synchronizer): Transaction =
        when (transaction.transactionState) {
            Expired ->
                when {
                    transaction.isShielding ->
                        ShieldTransaction.Failed(
                            timestamp = createTimestamp(transaction),
                            transactionOutputs = synchronizer.getTransactionOutputs(transaction),
                            amount = transaction.totalSpent,
                            id = transaction.txId,
                            memoCount = transaction.memoCount,
                            // A shielding tx's fee is feePaid (the cost), NOT netValue (the amount moved
                            // to the shielded pool) — this is shown to the user as the fee, and must match
                            // the SendTransaction blocks which correctly use feePaid.
                            fee = transaction.feePaid,
                            overview = transaction,
                            recipient = null
                        )

                    transaction.isSentTransaction ->
                        SendTransaction.Failed(
                            timestamp = createTimestamp(transaction),
                            transactionOutputs = synchronizer.getTransactionOutputs(transaction),
                            amount = transaction.netValue,
                            id = transaction.txId,
                            memoCount = transaction.memoCount,
                            fee = transaction.feePaid,
                            overview = transaction,
                            recipient = getRecipient(transaction)
                        )

                    else ->
                        ReceiveTransaction.Failed(
                            timestamp = createTimestamp(transaction),
                            transactionOutputs = synchronizer.getTransactionOutputs(transaction),
                            amount = transaction.netValue,
                            id = transaction.txId,
                            memoCount = transaction.memoCount,
                            overview = transaction,
                            recipient = null
                        )
                }

            Confirmed ->
                when {
                    transaction.isShielding ->
                        ShieldTransaction.Success(
                            timestamp = createTimestamp(transaction),
                            transactionOutputs = synchronizer.getTransactionOutputs(transaction),
                            amount = transaction.totalSpent,
                            id = transaction.txId,
                            memoCount = transaction.memoCount,
                            // A shielding tx's fee is feePaid (the cost), NOT netValue (the amount moved
                            // to the shielded pool) — this is shown to the user as the fee, and must match
                            // the SendTransaction blocks which correctly use feePaid.
                            fee = transaction.feePaid,
                            overview = transaction,
                            recipient = null
                        )

                    transaction.isSentTransaction ->
                        SendTransaction.Success(
                            timestamp = createTimestamp(transaction),
                            transactionOutputs = synchronizer.getTransactionOutputs(transaction),
                            amount = transaction.netValue,
                            id = transaction.txId,
                            memoCount = transaction.memoCount,
                            fee = transaction.feePaid,
                            overview = transaction,
                            recipient = getRecipient(transaction)
                        )

                    else ->
                        ReceiveTransaction.Success(
                            timestamp = createTimestamp(transaction),
                            transactionOutputs = synchronizer.getTransactionOutputs(transaction),
                            amount = transaction.netValue,
                            id = transaction.txId,
                            memoCount = transaction.memoCount,
                            overview = transaction,
                            recipient = null
                        )
                }

            Pending ->
                when {
                    transaction.isShielding ->
                        ShieldTransaction.Pending(
                            timestamp = createTimestamp(transaction),
                            transactionOutputs = synchronizer.getTransactionOutputs(transaction),
                            amount = transaction.totalSpent,
                            id = transaction.txId,
                            memoCount = transaction.memoCount,
                            // A shielding tx's fee is feePaid (the cost), NOT netValue (the amount moved
                            // to the shielded pool) — this is shown to the user as the fee, and must match
                            // the SendTransaction blocks which correctly use feePaid.
                            fee = transaction.feePaid,
                            overview = transaction,
                            recipient = null
                        )

                    transaction.isSentTransaction ->
                        SendTransaction.Pending(
                            timestamp = createTimestamp(transaction),
                            transactionOutputs = synchronizer.getTransactionOutputs(transaction),
                            amount = transaction.netValue,
                            id = transaction.txId,
                            memoCount = transaction.memoCount,
                            fee = transaction.feePaid,
                            overview = transaction,
                            recipient = getRecipient(transaction)
                        )

                    else ->
                        ReceiveTransaction.Pending(
                            timestamp = createTimestamp(transaction),
                            transactionOutputs = synchronizer.getTransactionOutputs(transaction),
                            amount = transaction.netValue,
                            id = transaction.txId,
                            memoCount = transaction.memoCount,
                            overview = transaction,
                            recipient = null
                        )
                }
        }

    private fun createTransactionState(minedHeight: BlockHeight?): TransactionState? {
        return if (minedHeight != null) return Confirmed else null
    }

    private fun createTimestamp(overview: TransactionOverview): Instant? =
        overview.blockTimeEpochSeconds?.let { Instant.ofEpochSecond(it) }

    // ZC-946 batch 3: one per repository (a Koin single), so per process. See MemoCache for what it keeps.
    private val memoCache = MemoCache()

    override fun forgetMemos() = memoCache.clear()

    override suspend fun getMemos(transaction: Transaction): List<String> =
        synchronizerProvider.getSynchronizer().let { synchronizer ->
            memoCache.read(synchronizer, transaction.overview) {
                withContext(Dispatchers.IO) {
                    synchronizer
                        .getMemos(transaction.overview)
                        .mapNotNull { memo -> memo.takeIf { it.isNotEmpty() } }
                        .toList()
                }
            }
        }

    override fun observeTransaction(txId: String): Flow<Transaction?> =
        transactions
            .map { transactions ->
                transactions?.find { it.id.txIdString() == txId }
            }

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun observeTransactionsByMemo(memo: String): Flow<List<TransactionId>?> =
        synchronizerProvider
            .synchronizer
            .flatMapLatest { synchronizer ->
                synchronizer?.getTransactionsByMemoSubstring(memo)?.onEmpty { emit(listOf()) } ?: flowOf(null)
            }.distinctUntilChanged()

    override suspend fun getTransactions(): List<Transaction> = transactions.filterNotNull().first()

    /**
     * ZC-1273: the SDK reports per-output values only inside its own TxOutputsView ([TransactionOutput] holds the pool alone,
     * and netValue is a total with the fee and the platform fee in it), so its view is read here, read-only. The txid is
     * written as a blob literal because rawQuery binds strings only; it is hex of the transaction's own bytes. A mined
     * transaction's outputs do not change, so they are kept for the next history pass, for as long as the wallet stays.
     */
    @Suppress("TooGenericExceptionCaught")
    override suspend fun getOutputValues(transaction: Transaction): List<PaymentOutput>? =
        withContext(Dispatchers.IO) {
            if (context == null) return@withContext null
            try {
                val synchronizer = synchronizerProvider.getSynchronizer()
                val cache = minedOutputsOf(synchronizer)
                cache[transaction.id.txIdString()]?.let { return@withContext it }
                val path = synchronizer.getExistingDataDbFilePath(context, ZcashNetwork.fromResources(context))
                val txid =
                    transaction.overview.txId.value.byteArray
                        .joinToString("") { "%02x".format(it) }
                val dataDb = SQLiteDatabase.openDatabase(path, null, SQLiteDatabase.OPEN_READONLY)
                dataDb
                    .use { db ->
                        db
                            .rawQuery(
                                "SELECT to_address, to_account_uuid IS NOT NULL, value, is_change, memo FROM v_tx_outputs " +
                                    "WHERE txid = x'$txid'",
                                null
                            ).use { cursor ->
                                val memoColumn = cursor.getColumnIndex("memo")
                                buildList {
                                    while (cursor.moveToNext()) {
                                        add(
                                            PaymentOutput(
                                                address = cursor.getStringOrNull(0),
                                                toOwnAccount = cursor.getInt(1) != 0,
                                                valueZatoshi = cursor.getLong(2),
                                                isChange = cursor.getInt(3) != 0,
                                                memo = if (memoColumn < 0) null else memoText(cursor.getBlobOrNull(memoColumn)),
                                            )
                                        )
                                    }
                                }
                            }
                    }.also { outputs ->
                        // ZC-1339: an empty read of a mined transaction is not final (its outputs are not stored yet); never kept.
                        if (transaction.overview.minedHeight != null && outputs.isNotEmpty()) cache[transaction.id.txIdString()] = outputs
                    }
            } catch (e: Exception) {
                Log.w("ZCHAT_RECIPIENTS", "output values unreadable: ${e.javaClass.simpleName}")
                null
            }
        }

    /** ZC-1376: a ZIP 302 text memo (first byte up to 0xF4, zero padded) as text; an empty or binary memo is null. */
    private fun memoText(bytes: ByteArray?): String? {
        if (bytes == null || bytes.isEmpty() || (bytes[0].toInt() and 0xFF) > MAX_TEXT_MEMO_LEAD_BYTE) return null
        val end = bytes.indexOfLast { it != 0.toByte() } + 1
        return bytes.copyOf(end).toString(Charsets.UTF_8).ifEmpty { null }
    }

    @Synchronized
    private fun minedOutputsOf(synchronizer: Synchronizer): MutableMap<String, List<PaymentOutput>> {
        if (minedOutputsWallet !== synchronizer) {
            minedOutputsWallet = synchronizer
            minedOutputs.clear()
        }
        return minedOutputs
    }

    override suspend fun getAllRecipientAddresses(transaction: Transaction): List<String> {
        return withContext(Dispatchers.IO) {
            val txId = transaction.id.txIdString()
            val result = mutableListOf<String>()
            synchronizerProvider
                .getSynchronizer()
                .getRecipients(transaction.overview)
                .collect { recipient ->
                    // R2-5 (b): SDK 3.0.0 lists every output, including the change back to this wallet
                    // (accountUuid set). Only outputs that leave the wallet can name the peer.
                    if (recipient.accountUuid == null) recipient.addressValue?.let { result.add(it) }
                }
            Log.d("ZCHAT_RECIPIENTS", "getAllRecipients($txId): ${result.size} recipients [${result.joinToString { it.take(12) + "..." }}]")
            result.toList()
        }
    }

    private suspend fun getRecipient(overview: TransactionOverview): WalletAddress? {
        val txId = overview.txId.txIdString()
        // R2-5 (b): SDK 3.0.0 lists EVERY output of the transaction, in any order: the payee, the ZCHAT platform
        // fee (current or an earlier address) and the change back to this wallet (accountUuid set, with or without
        // an address). The recipient is the first output that leaves the wallet and is not a platform fee; else a
        // platform fee (a payment to that address itself); else an output to this wallet (a self-send, an
        // ephemeral-address unlock), as before.
        val outputs =
            synchronizerProvider
                .getSynchronizer()
                .getRecipients(overview)
                .toList()
                .filter { it.addressValue != null }
        val external = outputs.filter { it.accountUuid == null }
        val address =
            (
                external.firstOrNull { it.addressValue?.let(ZMSGConstants::isPlatformFeeAddress) == false }
                    ?: external.firstOrNull()
                    ?: outputs.firstOrNull()
            )?.addressValue
        if (address == null) {
            Log.d("ZCHAT_RECIPIENTS", "getRecipient($txId): no output with an address")
            return null
        }
        Log.d("ZCHAT_RECIPIENTS", "getRecipient($txId): picked ${address.take(12)}...")

        return when (synchronizerProvider.getSynchronizer().validateAddress(address)) {
            AddressType.Shielded -> WalletAddress.Sapling.new(address)
            AddressType.Tex -> WalletAddress.Tex.new(address)
            AddressType.Transparent -> WalletAddress.Transparent.new(address)
            AddressType.Unified -> WalletAddress.Unified.new(address)
            else -> {
                Log.w("ZCHAT_RECIPIENTS", "getRecipient($txId): unknown address type for ${address.take(12)}...")
                null
            }
        }
    }
}

sealed interface Transaction {
    val id: TransactionId
    val amount: Zatoshi
    val memoCount: Int
    val timestamp: Instant?
    val transactionOutputs: List<TransactionOutput>
    val overview: TransactionOverview
    val fee: Zatoshi?
    val recipient: WalletAddress?
}

sealed interface SendTransaction : Transaction {
    data class Success(
        override val id: TransactionId,
        override val amount: Zatoshi,
        override val timestamp: Instant?,
        override val memoCount: Int,
        override val fee: Zatoshi?,
        override val transactionOutputs: List<TransactionOutput>,
        override val overview: TransactionOverview,
        override val recipient: WalletAddress?,
    ) : SendTransaction

    data class Pending(
        override val id: TransactionId,
        override val amount: Zatoshi,
        override val timestamp: Instant?,
        override val memoCount: Int,
        override val fee: Zatoshi?,
        override val transactionOutputs: List<TransactionOutput>,
        override val overview: TransactionOverview,
        override val recipient: WalletAddress?,
    ) : SendTransaction

    data class Failed(
        override val id: TransactionId,
        override val amount: Zatoshi,
        override val timestamp: Instant?,
        override val memoCount: Int,
        override val fee: Zatoshi?,
        override val transactionOutputs: List<TransactionOutput>,
        override val overview: TransactionOverview,
        override val recipient: WalletAddress?,
    ) : SendTransaction
}

sealed interface ReceiveTransaction : Transaction {
    override val fee: Zatoshi?
        get() = null

    data class Success(
        override val id: TransactionId,
        override val amount: Zatoshi,
        override val timestamp: Instant?,
        override val memoCount: Int,
        override val transactionOutputs: List<TransactionOutput>,
        override val overview: TransactionOverview,
        override val recipient: WalletAddress?,
    ) : ReceiveTransaction

    data class Pending(
        override val id: TransactionId,
        override val amount: Zatoshi,
        override val timestamp: Instant?,
        override val memoCount: Int,
        override val transactionOutputs: List<TransactionOutput>,
        override val overview: TransactionOverview,
        override val recipient: WalletAddress?,
    ) : ReceiveTransaction

    data class Failed(
        override val id: TransactionId,
        override val amount: Zatoshi,
        override val timestamp: Instant?,
        override val memoCount: Int,
        override val transactionOutputs: List<TransactionOutput>,
        override val overview: TransactionOverview,
        override val recipient: WalletAddress?,
    ) : ReceiveTransaction
}

sealed interface ShieldTransaction : Transaction {
    data class Success(
        override val id: TransactionId,
        override val amount: Zatoshi,
        override val timestamp: Instant?,
        override val memoCount: Int,
        override val fee: Zatoshi?,
        override val transactionOutputs: List<TransactionOutput>,
        override val overview: TransactionOverview,
        override val recipient: WalletAddress?,
    ) : ShieldTransaction

    data class Pending(
        override val id: TransactionId,
        override val amount: Zatoshi,
        override val timestamp: Instant?,
        override val memoCount: Int,
        override val fee: Zatoshi?,
        override val transactionOutputs: List<TransactionOutput>,
        override val overview: TransactionOverview,
        override val recipient: WalletAddress?,
    ) : ShieldTransaction

    data class Failed(
        override val id: TransactionId,
        override val amount: Zatoshi,
        override val memoCount: Int,
        override val timestamp: Instant?,
        override val transactionOutputs: List<TransactionOutput>,
        override val fee: Zatoshi?,
        override val overview: TransactionOverview,
        override val recipient: WalletAddress?,
    ) : ShieldTransaction
}

val Transaction.isPending: Boolean
    get() = this is SendTransaction.Pending || this is ShieldTransaction.Pending || this is ReceiveTransaction.Pending

/** ZIP 302: a memo whose first byte is above this is empty (0xF6) or not text. */
private const val MAX_TEXT_MEMO_LEAD_BYTE = 0xF4
