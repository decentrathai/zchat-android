package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.screen.chat.model.RebuiltGroupKey
import co.electriccoin.zcash.ui.screen.chat.viewmodel.KexCeilingPassFixture.Companion.txId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * ZC-1068 (T7bG cross review, GPT and Grok, high) — the first new key of a rebuilt group waits for the newest block time a
 * history pass read (RebuiltGroupKey.newestMinedTxMs; GroupViewModel.rebuiltKeyWaitsForHistory checks only > 0). At
 * fbe847332 any pass that lists a mined transaction sets it: a pass while the history scan is still running, or one with
 * a memo it could not read. Our own removal of a member may sit in exactly what that pass did not read, so the key change
 * opens while the removed member is still listed and is among the new key's recipients.
 *
 * Wanted: the block time is taken only from a pass that read the whole history (scan complete, every memo read). A phone
 * with no network still gets there: the pass runs over the wallet's stored transactions. Every key, txid and address is
 * synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RebuiltKeyWaitsForAWholeHistoryPassTest {
    private val sendScopes = SendHarnessScopes()
    private val main = UnconfinedTestDispatcher()

    private companion object {
        const val OWN_HEIGHT = 3_000_100L
        const val OWN_HEIGHT_2 = 3_000_120L
    }

    @Before
    fun setUp() {
        sendScopes.open()
        Dispatchers.setMain(main)
        RebuiltGroupKey.newestMinedTxMs = 0L
    }

    @After
    fun tearDown() {
        RebuiltGroupKey.newestMinedTxMs = 0L
        sendScopes.close()
        Dispatchers.resetMain()
    }

    @Test
    fun `a complete scan with a memo it could not read gives no block time, and the next whole pass does`() =
        runBlocking {
            val f = KexCeilingPassFixture()
            val txs = listOf(f.outgoing(1, OWN_HEIGHT), f.outgoing(2, OWN_HEIGHT_2))
            f.onePass(txs, memos = emptyMap(), scheduler = main.scheduler, unreadable = setOf(txId(2)))
            assertEquals(
                "a pass that could not read a memo (it may hold our own removal) gave the block time",
                0L,
                RebuiltGroupKey.newestMinedTxMs,
            )

            f.onePass(txs, memos = emptyMap(), scheduler = main.scheduler)
            assertTrue("a later pass that read every memo did not give the block time", RebuiltGroupKey.newestMinedTxMs > 0L)
        }

    @Test
    fun `a pass while the history scan is still running gives no block time`() =
        runBlocking {
            val f = KexCeilingPassFixture()
            val txs = listOf(f.outgoing(1, OWN_HEIGHT), f.outgoing(2, OWN_HEIGHT_2))
            f.onePass(txs, memos = emptyMap(), scheduler = main.scheduler, scanComplete = false)
            assertEquals("a pass of an unfinished scan (a removal not yet listed) gave the block time", 0L, RebuiltGroupKey.newestMinedTxMs)
        }

    /** Control, GREEN before and after: an offline phone's pass over its stored, fully read history gives the block time. */
    @Test
    fun `control - a whole pass over the stored transactions gives the newest mined block time`() =
        runBlocking {
            val f = KexCeilingPassFixture()
            val older = f.outgoing(1, OWN_HEIGHT)
            val newer = f.outgoing(2, OWN_HEIGHT_2)
            f.onePass(listOf(older, newer), memos = emptyMap(), scheduler = main.scheduler)
            val newest = listOf(older, newer).mapNotNull { it.timestamp?.toEpochMilli() }.max()
            assertEquals("a whole pass did not give the newest mined block time", newest, RebuiltGroupKey.newestMinedTxMs)
        }
}
