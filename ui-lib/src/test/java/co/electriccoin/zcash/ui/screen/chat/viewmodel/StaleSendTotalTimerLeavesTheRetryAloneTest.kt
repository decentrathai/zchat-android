package co.electriccoin.zcash.ui.screen.chat.viewmodel

import cash.z.ecc.android.sdk.model.Zatoshi
import co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferences
import co.electriccoin.zcash.ui.screen.chat.model.MessageStatus
import co.electriccoin.zcash.ui.screen.chat.usecase.ChatSendQuote
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * ZC-1379 (GPT review, verified by reading): settleLiveSendTotal removes the row's total from liveSendTotals and never cancels
 * its timer. resumeFundsWaitRows settles by hand a row a wake-up finds older than the limit (a phone that slept stopped the
 * timer, not the row's age). A Retry then arms a new total; when the old timer fires it calls settleLiveSendTotal(rowId,
 * oldTotal), which checks no identity: the Retry's queue entry and quote go, the new timer is cancelled, and the row turns
 * FAILED ("not sent") while the Retry may be in flight.
 *
 * Must hold: the manual settle cancels its timer; a stale total's timer that fires anyway leaves the Retry's row alone (not
 * FAILED, queue entry and quote kept, its own timer alive). Driven through the private functions by name; time is virtual.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class StaleSendTotalTimerLeavesTheRetryAloneTest {
    private val sendScopes = SendHarnessScopes()
    private val scheduler = TestCoroutineScheduler()

    @Before
    fun setUp() {
        LedgerTests.setUp(sendScopes)
        Dispatchers.setMain(UnconfinedTestDispatcher(scheduler))
        totals().clear()
        quotes().clear()
    }

    @After
    fun tearDown() {
        totals().values.forEach { (field(it, "job") as Job?)?.cancel() }
        totals().clear()
        quotes().clear()
        LedgerTests.tearDown(sendScopes)
    }

    private fun method(name: String) =
        ChatViewModel::class.java.declaredMethods
            .first { it.name == name && !it.isSynthetic && it.parameterCount == if (name == "liveSendTotalFor") 3 else 2 }
            .apply { isAccessible = true }

    private fun staticField(name: String): Any =
        ChatViewModel::class.java
            .getDeclaredField(name)
            .apply { isAccessible = true }
            .get(null)!!

    @Suppress("UNCHECKED_CAST")
    private fun totals() = staticField("liveSendTotals") as MutableMap<String, Any>

    @Suppress("UNCHECKED_CAST")
    private fun quotes() = staticField("pendingSendQuotes") as MutableMap<String, ChatSendQuote>

    private fun field(
        owner: Any,
        name: String,
    ): Any? =
        owner.javaClass
            .getDeclaredField(name)
            .apply { isAccessible = true }
            .get(owner)

    private fun arm(
        vm: ChatViewModel,
        leftMs: Long,
    ): Any = method("liveSendTotalFor").invoke(vm, ROW, null, leftMs)!!

    private fun settleByHand(
        vm: ChatViewModel,
        total: Any,
    ) {
        method("settleLiveSendTotal").invoke(vm, ROW, total)
    }

    @Suppress("UNCHECKED_CAST")
    private fun queue(vm: ChatViewModel): MutableList<Any> = field(vm, "messageQueue") as MutableList<Any>

    private fun queueEntry(): Any =
        Class
            .forName("co.electriccoin.zcash.ui.screen.chat.viewmodel.ChatViewModel\$QueuedMessage")
            .declaredConstructors
            .first { it.parameterCount == 4 }
            .apply { isAccessible = true }
            .newInstance(world.peer, "hello", 1_000L, ROW)

    private val world = LedgerWorld()

    private fun stored(status: MessageStatus) {
        world.prefs.rows[ROW] =
            ZchatPreferences.PendingMessageData(
                id = ROW,
                text = "hello",
                timestampMillis = System.currentTimeMillis(),
                peerAddress = world.peer,
                status = status.name,
            )
    }

    private class Retried(
        val vm: ChatViewModel,
        val old: Any,
        val retry: Any,
    )

    /** A row's total armed on an earlier block; a later wake-up settles it by hand; the user taps Retry. */
    private suspend fun settledByHandThenRetried(): Retried {
        val vm = world.openVm()
        stored(MessageStatus.SENDING)
        val old = arm(vm, OLD_TIMER_MS)
        settleByHand(vm, old)
        assertEquals("fixture: the manual settle did not mark the row not sent", MessageStatus.FAILED.name, world.prefs.rows[ROW]?.status)

        // The Retry: the row sends again with its quote, in the queue, under a new total.
        stored(MessageStatus.SENDING)
        quotes()[ROW] = ChatSendQuote(world.peer, 1, Zatoshi(1_000L), Zatoshi(16_000L), Zatoshi(15_000L))
        synchronized(queue(vm)) { queue(vm).add(queueEntry()) }
        val retry = arm(vm, RETRY_TIMER_MS)
        assertNotEquals("fixture: the Retry did not get a total of its own", old, retry)
        return Retried(vm, old, retry)
    }

    @Test
    fun `the manual settle cancels the timer of the total it settles`() =
        runBlocking {
            val vm = world.openVm()
            stored(MessageStatus.SENDING)
            val old = arm(vm, OLD_TIMER_MS)

            settleByHand(vm, old)

            assertFalse("a settled total's timer is still armed", (field(old, "job") as Job).isActive)
        }

    @Test
    fun `a stale total's timer leaves the Retry's row alone`() =
        runBlocking {
            val retried = settledByHandThenRetried()

            scheduler.advanceTimeBy(OLD_TIMER_MS + 1)
            scheduler.runCurrent()

            assertNotEquals(
                "the old total's timer marked the Retry's row not sent",
                MessageStatus.FAILED.name,
                world.prefs.rows[ROW]?.status,
            )
            assertTrue("the old total's timer removed the Retry's quote", quotes().containsKey(ROW))
            assertTrue(
                "the old total's timer removed the Retry's queue entry",
                synchronized(queue(retried.vm)) { queue(retried.vm).any { field(it, "pendingId") == ROW } },
            )
            assertSame("the Retry's total is no longer the row's", retried.retry, totals()[ROW])
            assertTrue("the old total's timer cancelled the Retry's timer", (field(retried.retry, "job") as Job).isActive)
        }

    private companion object {
        const val ROW = "zc1379-row"
        const val OLD_TIMER_MS = 60_000L
        const val RETRY_TIMER_MS = 600_000L
    }
}
