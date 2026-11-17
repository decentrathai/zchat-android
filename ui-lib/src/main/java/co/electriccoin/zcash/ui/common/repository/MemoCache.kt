package co.electriccoin.zcash.ui.common.repository

import android.util.Log
import cash.z.ecc.android.sdk.model.TransactionOverview
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * ZC-946 batch 3 (PERF-SPEC revision 1 section 2; review of 14 Oct, findings 1 and 2; register ZC-958): the memos the
 * SDK answered for a transaction, kept in memory so the history pass, which reads every transaction's memos on every
 * pass, asks the SDK only for what changed. It answers exactly what the SDK answered and decides nothing.
 *
 * - Key: the whole [TransactionOverview] (a data class: txid, mined height, expiry, state, memo count, ...). Any change
 *   the SDK makes to it (mined, reorged, enhanced: enhancement fills the notes' memos, which the memo count is counted
 *   from) is a miss.
 * - Owner: the synchronizer instance (compared by reference) whose answers are held. Another instance (a wallet
 *   change, Reset, Destroy) empties the map before it is asked anything; so do [clear] (Reset) and the synchronizer
 *   going away (TransactionRepositoryImpl). Memory only: nothing on disk.
 * - Kept only when complete: the non-empty memos read are exactly as many as the overview's memo count (the SDK's count
 *   of outputs with a memo). SDK 3.0.0 emits one string per output and hands an output whose memo could not be read
 *   ("Memo not available") to us as an empty string, the same as a transparent output or one without a memo, so an
 *   answer with fewer memos (empty or partial) is never kept and is read again next pass (ZC-958). The raw count of
 *   strings is not the measure: it counts change and transparent outputs too, so it equals the memo count only when
 *   every output carries a memo.
 * - A throw and a cancellation of the read are never kept and reach the caller unchanged; a cancelled caller gets no
 *   answer from the map either ([read] checks before it answers), so a pass cut short counts its later reads as failed,
 *   as it does without the cache (KR-3, the history latch).
 * - At most [maxEntries] entries; the least recently used goes first.
 */
internal class MemoCache(
    private val maxEntries: Int = MAX_ENTRIES,
) {
    private val entries =
        object : LinkedHashMap<TransactionOverview, List<String>>(INITIAL_CAPACITY, LOAD_FACTOR, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<TransactionOverview, List<String>>?): Boolean =
                size > maxEntries
        }
    private var owner: Any? = null
    private var hits = 0L
    private var misses = 0L
    private var readMs = 0L

    /**
     * The memos of [overview] as [synchronizer] answers them: from the map when it holds a complete answer of this
     * instance, else from [load] (the SDK read, without its empty strings).
     */
    suspend fun read(
        synchronizer: Any,
        overview: TransactionOverview,
        load: suspend () -> List<String>,
    ): List<String> {
        val held =
            synchronized(this) {
                if (owner !== synchronizer) {
                    entries.clear()
                    owner = synchronizer
                }
                entries[overview]
            }
        if (held != null) {
            currentCoroutineContext().ensureActive()
            count(hit = true, ms = 0L)
            return held
        }
        val started = System.nanoTime()
        val memos = load()
        count(hit = false, ms = (System.nanoTime() - started) / NANOS_PER_MS)
        if (memos.size == overview.memoCount) {
            synchronized(this) {
                if (owner === synchronizer) entries[overview] = memos
            }
        }
        return memos
    }

    /**
     * ZC-946-3 (review 4, nit 3): forgets every answer and its owner, so no decrypted memo of a wallet that is gone
     * stays in memory (Reset; the synchronizer went away). A load still running then is not kept: its owner is gone.
     */
    fun clear() {
        synchronized(this) {
            entries.clear()
            owner = null
        }
    }

    private fun count(
        hit: Boolean,
        ms: Long,
    ) {
        val line =
            synchronized(this) {
                if (hit) hits++ else misses++
                readMs += ms
                if ((hits + misses) % LOG_EVERY == 0L) "memo hits=$hits misses=$misses sdkMs=$readMs size=${entries.size}" else null
            }
        line?.let { Log.d("ZCHAT_PERF", it) }
    }

    private companion object {
        /** PERF-SPEC section 2. */
        const val MAX_ENTRIES = 2_000
        const val INITIAL_CAPACITY = 256
        const val LOAD_FACTOR = 0.75f
        const val LOG_EVERY = 100L
        const val NANOS_PER_MS = 1_000_000L
    }
}
