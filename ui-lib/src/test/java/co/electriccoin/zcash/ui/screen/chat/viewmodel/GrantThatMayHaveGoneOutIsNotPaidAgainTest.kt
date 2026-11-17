package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.common.model.SubmitResult
import co.electriccoin.zcash.ui.screen.chat.model.GroupMessageType
import co.electriccoin.zcash.ui.screen.chat.viewmodel.HandshakeHarness.awaitIdle
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestCoroutineScheduler
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * KI-23 (ZC-124; SPEC D5, Part A) — a key answer that may have gone out is never paid again.
 *
 * When the SDK stores the answer's transaction and the submit then fails ("stored, then rejected"), the transaction
 * can still be broadcast. Every other automatic sender keeps its "already paid" record in that case. The key answer
 * withdraws it: the request is still in the history, so the next scan answers it again and pays again. The
 * send-attempt ledger holds a second payment back only while its entry of the first one exists; that entry is deleted
 * about twelve minutes after the first payment is mined, and then nothing is left to stop the next scan.
 *
 * Wanted: after a stored-then-rejected submit the per-request record and the paid count stay, and neither the same
 * request scanned again nor a new request of that member for that key is paid: (a) with no ledger at all, (b) with
 * the ledger's entry unresolved, (c) after the ledger has settled the payment as mined and forgotten it at depth.
 * Only a rejection that stored nothing withdraws the records, and the next scan then pays once.
 *
 * Everything is synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GrantThatMayHaveGoneOutIsNotPaidAgainTest {
    private val sendScopes = SendHarnessScopes()
    private val scheduler = TestCoroutineScheduler()

    @Before
    fun setUp() = GroupLedgerTests.setUp(sendScopes, scheduler)

    @After
    fun tearDown() = GroupLedgerTests.tearDown(sendScopes)

    private fun storedThenRejected(): SubmitResult =
        SubmitResult.Failure(txIds = listOf("synthetic-stored"), code = 18, description = "rejected")

    @Test
    fun `with no ledger a stored-then-rejected answer is not paid again by a rescan or by a new request`() =
        runBlocking {
            val w = KeyGrantWorld(scheduler)
            val seam = HandshakeSeam(MA3.A, firstSubmitOutcome = storedThenRejected())
            val vm = w.chatVm(seam, ledger = false)

            w.chain(vm, MA3.A, tx = 1)
            awaitIdle(vm)
            assertEquals("fixture sanity: the answer was built and handed to the SDK once", 1, seam.submits.get())

            w.chain(vm, MA3.A, tx = 1)
            awaitIdle(vm)
            assertEquals(
                "the SDK stored the answer's transaction and may still broadcast it, but the next scan of the SAME " +
                    "request paid for it again: the record of the first payment was withdrawn",
                1,
                seam.submits.get(),
            )
            w.chain(vm, MA3.A, tx = 2)
            awaitIdle(vm)
            assertEquals("a new request of the member for the same key was paid beside the stored one", 1, seam.submits.get())
            assertTrue("the request record was withdrawn", w.chainRequestId(1) in w.store.granted)
            assertEquals("the paid count was withdrawn", 1, w.store.count(MA3.A))
        }

    @Test
    fun `with the ledger's entry unresolved the records of a stored-then-rejected answer stay`() =
        runBlocking {
            val w = KeyGrantWorld(scheduler)
            w.world.seam.outcome = { n, ids -> if (n == 1) SubmitResult.Failure(ids, 18, "rejected") else SubmitResult.Success(ids) }
            val vm = w.chatVm(w.world.seam, ledger = true)

            w.chain(vm, MA3.A, tx = 1)
            awaitIdle(vm)
            assertEquals("fixture sanity: one submit", 1, w.submits())
            assertEquals("fixture sanity: the ledger holds the answer with its id", 1, w.grantEntry(MA3.A)?.txIds?.size)

            w.chain(vm, MA3.A, tx = 1)
            awaitIdle(vm)
            w.chain(vm, MA3.A, tx = 2)
            awaitIdle(vm)

            assertEquals(1, w.submits())
            assertEquals("LIVE", w.grantEntry(MA3.A)?.state)
            assertTrue(
                "the record of a payment that may still reach the chain was withdrawn; only the ledger's entry holds " +
                    "the next scan back, and that entry does not stay",
                w.chainRequestId(1) in w.store.granted,
            )
            assertEquals("the paid count of a payment that may still reach the chain is not kept", 1, w.store.count(MA3.A))
        }

    @Test
    fun `after the ledger settled the payment as mined and forgot it at depth nothing is paid again`() =
        runBlocking {
            val w = KeyGrantWorld(scheduler)
            w.world.seam.outcome = { n, ids -> if (n == 1) SubmitResult.Failure(ids, 18, "rejected") else SubmitResult.Success(ids) }
            val vm = w.chatVm(w.world.seam, ledger = true)

            w.chain(vm, MA3.A, tx = 1)
            awaitIdle(vm)
            assertEquals(1, w.submits())

            // The stored transaction was broadcast after all and mined.
            w.world.walletLists(mined = { GroupLedgerWorld.H + 100 })
            w.world.pass(vm, GroupLedgerWorld.H + 101)
            assertEquals("fixture sanity: the pass settled the answer as mined", "MINED", w.grantEntry(MA3.A)?.state)
            w.chain(vm, MA3.A, tx = 1)
            awaitIdle(vm)
            w.chain(vm, MA3.A, tx = 2)
            awaitIdle(vm)
            assertEquals("control: while the mined entry stands nothing is built", 1, w.world.seam.proposals.size)

            w.world.pass(vm, GroupLedgerWorld.H + 600)
            assertNull("fixture sanity: the mined entry is deleted at depth", w.grantEntry(MA3.A))

            w.chain(vm, MA3.A, tx = 1)
            awaitIdle(vm)
            assertEquals(
                "the answer was MINED, its ledger entry was deleted at depth, and the next scan of the same request " +
                    "paid for it a second time",
                1,
                w.submits(),
            )
            w.chain(vm, MA3.A, tx = 3)
            awaitIdle(vm)
            assertEquals("a new request for the key that was paid for and mined was paid again", 1, w.submits())
            assertEquals(1, w.world.storedOf(GroupMessageType.GROUP_KEY, MA3.A).size)
        }

    /** Control, green before and after. */
    @Test
    fun `control - an answer the network took is not paid again by a rescan`() =
        runBlocking {
            val w = KeyGrantWorld(scheduler)
            val seam = HandshakeSeam(MA3.A)
            val vm = w.chatVm(seam, ledger = false)

            w.chain(vm, MA3.A, tx = 1)
            awaitIdle(vm)
            w.chain(vm, MA3.A, tx = 1)
            awaitIdle(vm)

            assertEquals(1, seam.submits.get())
            assertEquals(1, seam.broadcasts.size)
        }

    @Test
    fun `a rejection that stored nothing withdraws the records and the next scan pays once`() =
        runBlocking {
            val w = KeyGrantWorld(scheduler)
            val seam =
                HandshakeSeam(MA3.A, firstSubmitOutcome = SubmitResult.Failure(txIds = emptyList(), code = 18, description = "rejected"))
            val vm = w.chatVm(seam, ledger = false)

            w.chain(vm, MA3.A, tx = 1)
            awaitIdle(vm)
            assertEquals(1, seam.submits.get())
            assertTrue("nothing was stored, but the request stays recorded as answered", w.store.granted.isEmpty())
            assertEquals("the stamp never wrote the paid count", 1, w.store.highestCount[w.store.grantKey(MA3.A)])
            assertEquals("nothing was stored, but the paid count stays", 0, w.store.count(MA3.A))

            w.chain(vm, MA3.A, tx = 1)
            awaitIdle(vm)
            w.chain(vm, MA3.A, tx = 1)
            awaitIdle(vm)
            assertEquals("after a proven non-send the request is answered once", 2, seam.submits.get())
            assertEquals(1, seam.broadcasts.size)
        }
}
