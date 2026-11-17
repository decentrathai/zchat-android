package co.electriccoin.zcash.ui.common.repository

import cash.z.ecc.android.sdk.Synchronizer
import cash.z.ecc.android.sdk.model.BlockHeight
import cash.z.ecc.android.sdk.model.TransactionId
import cash.z.ecc.android.sdk.model.TransactionOverview
import cash.z.ecc.android.sdk.model.TransactionState
import cash.z.ecc.android.sdk.model.Zatoshi
import cash.z.ecc.android.sdk.model.Zip318Kind
import co.electriccoin.zcash.ui.common.model.SynchronizerError
import co.electriccoin.zcash.ui.common.provider.SynchronizerProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import java.lang.reflect.Proxy
import java.util.concurrent.atomic.AtomicInteger

/**
 * ZC-946-3: the SDK's memo read as `TransactionRepositoryImpl.getMemos` meets it, for tests of the memo cache.
 *
 * [MemoSdk] is a synchronizer whose only used method, `getMemos(overview)`, emits the raw per-output strings the test
 * scripted for that transaction id (SDK 3.0.0 emits one string per output: "" for a transparent output, an output with
 * no memo, and an output whose memo could not be read, "Memo not available"), counts every call, and can throw instead.
 * [provider] hands out the synchronizer the test currently names. Every id is synthetic.
 */
internal object MemoReadFixture {
    class MemoSdk {
        val reads = AtomicInteger()

        /** txid hex -> what the next read emits (raw, before the repository drops empty strings). */
        val raw = HashMap<String, List<String>>()

        /** txid hex -> what the next read throws instead of answering. */
        val throws = HashMap<String, Throwable>()

        val synchronizer: Synchronizer =
            Proxy.newProxyInstance(Synchronizer::class.java.classLoader, arrayOf(Synchronizer::class.java)) { p, method, args ->
                when (method.name) {
                    "getMemos" -> memos(args!![0] as TransactionOverview)
                    "hashCode" -> System.identityHashCode(p)
                    "equals" -> p === args?.get(0)
                    "toString" -> "MemoSdk"
                    else -> throw UnsupportedOperationException("unexpected Synchronizer.${method.name}")
                }
            } as Synchronizer

        private fun memos(overview: TransactionOverview): Flow<String> {
            reads.incrementAndGet()
            val key = overview.txId.txIdString()
            return flow {
                throws[key]?.let { throw it }
                raw[key].orEmpty().forEach { emit(it) }
            }
        }
    }

    /** A provider whose synchronizer is whichever [current] holds when it is asked. */
    class SwitchingProvider(
        var current: Synchronizer,
    ) : SynchronizerProvider {
        override val error: StateFlow<SynchronizerError?> = MutableStateFlow(null)
        override val synchronizer: StateFlow<Synchronizer?> get() = MutableStateFlow(current)

        override suspend fun getSynchronizer(): Synchronizer = current

        override fun resetSynchronizer() = Unit
    }

    fun txId(number: Int): TransactionId = TransactionId.new("%064x".format(number))

    /** An overview as the SDK lists it; [memoCount] is the SDK's count of outputs with a memo. */
    fun overview(
        number: Int,
        memoCount: Int,
        minedHeight: Long? = null,
        expiryHeight: Long = EXPIRY,
    ): TransactionOverview =
        TransactionOverview(
            txId(number),
            minedHeight?.let { BlockHeight.new(it) },
            BlockHeight.new(expiryHeight),
            null,
            null,
            true,
            Zatoshi(AMOUNT + FEE),
            Zatoshi(AMOUNT + FEE),
            Zatoshi(0),
            Zatoshi(FEE),
            false,
            0,
            2,
            memoCount,
            null,
            if (minedHeight != null) TransactionState.Confirmed else TransactionState.Pending,
            false,
            1,
            Zatoshi(0),
            true,
            Zip318Kind.NOT_CLASSIFIED,
        )

    fun send(overview: TransactionOverview): Transaction =
        SendTransaction.Pending(overview.txId, Zatoshi(AMOUNT), null, 1, Zatoshi(FEE), emptyList(), overview, null)

    private const val AMOUNT = 1_000L
    private const val FEE = 10_000L
    private const val EXPIRY = 4_000L
}
