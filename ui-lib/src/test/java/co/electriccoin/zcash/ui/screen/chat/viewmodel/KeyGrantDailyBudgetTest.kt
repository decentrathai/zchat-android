package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.screen.chat.viewmodel.HandshakeHarness.awaitIdle
import co.electriccoin.zcash.ui.screen.chat.viewmodel.HandshakeHarness.awaitTrue
import co.electriccoin.zcash.ui.screen.chat.viewmodel.HandshakeHarness.chatViewModelWith
import co.electriccoin.zcash.ui.screen.chat.viewmodel.HandshakeHarness.stillConfirming
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
 * KI-23 (ZC-020; SPEC D4) — at most ten automatic paid key answers a day, for the whole wallet.
 *
 * One paid answer per member and key still lets many members (or one stranger-turned-member after another) each cost
 * the creator 17,000 zat with no question, the same day. The automatic key reply to a first contact has a wallet-wide
 * daily budget; the key answer has none.
 *
 * Wanted: the budget is asked with (10, 24 hours); the eleventh paid answer of a day is not paid, raises no question
 * and is recorded nowhere, and is answered on a later scan once a unit is free again. One unit per paid answer, used
 * at its first attempt and not again when that answer waits for a block and tries once more.
 *
 * No send-attempt ledger is installed. Everything is synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class KeyGrantDailyBudgetTest {
    private val sendScopes = SendHarnessScopes()
    private val scheduler = TestCoroutineScheduler()

    @Before
    fun setUp() = GroupLedgerTests.setUp(sendScopes, scheduler)

    @After
    fun tearDown() = GroupLedgerTests.tearDown(sendScopes)

    private val eleven = (1..11).map { "usyntheticki23budgetmember%03d".format(it) }

    @Test
    fun `the eleventh paid answer of a day is not paid or recorded, and is answered once the day has passed`() =
        runBlocking {
            val w = KeyGrantWorld(scheduler, members = eleven)
            val seam = HandshakeSeam(eleven.first())
            val vm = w.chatVm(seam, ledger = false)

            eleven.forEachIndexed { i, member ->
                w.chain(vm, member, tx = 100 + i)
                awaitIdle(vm)
            }

            assertEquals(
                "eleven members asked for the key on one day and ${seam.proposalRequests.get()} answers were paid: " +
                    "there is no daily limit on automatic paid key answers",
                10,
                seam.proposalRequests.get(),
            )
            assertEquals(10, seam.broadcasts.size)
            assertEquals(
                "the daily budget was not asked with 10 a day",
                setOf(10 to 86_400_000L),
                w.store.consumeCalls.toSet(),
            )
            val last = eleven.last()
            assertFalse("the answer that was not paid was recorded as given", w.chainRequestId(110) in w.store.granted)
            assertEquals("the answer that was not paid left a paid count", 0, w.store.count(last))
            assertFalse("a used-up budget raised a question", w.asksForConsent(vm))

            // The same request is still in the history; a day later a unit is free.
            w.store.clock += 86_400_000L
            w.chain(vm, last, tx = 110)
            awaitIdle(vm)
            w.chain(vm, last, tx = 110)
            awaitIdle(vm)
            assertEquals("the eleventh member was not answered once a unit was free", 11, seam.proposalRequests.get())
            assertEquals(11, seam.broadcasts.size)
        }

    @Test
    fun `a used-up budget raises no consent question`() =
        runBlocking {
            val w = KeyGrantWorld(scheduler, consent = false)
            w.store.budgetUsedUp = true
            val seam = HandshakeSeam(MA3.A)
            val vm = w.chatVm(seam, ledger = false)

            w.chain(vm, MA3.A, tx = 120)
            awaitIdle(vm)

            assertFalse(
                "the creator was asked to allow a payment that the daily budget does not allow today",
                w.asksForConsent(vm),
            )
            assertEquals(0, seam.proposalRequests.get())
            assertTrue(w.store.granted.isEmpty())
        }

    @Test
    fun `an answer that waits for a block and tries again uses one unit of the budget`() =
        runBlocking {
            val fixture = GroupGrantFixture(member = "usyntheticki23budgetwait0001", consent = true)
            val seam = HandshakeSeam(fixture.member, failFirstProposalWith = stillConfirming())
            val heights = HeightFeed()
            val vm = chatViewModelWith(fixture.proxy, seam, heights, ironwoodPending = 200_000L)

            fixture.scan(vm, fixture.txId(7))
            awaitTrue { heights.isWaiting() }
            assertEquals("fixture sanity: the first attempt found the funds still confirming", 1, seam.proposalRequests.get())
            heights.advance()
            awaitIdle(vm)

            assertEquals("fixture sanity", emptyList<String>(), fixture.unexpectedCalls)
            assertEquals("fixture sanity: the answer was paid on its second attempt", 1, seam.broadcasts.size)
            assertEquals(
                "one paid answer did not use exactly one unit of the daily budget (10 a day)",
                listOf(10 to 86_400_000L),
                fixture.budgetConsumes.toList(),
            )
            assertEquals("the paid count after one paid answer", listOf(1), fixture.paidCounts.values.toList())
        }
}
