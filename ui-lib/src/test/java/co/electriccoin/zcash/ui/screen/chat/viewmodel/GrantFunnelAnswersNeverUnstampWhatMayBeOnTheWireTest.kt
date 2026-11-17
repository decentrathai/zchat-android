package co.electriccoin.zcash.ui.screen.chat.viewmodel

import androidx.lifecycle.viewModelScope
import co.electriccoin.zcash.ui.screen.chat.viewmodel.HandshakeHarness.awaitIdle
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * KI-23 (ZC-124, ZC-020; SPEC D5 on F8's funnel) — what each answer of the send funnel does to the three things the
 * stamp of a paid key answer writes: the per-request record, the paid count and the unit of the daily budget.
 *
 * PENDING (another attempt of this key appeared; or this one was stored and its ids could not be recorded) keeps all
 * three: nothing is withdrawn and nothing is given back, because a transaction of this key may be on its way. Only a
 * proven non-send (the ledger refused the attempt's own entry before the SDK was asked; a cancel before the hand-over
 * to the SDK) withdraws the record and puts the count back; the budget unit is never given back. The ledger's gate
 * runs before the stamp, so its PENDING writes nothing at all.
 *
 * Everything is synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GrantFunnelAnswersNeverUnstampWhatMayBeOnTheWireTest {
    private val sendScopes = SendHarnessScopes()
    private val scheduler = TestCoroutineScheduler()

    @Before
    fun setUp() = GroupLedgerTests.setUp(sendScopes, scheduler)

    @After
    fun tearDown() = GroupLedgerTests.tearDown(sendScopes)

    private val stamp = listOf("setGroupKeyRequestGranted:true", "setGroupKeyGrantPaidCount:1", "tryConsumeGroupKeyGrantBudget")

    @Test
    fun `another attempt of the key that appears after the stamp leaves the record, the count and the unit as stamped`() =
        runBlocking {
            for (state in listOf("LIVE", "MINED", "NOT_SENT")) {
                GroupLedgerTests.resetProcess()
                val w = KeyGrantWorld(scheduler)
                val vm = w.chatVm(w.world.seam, ledger = true)
                w.world.ledger.beforeMethod("begin") {
                    w.world.ledger.clearHooks()
                    val other = w.world.seed("KEY_GRANT", w.world.memberSubject(MA3.A), "epoch:1")
                    when (state) {
                        "MINED" -> w.world.ledger.seedMined(other, GroupLedgerWorld.H + 5)
                        "NOT_SENT" -> w.world.ledger.seedNotSent(other, GroupLedgerWorld.H + 60)
                    }
                }

                w.chain(vm, MA3.A, tx = 1)
                awaitIdle(vm)

                assertEquals("[$state] the attempt that lost its key to another one was submitted", 0, w.submits())
                assertEquals(
                    "[$state] the stamp is not exactly the record, the count and one budget unit, with nothing taken back",
                    stamp,
                    w.store.writes.toList(),
                )
                assertTrue("[$state] the request record was withdrawn", w.chainRequestId(1) in w.store.granted)
                assertEquals("[$state] the paid count was withdrawn", 1, w.store.count(MA3.A))
                assertEquals("[$state] the budget unit was given back", 1, w.store.budget.size)
                assertEquals("[$state] the other attempt's entry was changed", state, w.grantEntry(MA3.A)?.state)
            }
        }

    @Test
    fun `an answer that was stored but whose ids could not be recorded keeps the record and the count`() =
        runBlocking {
            val w = KeyGrantWorld(scheduler)
            w.world.ledger.failRecordStored = true
            val vm = w.chatVm(w.world.seam, ledger = true)

            w.chain(vm, MA3.A, tx = 1)
            awaitIdle(vm)

            assertEquals("fixture sanity: stored, not submitted", 1, w.world.seam.stored.size)
            assertEquals(0, w.submits())
            assertEquals(stamp, w.store.writes.toList())
            assertEquals("the paid count of a stored transaction is not kept", 1, w.store.count(MA3.A))
            assertEquals("LIVE", w.grantEntry(MA3.A)?.state)

            w.world.ledger.failRecordStored = false
            w.chain(vm, MA3.A, tx = 2)
            awaitIdle(vm)
            assertEquals("a new request was built beside the stored transaction", 1, w.world.seam.proposals.size)
        }

    @Test
    fun `an attempt whose ledger entry cannot be written sends nothing, takes its stamp back and keeps the budget unit`() =
        runBlocking {
            val w = KeyGrantWorld(scheduler)
            w.world.ledger.failBegin = true
            val vm = w.chatVm(w.world.seam, ledger = true)

            w.chain(vm, MA3.A, tx = 1)
            awaitIdle(vm)

            assertEquals(0, w.world.seam.stored.size)
            assertEquals(
                "the stamp and its take-back are not: record, count, unit, then the count back and the record withdrawn",
                stamp + listOf("setGroupKeyGrantPaidCount:0", "setGroupKeyRequestGranted:false"),
                w.store.writes.toList(),
            )
            assertTrue(w.store.granted.isEmpty())
            assertEquals(0, w.store.count(MA3.A))
            assertEquals("the budget unit of an attempt is never given back", 1, w.store.budget.size)

            w.world.ledger.failBegin = false
            w.chain(vm, MA3.A, tx = 2)
            awaitIdle(vm)
            assertEquals("after a proven non-send the next request is paid once", 1, w.submits())
            assertEquals(1, w.store.count(MA3.A))
        }

    @Test
    fun `a cancel before the hand-over to the SDK takes the record and the count back`() =
        runBlocking {
            val w = KeyGrantWorld(scheduler)
            val seam = IdsHandshakeSeam(MA3.A)
            val hold = CompletableDeferred<Unit>()
            seam.holdProposals = hold
            val vm = w.chatVm(seam, ledger = true)

            w.chain(vm, MA3.A, tx = 1)
            withTimeout(HandshakeHarness.TIMEOUT_MS) { seam.proposalEntered.await() }
            assertEquals(
                "the paid count is not on disk before the answer's transaction is built",
                1,
                w.store.count(MA3.A),
            )
            assertTrue(w.chainRequestId(1) in w.store.granted)
            vm.viewModelScope.coroutineContext.job
                .cancelAndJoin()

            assertEquals(0, seam.submits.get())
            assertTrue("a cancel before the submit left the request recorded as answered", w.store.granted.isEmpty())
            assertEquals("a cancel before the submit left the paid count", 0, w.store.count(MA3.A))

            seam.holdProposals = null
            val next = w.chatVm(seam, ledger = true)
            w.chain(next, MA3.A, tx = 2)
            awaitIdle(next)
            assertEquals("the member's next request is not answered after a cancel that sent nothing", 1, seam.submits.get())
        }

    /** Control, green before and after: F8's gate runs before the stamp. */
    @Test
    fun `control - the gate's answer for an entry of the key writes nothing and uses no budget unit`() =
        runBlocking {
            for (state in listOf("LIVE", "MINED", "NOT_SENT")) {
                GroupLedgerTests.resetProcess()
                val w = KeyGrantWorld(scheduler)
                val entry = w.world.seed("KEY_GRANT", w.world.memberSubject(MA3.A), "epoch:1")
                when (state) {
                    "MINED" -> w.world.ledger.seedMined(entry, GroupLedgerWorld.H + 5)
                    "NOT_SENT" -> w.world.ledger.seedNotSent(entry, GroupLedgerWorld.H + 60)
                }
                val vm = w.chatVm(w.world.seam, ledger = true)

                w.chain(vm, MA3.A, tx = 1)
                awaitIdle(vm)

                assertEquals("[$state]", 0, w.world.seam.proposals.size)
                assertTrue("[$state] the gate's answer wrote ${w.store.writes}", w.store.writes.isEmpty())
                assertTrue("[$state]", w.store.budget.isEmpty())
                assertNotNull("[$state]", w.grantEntry(MA3.A))
            }
        }
}
