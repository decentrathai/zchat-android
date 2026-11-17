package co.electriccoin.zcash.ui.common.repository

import co.electriccoin.zcash.ui.common.repository.MemoReadFixture.MemoSdk
import co.electriccoin.zcash.ui.common.repository.MemoReadFixture.SwitchingProvider
import co.electriccoin.zcash.ui.common.repository.MemoReadFixture.overview
import co.electriccoin.zcash.ui.common.repository.MemoReadFixture.send
import co.electriccoin.zcash.ui.common.usecase.Zc101FakeAccountDataSource
import co.electriccoin.zcash.ui.common.usecase.syntheticZashiAccount
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ZC-946 batch 3 (PERF-SPEC revision 1 section 2; review of 14 Oct, findings 1 and 2; register ZC-958): the history
 * pass reads every transaction's memos from the SDK on every pass (about 306 reads for 141 transactions, 3.1 s on the
 * HONOR). Wanted: a bounded cache in memory under `TransactionRepositoryImpl.getMemos` that answers exactly what the SDK
 * answered and never more:
 * - a second read of an unchanged transaction asks the SDK nothing;
 * - any change of the transaction's overview (mined, memo count), and another synchronizer instance (a wallet change,
 *   Reset, Destroy), asks the SDK again, and the other instance's answers are dropped; the oldest entry goes first;
 * - only a complete answer is kept: the memos read are as many as the SDK's memoCount says the transaction has. An
 *   output whose memo could not be read ("Memo not available") reaches us as an empty string, so an answer with fewer
 *   memos (empty or partial) stays retryable on every pass, as today (ZC-958);
 * - a throw and a cancellation are never kept and reach the caller unchanged; a cancelled caller never gets an answer,
 *   from the cache either (the pass counts such a read as a failure, KR-3).
 *
 * The real TransactionRepositoryImpl over a counting stand-in synchronizer (MemoReadFixture). Every id is synthetic.
 */
class MemoCacheTest {
    private val sdk = MemoSdk()
    private val provider = SwitchingProvider(sdk.synchronizer)

    private fun repository(): TransactionRepository =
        runBlocking { TransactionRepositoryImpl(Zc101FakeAccountDataSource(syntheticZashiAccount()), provider) }

    @Test
    fun `a second read of an unchanged transaction asks the SDK nothing and answers the same`() =
        runBlocking {
            val repo = repository()
            val tx = send(overview(1, memoCount = 1))
            sdk.raw[tx.overview.txId.txIdString()] = listOf("ZMSG|synthetic-one", "")

            val first = repo.getMemos(tx)
            val second = repo.getMemos(send(overview(1, memoCount = 1)))

            assertEquals(listOf("ZMSG|synthetic-one"), first)
            assertEquals("the warm answer differs from the SDK's", first, second)
            assertEquals("the second read of an unchanged transaction asked the SDK again", 1, sdk.reads.get())
        }

    @Test
    fun `a transaction without a memo is kept as such`() =
        runBlocking {
            val repo = repository()
            val tx = send(overview(2, memoCount = 0))
            sdk.raw[tx.overview.txId.txIdString()] = listOf("", "")

            repeat(3) { assertEquals(emptyList<String>(), repo.getMemos(tx)) }

            assertEquals("a transaction the SDK says has no memo is read again", 1, sdk.reads.get())
        }

    @Test
    fun `an empty or a partial answer for a transaction with memos is never kept`() =
        runBlocking {
            val repo = repository()
            val empty = send(overview(3, memoCount = 1))
            val partial = send(overview(4, memoCount = 2))
            sdk.raw[empty.overview.txId.txIdString()] = listOf("", "")
            sdk.raw[partial.overview.txId.txIdString()] = listOf("ZMSG|synthetic-part", "", "")

            repo.getMemos(empty)
            repo.getMemos(partial)
            sdk.raw[empty.overview.txId.txIdString()] = listOf("ZMSG|synthetic-late", "")
            sdk.raw[partial.overview.txId.txIdString()] = listOf("ZMSG|synthetic-part", "ZMSG|synthetic-rest", "")

            assertEquals("an unread memo stayed unread", listOf("ZMSG|synthetic-late"), repo.getMemos(empty))
            assertEquals(
                "a partial answer was kept as complete",
                listOf("ZMSG|synthetic-part", "ZMSG|synthetic-rest"),
                repo.getMemos(partial),
            )
            assertEquals(4, sdk.reads.get())
        }

    @Test
    fun `a change of the overview asks the SDK again`() =
        runBlocking {
            val repo = repository()
            val pending = send(overview(5, memoCount = 1))
            sdk.raw[pending.overview.txId.txIdString()] = listOf("ZMSG|synthetic-five")
            repo.getMemos(pending)
            repo.getMemos(pending)
            assertEquals("fixture: the unchanged read was kept", 1, sdk.reads.get())

            repo.getMemos(send(overview(5, memoCount = 1, minedHeight = 3_990L)))
            assertEquals("a newly mined transaction was answered from before it was mined", 2, sdk.reads.get())

            sdk.raw[pending.overview.txId.txIdString()] = listOf("ZMSG|synthetic-five", "ZMSG|synthetic-six")
            val more = repo.getMemos(send(overview(5, memoCount = 2, minedHeight = 3_990L)))
            assertEquals("a changed memo count was answered from before", 3, sdk.reads.get())
            assertEquals(listOf("ZMSG|synthetic-five", "ZMSG|synthetic-six"), more)
        }

    @Test
    fun `another synchronizer instance asks the SDK again and drops what the first one answered`() =
        runBlocking {
            val repo = repository()
            val tx = send(overview(7, memoCount = 1))
            sdk.raw[tx.overview.txId.txIdString()] = listOf("ZMSG|synthetic-seven")
            val other = MemoSdk().apply { raw[tx.overview.txId.txIdString()] = listOf("ZMSG|synthetic-other-wallet") }

            repo.getMemos(tx)
            provider.current = other.synchronizer
            val afterSwitch = repo.getMemos(tx)
            provider.current = sdk.synchronizer
            repo.getMemos(tx)

            assertEquals("another wallet's synchronizer was answered from the first one's read", 1, other.reads.get())
            assertEquals(listOf("ZMSG|synthetic-other-wallet"), afterSwitch)
            assertEquals("the first instance's answers were kept across a switch", 2, sdk.reads.get())
        }

    @Test
    fun `the cache is bounded and drops the oldest entry first`() =
        runBlocking {
            val repo = repository()
            val first = send(overview(10, memoCount = 1))
            (10..10 + BOUND).forEach { n -> sdk.raw[MemoReadFixture.txId(n).txIdString()] = listOf("ZMSG|synthetic-$n") }

            (10..10 + BOUND).forEach { n -> repo.getMemos(send(overview(n, memoCount = 1))) }
            assertEquals("fixture: every read asked the SDK once", BOUND + 1, sdk.reads.get())

            repo.getMemos(send(overview(10 + BOUND, memoCount = 1)))
            assertEquals("the newest entry was not kept", BOUND + 1, sdk.reads.get())
            repo.getMemos(first)
            assertEquals("the oldest entry was kept beyond the bound", BOUND + 2, sdk.reads.get())
        }

    @Test
    fun `a throw is never kept and reaches the caller unchanged`() =
        runBlocking {
            val repo = repository()
            val tx = send(overview(20, memoCount = 1))
            val key = tx.overview.txId.txIdString()
            val failure = IllegalStateException("synthetic memo read failure")
            sdk.throws[key] = failure
            sdk.raw[key] = listOf("ZMSG|synthetic-twenty")

            val thrown = runCatching { repo.getMemos(tx) }.exceptionOrNull()
            sdk.throws.remove(key)

            assertEquals("the read's own failure did not reach the caller", failure.message, thrown?.message)
            assertEquals(listOf("ZMSG|synthetic-twenty"), repo.getMemos(tx))
            assertEquals(2, sdk.reads.get())
        }

    @Test
    fun `a cancelled read is never kept and is rethrown`() =
        runBlocking {
            val repo = repository()
            val tx = send(overview(21, memoCount = 1))
            val key = tx.overview.txId.txIdString()
            sdk.throws[key] = CancellationException("synthetic: the pass was cut short")
            sdk.raw[key] = listOf("ZMSG|synthetic-twenty-one")

            val thrown = runCatching { repo.getMemos(tx) }.exceptionOrNull()
            sdk.throws.remove(key)

            assertTrue("a cancelled read did not end in a CancellationException: $thrown", thrown is CancellationException)
            assertEquals(listOf("ZMSG|synthetic-twenty-one"), repo.getMemos(tx))
            assertEquals(2, sdk.reads.get())
        }

    @Test
    fun `a cancelled caller gets no answer, from a warm entry either`() =
        runBlocking {
            val repo = repository()
            val tx = send(overview(22, memoCount = 1))
            sdk.raw[tx.overview.txId.txIdString()] = listOf("ZMSG|synthetic-twenty-two")
            repo.getMemos(tx)

            val read =
                async(start = CoroutineStart.UNDISPATCHED) {
                    coroutineContext.job.cancel()
                    repo.getMemos(tx)
                }
            val outcome = runCatching { read.await() }

            assertTrue("a cancelled pass got a memo answer", outcome.isFailure)
            assertTrue("not a cancellation: ${outcome.exceptionOrNull()}", outcome.exceptionOrNull() is CancellationException)
        }

    private companion object {
        /** PERF-SPEC section 2: 2,000 entries. */
        const val BOUND = 2_000
    }
}
