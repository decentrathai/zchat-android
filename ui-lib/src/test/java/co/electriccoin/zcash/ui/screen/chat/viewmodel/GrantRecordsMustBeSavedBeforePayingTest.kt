package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.screen.chat.viewmodel.HandshakeHarness.awaitIdle
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestCoroutineScheduler
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * KI-23 (ZC-020; SPEC D2, D4; the R5-2 rule for the new records) — a key answer is paid only against records that
 * reached the disk.
 *
 * The paid count is what stops a second request for the same key being paid for, and the budget unit what stops the
 * eleventh answer of a day. If either cannot be written, a payment made anyway is one that nothing remembers.
 *
 * Wanted: when the count, the budget unit or the per-request record cannot be written, nothing is built or paid, and
 * what the same attempt had already written is taken back, so a later scan can answer the request.
 *
 * No send-attempt ledger is installed. Everything is synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GrantRecordsMustBeSavedBeforePayingTest {
    private val sendScopes = SendHarnessScopes()
    private val scheduler = TestCoroutineScheduler()

    @Before
    fun setUp() = GroupLedgerTests.setUp(sendScopes, scheduler)

    @After
    fun tearDown() = GroupLedgerTests.tearDown(sendScopes)

    @Test
    fun `a paid count that cannot be written pays nothing and leaves no record`() =
        runBlocking {
            val w = KeyGrantWorld(scheduler)
            w.store.failCountWrite = true
            val seam = HandshakeSeam(MA3.A)
            val vm = w.chatVm(seam, ledger = false)

            w.chain(vm, MA3.A, tx = 1)
            awaitIdle(vm)

            assertTrue(
                "the paid count was never asked to be written before the answer was paid: ${w.store.writes}",
                w.store.writes.any { it.startsWith("setGroupKeyGrantPaidCount") },
            )
            assertEquals("the answer was paid although its paid count did not reach the disk", 0, seam.proposalRequests.get())
            assertEquals(0, seam.broadcasts.size)
            assertTrue("the request is recorded as answered although nothing was sent", w.store.granted.isEmpty())
            assertTrue("a unit of the daily budget was used for an answer that was not paid", w.store.budget.isEmpty())

            // The store works again: the same request, still in the history, is answered once.
            w.store.failCountWrite = false
            w.chain(vm, MA3.A, tx = 1)
            awaitIdle(vm)
            assertEquals(1, seam.proposalRequests.get())
        }

    @Test
    fun `a budget unit that cannot be written pays nothing, asks nothing and leaves no record`() =
        runBlocking {
            val w = KeyGrantWorld(scheduler)
            w.store.failBudgetConsume = true
            val seam = HandshakeSeam(MA3.A)
            val vm = w.chatVm(seam, ledger = false)

            w.chain(vm, MA3.A, tx = 2)
            awaitIdle(vm)

            assertEquals("the budget was never asked for a unit", 1, w.store.consumeCalls.size)
            assertEquals("the answer was paid although its budget unit was not written", 0, seam.proposalRequests.get())
            assertFalse(w.asksForConsent(vm))
            assertTrue("the request is recorded as answered although nothing was sent", w.store.granted.isEmpty())
            assertEquals("the paid count was left at 1 although nothing was sent", 0, w.store.count(MA3.A))
        }

    @Test
    fun `the budget unit is used after the consent, never before the question`() =
        runBlocking {
            val w = KeyGrantWorld(scheduler, consent = false)
            val seam = HandshakeSeam(MA3.A)
            val vm = w.chatVm(seam, ledger = false)

            w.chain(vm, MA3.A, tx = 3)
            awaitIdle(vm)

            assertTrue("fixture sanity: without the consent the answer asks first", w.asksForConsent(vm))
            assertEquals(0, seam.proposalRequests.get())
            assertTrue("something was written before the user answered: ${w.store.writes}", w.store.writes.isEmpty())

            w.store.consent = true
            w.chain(vm, MA3.A, tx = 3)
            awaitIdle(vm)
            assertEquals(1, seam.proposalRequests.get())
            assertEquals(
                "the stamp is not written in the order record, count, budget unit",
                listOf("setGroupKeyRequestGranted:true", "setGroupKeyGrantPaidCount:1", "tryConsumeGroupKeyGrantBudget"),
                w.store.writes.toList(),
            )
        }

    /** The existing rule, kept: the per-request record. */
    @Test
    fun `a request record that cannot be written pays nothing and writes no count`() =
        runBlocking {
            val w = KeyGrantWorld(scheduler)
            w.store.failRecordWrite = true
            val seam = HandshakeSeam(MA3.A)
            val vm = w.chatVm(seam, ledger = false)

            w.chain(vm, MA3.A, tx = 4)
            awaitIdle(vm)

            assertEquals(0, seam.proposalRequests.get())
            assertEquals(0, w.store.count(MA3.A))
            assertTrue(w.store.budget.isEmpty())
        }
}
