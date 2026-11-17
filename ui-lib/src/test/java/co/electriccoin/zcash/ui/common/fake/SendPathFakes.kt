package co.electriccoin.zcash.ui.common.fake

import cash.z.ecc.android.sdk.Broadcaster
import cash.z.ecc.android.sdk.Synchronizer
import cash.z.ecc.android.sdk.model.AccountUuid
import cash.z.ecc.android.sdk.model.BlockHeight
import cash.z.ecc.android.sdk.model.CreatedTransaction
import cash.z.ecc.android.sdk.model.FirstClassByteArray
import cash.z.ecc.android.sdk.model.Pczt
import cash.z.ecc.android.sdk.model.Proposal
import cash.z.ecc.android.sdk.model.TransactionId
import cash.z.ecc.android.sdk.model.TransactionRecipient
import cash.z.ecc.android.sdk.model.TransactionSubmitResult
import cash.z.ecc.android.sdk.model.UnifiedSpendingKey
import co.electriccoin.lightwallet.client.model.LightWalletEndpoint
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asFlow
import java.lang.reflect.Proxy
import java.util.Collections

/**
 * F0 (fix-all 2.13.0, shared test fakes). The SDK's create-then-submit API as the F2 one-submit-path tests see it:
 * [createProposedTransactions] returns the scripted legs, the caller's "stored" callback is recorded through
 * [onStored], and every [submit] is answered per leg — Success by default, or a scripted [TransactionSubmitResult] /
 * throw. [events] keeps the order of all three, which is what the F2 tests pin ("create", then "stored", then one
 * "submit" per leg; nothing submitted when the stored commit fails).
 *
 * Every txid is synthetic (see [leg]); nothing here touches a network or a wallet.
 */
internal class RecordingBroadcaster(
    private val legs: List<CreatedTransaction>,
) : Broadcaster {
    /** The ordered record: `create`, `create_pczt`, `stored:<ids>@<expiry>`, `submit:<txid>`. */
    val events: MutableList<String> = Collections.synchronizedList(mutableListOf())

    private val answers = mutableMapOf<String, suspend (CreatedTransaction) -> TransactionSubmitResult>()

    /** Makes the NEXT create throw [error] (for example a create that stored nothing, or one that stored and threw). */
    var createFailure: Throwable? = null

    /** Answer every submit of [txId] with [answer] (it may throw to model a submit that threw). */
    fun answerSubmit(
        txId: String,
        answer: suspend (CreatedTransaction) -> TransactionSubmitResult,
    ) {
        answers[txId] = answer
    }

    /** The callback a submit path calls once the created ids and expiry are durably recorded (F2's onStored). */
    fun onStored(
        ids: List<String>,
        expiry: BlockHeight?,
    ) {
        events += "stored:${ids.joinToString(",")}@${expiry?.value}"
    }

    /** The txids submitted, in order (a leg submitted twice appears twice). */
    fun submitted(): List<String> = events.filter { it.startsWith("submit:") }.map { it.removePrefix("submit:") }

    override suspend fun createProposedTransactions(
        proposal: Proposal,
        usk: UnifiedSpendingKey,
    ): List<CreatedTransaction> {
        events += "create"
        createFailure?.let {
            createFailure = null
            throw it
        }
        return legs
    }

    override suspend fun createTransactionFromPczt(
        pcztWithProofs: Pczt,
        pcztWithSignatures: Pczt,
    ): List<CreatedTransaction> {
        events += "create_pczt"
        createFailure?.let {
            createFailure = null
            throw it
        }
        return legs
    }

    override suspend fun submit(
        transaction: CreatedTransaction,
        endpoint: LightWalletEndpoint,
    ): TransactionSubmitResult {
        val id = transaction.txIdString()
        events += "submit:$id"
        val answer = answers[id] ?: return TransactionSubmitResult.Success(transaction.txId)
        return answer(transaction)
    }

    companion object {
        /** A synthetic created leg: its txid bytes are all [seed], its raw bytes are [seed] repeated 8 times. */
        fun leg(
            seed: Int,
            expiryHeight: Long,
        ): CreatedTransaction =
            CreatedTransaction(
                FirstClassByteArray(ByteArray(TXID_BYTES) { seed.toByte() }),
                FirstClassByteArray(ByteArray(RAW_BYTES) { seed.toByte() }),
                BlockHeight.new(expiryHeight),
            )

        /** The gRPC-style failure lightwalletd answers with (Zodl's per-leg catch maps a throw to code -1). */
        fun grpcFailure(
            transaction: CreatedTransaction,
            code: Int = -1,
        ): TransactionSubmitResult =
            TransactionSubmitResult.Failure(transaction.txId, true, code, "fake gRPC failure", false)

        private const val TXID_BYTES = 32
        private const val RAW_BYTES = 8
    }
}

/**
 * F0 (fix-all 2.13.0, shared test fakes). A [Synchronizer] that answers only what the TEX checker, the send-attempt
 * resolver and the D1 driver read — [fullyScannedHeight], [networkHeight], the recipients of a transaction
 * ([recipients]), [fetchUtxosByAddress] and the [broadcaster] — and throws on anything else, so a test that
 * reaches an unscripted call fails loudly instead of reading a default.
 *
 * Heights are settable ([fullyScannedHeight] / [networkHeight] are plain StateFlows), so a test can walk a rewind
 * between two reads, or two passes of a scan.
 */
internal class ScriptedSynchronizer(
    val broadcaster: Broadcaster? = null,
    fullyScanned: Long = 0,
    network: Long = 0,
) {
    val fullyScannedHeight = MutableStateFlow(BlockHeight.new(fullyScanned))
    val networkHeight = MutableStateFlow(BlockHeight.new(network))

    /** What getRecipients answers. The overview overload matches by txIdString(), so byte order is the SDK's own. */
    val recipients: MutableMap<TransactionId, List<TransactionRecipient>> = mutableMapOf()

    /** fetchUtxosByAddress(account, address) answers this (true = UTXOs found); every call is recorded in [utxoFetches]. */
    var utxoAnswer: (AccountUuid, String) -> Boolean = { _, _ -> false }

    /** The addresses fetchUtxosByAddress was asked for, in order. Tests must never print them (they can be real). */
    val utxoFetches: MutableList<String> = Collections.synchronizedList(mutableListOf())

    /** The names of the calls answered, in order. */
    val calls: MutableList<String> = Collections.synchronizedList(mutableListOf())

    val synchronizer: Synchronizer =
        Proxy.newProxyInstance(
            Synchronizer::class.java.classLoader,
            arrayOf(Synchronizer::class.java),
        ) { proxy, method, args ->
            when (method.name) {
                "toString" -> "ScriptedSynchronizer"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args!![0]
                else -> {
                    calls += method.name
                    answer(method.name, args.orEmpty())
                }
            }
        } as Synchronizer

    private fun answer(
        name: String,
        args: Array<out Any?>,
    ): Any? =
        when (name) {
            "getFullyScannedHeight" -> fullyScannedHeight
            "getNetworkHeight" -> networkHeight
            "getBroadcaster" -> broadcaster ?: throw UnsupportedOperationException("no broadcaster scripted")
            // Two overloads: getRecipients(TransactionOverview): Flow and the suspend getRecipients(): Map.
            "getRecipients" ->
                if (args.size == 1 && args[0] !is kotlin.coroutines.Continuation<*>) {
                    val txId = args[0]!!.javaClass.getMethod("getTxId").invoke(args[0]) as TransactionId
                    recipients.entries
                        .firstOrNull { it.key.txIdString() == txId.txIdString() }
                        ?.value
                        .orEmpty()
                        .asFlow()
                } else {
                    recipients.toMap()
                }
            "fetchUtxosByAddress" -> {
                val account = args[0] as AccountUuid
                val address = args[1] as String
                utxoFetches += address
                utxoAnswer(account, address)
            }
            else -> throw UnsupportedOperationException("unexpected Synchronizer call: $name")
        }
}
