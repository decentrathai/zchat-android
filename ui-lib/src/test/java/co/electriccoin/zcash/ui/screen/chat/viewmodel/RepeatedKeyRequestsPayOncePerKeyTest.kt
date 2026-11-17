package co.electriccoin.zcash.ui.screen.chat.viewmodel

import androidx.lifecycle.viewModelScope
import co.electriccoin.zcash.ui.screen.chat.viewmodel.HandshakeHarness.awaitIdle
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * KI-23 (ZC-020; SPEC D2, D3) — one paid answer per member per key.
 *
 * Every tap on "sync group keys" is a new request with a new id, and today each one is answered on its own: when the
 * answer cannot go free over the relays and the standing consent is given, the creator pays 17,000 zat per request,
 * with no limit. Five taps are five payments for the same key to the same member.
 *
 * Wanted: the first such request is paid once. Every later request of that member for that key is answered over the
 * relays only: it is neither paid nor recorded, so a relay that works later still answers it. Two requests that arrive
 * together pay once. A back-out while the payment is broadcast still counts as the one payment. A new key (after a
 * rotation) and another member each get their own one paid answer.
 *
 * No send-attempt ledger is installed: this is the count alone. Everything is synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RepeatedKeyRequestsPayOncePerKeyTest {
    private val sendScopes = SendHarnessScopes()
    private val scheduler = TestCoroutineScheduler()

    @Before
    fun setUp() = GroupLedgerTests.setUp(sendScopes, scheduler)

    @After
    fun tearDown() = GroupLedgerTests.tearDown(sendScopes)

    @Test
    fun `five on-chain requests of one member for one key pay once and the other four are not recorded`() =
        runBlocking {
            val w = KeyGrantWorld(scheduler)
            val seam = HandshakeSeam(MA3.A)
            val vm = w.chatVm(seam, ledger = false)

            for (tx in 1..5) {
                w.chain(vm, MA3.A, tx)
                awaitIdle(vm)
            }

            assertEquals(
                "five requests of one member for one key built ${seam.proposalRequests.get()} on-chain answers: " +
                    "every tap on \"sync group keys\" makes the creator pay again",
                1,
                seam.proposalRequests.get(),
            )
            assertEquals(1, seam.broadcasts.size)
            assertEquals("the paid count of the member's key is not 1", 1, w.store.count(MA3.A))
            assertEquals(
                "only the paid request is recorded as answered (the others can still be answered free)",
                setOf(w.chainRequestId(1)),
                w.store.granted.toSet(),
            )
            assertEquals("one unit of the daily budget per paid answer", 1, w.store.budget.size)
        }

    @Test
    fun `relay requests that cannot be answered free pay once, and the later ones go over the relays when they work`() =
        runBlocking {
            val w = KeyGrantWorld(scheduler)
            w.relays(MA3.A, acks = 0)
            val seam = HandshakeSeam(MA3.A)
            val vm = w.chatVm(seam, ledger = false)

            for (n in 1..3) {
                w.relay(vm, MA3.A, event = "synthetic-ki23-request-$n")
                awaitIdle(vm)
            }
            assertEquals(
                "three relay requests of one member for one key, with relays that took no answer, were each paid for",
                1,
                seam.proposalRequests.get(),
            )

            w.net.acks = { 1 }
            val before = w.keysPublishedTo(MA3.A)
            for (n in 4..5) {
                w.relay(vm, MA3.A, event = "synthetic-ki23-request-$n")
                awaitIdle(vm)
            }
            assertEquals("the later requests were not answered over the relays", before + 2, w.keysPublishedTo(MA3.A))
            assertEquals(1, seam.proposalRequests.get())
            assertEquals("the paid count moved on a free answer", 1, w.store.count(MA3.A))
        }

    @Test
    fun `a second request of the member that arrives while the first is being paid pays nothing`() =
        runBlocking {
            val w = KeyGrantWorld(scheduler)
            val seam = HandshakeSeam(MA3.A, holdFirstSubmit = true)
            val chatList = w.chatVm(seam, ledger = false)
            val chatDetail = w.chatVm(seam, ledger = false)

            w.chain(chatList, MA3.A, tx = 11)
            withTimeout(HandshakeHarness.TIMEOUT_MS) { seam.firstSubmitEntered.await() }
            // The member tapped again: a different request reaches the other screen's scan.
            w.chain(chatDetail, MA3.A, tx = 12)
            seam.release.complete(Unit)
            awaitIdle(chatList, chatDetail)

            assertEquals(
                "two different requests of one member for one key were both paid (the claim is per request)",
                1,
                seam.broadcasts.size,
            )
            assertEquals(1, seam.proposalRequests.get())
        }

    @Test
    fun `backing out while the answer is broadcast keeps the one payment, and a new request pays nothing`() =
        runBlocking {
            val w = KeyGrantWorld(scheduler)
            val seam = HandshakeSeam(MA3.A, holdFirstSubmit = true)
            val first = w.chatVm(seam, ledger = false)

            w.chain(first, MA3.A, tx = 21)
            withTimeout(HandshakeHarness.TIMEOUT_MS) { seam.firstSubmitEntered.await() }
            first.viewModelScope.coroutineContext.job
                .cancelAndJoin()
            seam.release.complete(Unit)
            withTimeout(HandshakeHarness.TIMEOUT_MS) { seam.firstSubmitDone.await() }

            val next = w.chatVm(seam, ledger = false)
            w.chain(next, MA3.A, tx = 22)
            awaitIdle(next)

            assertEquals(
                "the answer reached the network, and a new request of the same member for the same key was paid again",
                1,
                seam.broadcasts.size,
            )
            assertEquals("the paid count did not survive the back-out", 1, w.store.count(MA3.A))
        }

    /** Control, green before and after: a new key is a new paid answer, and so is another member. */
    @Test
    fun `control - after a rotation the member is paid for once more, and another member once`() =
        runBlocking {
            val w = KeyGrantWorld(scheduler)
            val seam = HandshakeSeam(MA3.A)
            val vm = w.chatVm(seam, ledger = false)

            w.chain(vm, MA3.A, tx = 31)
            awaitIdle(vm)
            assertEquals(1, seam.proposalRequests.get())

            w.rotate()
            w.chain(vm, MA3.A, tx = 32, haveEpoch = 1)
            awaitIdle(vm)
            assertEquals("a request for the NEW key was not answered", 2, seam.proposalRequests.get())

            w.chain(vm, MA3.B, tx = 33, haveEpoch = 1)
            awaitIdle(vm)
            assertEquals("another member's request was not answered", 3, seam.proposalRequests.get())
            assertEquals(3, seam.broadcasts.size)
        }
}
