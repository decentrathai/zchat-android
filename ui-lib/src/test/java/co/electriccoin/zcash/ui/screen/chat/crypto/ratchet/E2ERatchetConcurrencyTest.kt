package co.electriccoin.zcash.ui.screen.chat.crypto.ratchet

import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import kotlin.test.assertEquals

/**
 * REAL-thread concurrency regression for the ratchet send path.
 *
 * The existing `concurrent_encrypts_produce_distinct_monotonic_counters` test in [E2ERatchetTest]
 * runs under `runTest`'s SINGLE-threaded scheduler: `InMemoryRatchetStateStore` has no suspension
 * points, so every `async { encrypt() }` runs to completion sequentially and the test stays green
 * even with the per-conversation mutex deleted. It therefore does NOT pin the TOCTOU it claims to.
 *
 * This test dispatches encrypts across a REAL multi-thread pool so that the
 * load-state → derive-counter → save-state read-modify-write in [E2ERatchet.encrypt] genuinely
 * races. The per-conversation mutex ([RatchetStateStore.mutexFor] held via `withLock`) must
 * serialize it.
 *
 * MUTATION THAT REDDENS THIS TEST: remove the `mutex.withLock { ... }` wrapper in
 * [E2ERatchet.encrypt] (or return a fresh Mutex per `mutexFor` call). Then two threads load the
 * same persisted `nextCounter`, both encrypt with the SAME (direction, counter) — i.e. the same
 * AES-GCM (key, nonce) pair, a catastrophic nonce reuse — and the collected counter set has
 * duplicates/gaps, failing the exact-set assertion below. (Unsynchronized HashMap access in the
 * store may also throw ConcurrentModificationException — likewise red.)
 */
class E2ERatchetConcurrencyTest {

    private val testRootKey = ByteArray(32) { it.toByte() }
    private val testConvId = "CONV0001"

    @Test
    fun parallel_encrypts_on_real_threads_never_share_or_skip_a_counter() {
        val workers = 8
        val perWorker = 25
        val total = workers * perWorker
        val pool = Executors.newFixedThreadPool(workers)
        try {
            val dispatcher = pool.asCoroutineDispatcher()
            val alice = E2ERatchet(
                rootKey = testRootKey,
                convId = testConvId,
                isLower = true,
                store = InMemoryRatchetStateStore(),
            )
            // Release all workers into encrypt() at the same instant to maximize contention.
            // Blocking await is safe: exactly `workers` coroutines on a `workers`-sized pool.
            val startGate = CyclicBarrier(workers)

            val counters: List<Long> = runBlocking {
                (0 until workers).map { w ->
                    async(dispatcher) {
                        startGate.await()
                        val mine = ArrayList<Long>(perWorker)
                        repeat(perWorker) { i ->
                            mine.add(alice.encrypt("worker $w msg $i".toByteArray()).counter)
                        }
                        mine
                    }
                }.awaitAll().flatten()
            }

            // Exactly one ciphertext per counter in [0, total): no duplicates (nonce reuse),
            // no gaps (lost update). A reverted mutex yields duplicate counters under this
            // contention, shrinking the set below `total`.
            assertEquals(total, counters.size, "every encrypt must return a counter")
            assertEquals(
                (0L until total.toLong()).toSet(),
                counters.toSet(),
                "concurrent encrypts must yield each counter exactly once — a duplicate " +
                    "(direction, counter) pair IS an AES-GCM (key, nonce) reuse",
            )
        } finally {
            pool.shutdownNow()
        }
    }
}
