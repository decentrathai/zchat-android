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
 * KI-23 (ZC-020; SPEC D1) — a member who says it already holds the current key is never paid for.
 *
 * A key request carries the key number the member says it has. Today a request that names the CURRENT number is
 * answered like any other: free over the relays when that works, and otherwise with an on-chain payment (17,000 zat)
 * under the standing consent, or with the consent question. A member can tap "sync group keys" as often as it likes
 * and the requester sets that number itself, so the creator pays for a key the member already has.
 *
 * Wanted: such a request is answered over the relays only. With no relay route, or with relays that take nothing,
 * nothing is built, nothing is paid, no consent question is raised and the request is not recorded as answered (a later
 * request can still be answered free). A relay that takes the answer delivers it and records the request.
 *
 * Every address, key, group id and transaction id is synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class KeyRequestAtCurrentEpochIsNeverPaidOnChainTest {
    private val sendScopes = SendHarnessScopes()
    private val scheduler = TestCoroutineScheduler()

    @Before
    fun setUp() = GroupLedgerTests.setUp(sendScopes, scheduler)

    @After
    fun tearDown() = GroupLedgerTests.tearDown(sendScopes)

    @Test
    fun `with no relay route a request at the current key number builds nothing and is not recorded`() =
        runBlocking {
            val w = KeyGrantWorld(scheduler)
            val seam = HandshakeSeam(MA3.A)
            val vm = w.chatVm(seam, ledger = false)

            w.chain(vm, MA3.A, tx = 1, haveEpoch = 1)
            awaitIdle(vm)

            assertEquals(
                "the creator paid on-chain to send the current key to a member whose request says it already holds it",
                0,
                seam.proposalRequests.get(),
            )
            assertEquals(0, seam.broadcasts.size)
            assertTrue("a request that was not answered was recorded as answered: ${w.store.granted}", w.store.granted.isEmpty())
            assertEquals("a paid count was written for a request that was not paid", 0, w.store.count(MA3.A))
            assertTrue("a unit of the daily budget was used for nothing", w.store.budget.isEmpty())
        }

    @Test
    fun `when the relays take nothing a request at the current key number builds nothing and is not recorded`() =
        runBlocking {
            val w = KeyGrantWorld(scheduler)
            w.relays(MA3.A, acks = 0)
            val seam = HandshakeSeam(MA3.A)
            val vm = w.chatVm(seam, ledger = false)

            w.chain(vm, MA3.A, tx = 2, haveEpoch = 1)
            awaitIdle(vm)

            assertEquals("fixture sanity: the free relay copy was tried first", 1, w.keysPublishedTo(MA3.A))
            assertEquals(
                "the relays took nothing, and the creator then paid on-chain for a key the member says it holds",
                0,
                seam.proposalRequests.get(),
            )
            assertTrue("a request that was not answered was recorded as answered", w.store.granted.isEmpty())
        }

    @Test
    fun `a relay that takes the answer delivers the key free and records the request`() =
        runBlocking {
            val w = KeyGrantWorld(scheduler)
            w.relays(MA3.A, acks = 1)
            val seam = HandshakeSeam(MA3.A)
            val vm = w.chatVm(seam, ledger = false)

            w.chain(vm, MA3.A, tx = 3, haveEpoch = 1)
            awaitIdle(vm)

            assertEquals("the key was not sent to the member over the relays exactly once", 1, w.keysPublishedTo(MA3.A))
            assertEquals(0, seam.proposalRequests.get())
            assertEquals("the answered request was not recorded", setOf(w.chainRequestId(3)), w.store.granted.toSet())
            assertEquals("a free answer wrote the paid count", 0, w.store.count(MA3.A))
            assertTrue("a free answer used a unit of the daily budget", w.store.budget.isEmpty())
        }

    @Test
    fun `without the standing consent a request at the current key number raises no question`() =
        runBlocking {
            val w = KeyGrantWorld(scheduler, consent = false)
            val seam = HandshakeSeam(MA3.A)
            val vm = w.chatVm(seam, ledger = false)

            w.chain(vm, MA3.A, tx = 4, haveEpoch = 1)
            awaitIdle(vm)

            assertFalse(
                "the creator was asked to allow a payment for a key the member says it already holds",
                w.asksForConsent(vm),
            )
            assertEquals(0, seam.proposalRequests.get())
        }

    /** Control, green before and after: a member one key number behind is still answered on-chain, once. */
    @Test
    fun `control - a request from a member one key number behind is paid once`() =
        runBlocking {
            val w = KeyGrantWorld(scheduler)
            val seam = HandshakeSeam(MA3.A)
            val vm = w.chatVm(seam, ledger = false)

            w.chain(vm, MA3.A, tx = 5, haveEpoch = 0)
            awaitIdle(vm)

            assertEquals(1, seam.proposalRequests.get())
            assertEquals(1, seam.broadcasts.size)
        }
}
