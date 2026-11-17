package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.screen.chat.viewmodel.HandshakeHarness.awaitIdle
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestCoroutineScheduler
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * KI-23 (ZC-124, ZC-020; SPEC D6, Part B, the ChatViewModel half) — a key answer that was paid for and proven never
 * to have reached the chain is never paid again by itself.
 *
 * In 2.13.0 nothing re-sends such an answer automatically: the creator sees "Key not delivered" in Group Settings and
 * taps Resend, which shows its price. So whatever the send-attempt ledger says about the one paid answer (unresolved,
 * mined, proven not sent, or nothing any more), a further request of that member for that key must build nothing and
 * ask nothing. The ledger holds a request back while it has an entry; once the entry is gone only the paid count does.
 *
 * Also pinned: the proof takes the answer's entry to "not sent" and writes no record, no count and no budget unit
 * (a precondition from F8); and a free relay delivery of the key removes the member's failed entries of both key kinds,
 * so the badge goes away.
 *
 * Everything is synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NotSentGrantIsNeverPaidAgainByItselfTest {
    private val sendScopes = SendHarnessScopes()
    private val scheduler = TestCoroutineScheduler()

    @Before
    fun setUp() = GroupLedgerTests.setUp(sendScopes, scheduler)

    @After
    fun tearDown() = GroupLedgerTests.tearDown(sendScopes)

    @Test
    fun `the proof takes the paid answer to not sent and writes no record, count or budget unit`() =
        runBlocking {
            val w = KeyGrantWorld(scheduler)
            w.world.storedNeverMined()
            val vm = w.chatVm(w.world.seam, ledger = true)

            w.chain(vm, MA3.A, tx = 1)
            awaitIdle(vm)
            assertEquals("fixture sanity: the answer was paid once", 1, w.submits())
            assertEquals("LIVE", w.grantEntry(MA3.A)?.state)
            assertEquals("the paid answer left no paid count", 1, w.store.count(MA3.A))
            val writes = w.store.writes.toList()
            val granted = w.store.granted.toSet()

            w.world.walletLists()
            val from = w.world.seam.expiry + GroupLedgerWorld.MARGIN
            w.world.pass(vm, from - 1)
            assertEquals("LIVE", w.grantEntry(MA3.A)?.state)
            w.world.pass(vm, from)
            assertEquals("one read past the expiry is not a proof", "LIVE", w.grantEntry(MA3.A)?.state)
            w.world.pass(vm, from + 1)

            assertEquals(
                "an answer that was stored, never mined and is past its expiry on two passes at rising tips is not " +
                    "proven not sent (ledger: ${w.ledger.all()})",
                "NOT_SENT",
                w.grantEntry(MA3.A)?.state,
            )
            assertEquals("the proof wrote the request record, the count or the budget", writes, w.store.writes.toList())
            assertEquals(granted, w.store.granted.toSet())
            assertEquals(1, w.store.count(MA3.A))

            // Nothing pays by itself: a new request, and another pass.
            w.chain(vm, MA3.A, tx = 2)
            awaitIdle(vm)
            w.world.pass(vm, from + 2)
            assertEquals("a proven failure was paid again by itself", 1, w.world.seam.proposals.size)
            assertEquals("NOT_SENT", w.grantEntry(MA3.A)?.state)
            assertFalse(w.asksForConsent(vm))
        }

    @Test
    fun `a new request builds nothing while the paid answer is unresolved, mined, or forgotten by the ledger`() =
        runBlocking {
            val w = KeyGrantWorld(scheduler)
            w.world.storedNeverMined()
            val vm = w.chatVm(w.world.seam, ledger = true)

            w.chain(vm, MA3.A, tx = 1)
            awaitIdle(vm)
            assertEquals(1, w.world.seam.proposals.size)

            w.chain(vm, MA3.A, tx = 2)
            awaitIdle(vm)
            assertEquals("control: an unresolved entry holds a new request back", 1, w.world.seam.proposals.size)

            w.world.walletLists(mined = { GroupLedgerWorld.H + 100 })
            w.world.pass(vm, GroupLedgerWorld.H + 101)
            assertEquals("MINED", w.grantEntry(MA3.A)?.state)
            w.chain(vm, MA3.A, tx = 3)
            awaitIdle(vm)
            assertEquals("control: a mined entry holds a new request back", 1, w.world.seam.proposals.size)

            w.world.pass(vm, GroupLedgerWorld.H + 600)
            assertNull(w.grantEntry(MA3.A))
            val ledgerWrites = w.world.ledger.writes()
            // The standing consent is withdrawn meanwhile: a second payment must not even be asked for.
            w.store.consent = false
            w.chain(vm, MA3.A, tx = 4)
            awaitIdle(vm)

            assertEquals(
                "the member's key was paid for once and mined; with the ledger's entry gone, a new request of the " +
                    "same member for the same key was paid for again",
                1,
                w.world.seam.proposals.size,
            )
            assertFalse("a second payment for a key that was already paid for was asked for", w.asksForConsent(vm))
            assertEquals("the request path wrote the ledger", ledgerWrites, w.world.ledger.writes())
            assertFalse("the request that was not answered was recorded", w.chainRequestId(4) in w.store.granted)
        }

    /** A precondition from F8-G (green before and after): the relay settle of both key kinds. */
    @Test
    fun `a free relay delivery of the key removes the member's failed entries of both kinds and nobody else's`() =
        runBlocking {
            val w = KeyGrantWorld(scheduler)
            val tip = GroupLedgerWorld.H + 60
            w.world.ledger.seedNotSent(w.world.seed("KEY_GRANT", w.world.memberSubject(MA3.A), "epoch:1"), tip)
            w.world.ledger.seedNotSent(w.world.seed("KEY_MEMO", w.world.memberSubject(MA3.A), "epoch:1"), tip)
            w.world.ledger.seedNotSent(w.world.seed("KEY_MEMO", w.world.memberSubject(MA3.B), "epoch:1"), tip)
            w.store.counts[w.store.grantKey(MA3.A)] = 1
            w.relays(MA3.A, acks = 1)
            val vm = w.chatVm(w.world.seam, ledger = true)

            w.chain(vm, MA3.A, tx = 1)
            awaitIdle(vm)

            assertEquals("the key was not sent over the relays once", 1, w.keysPublishedTo(MA3.A))
            assertEquals(0, w.world.seam.proposals.size)
            assertTrue("the answered request was not recorded", w.chainRequestId(1) in w.store.granted)
            assertEquals(
                "the member's failed key entries outlive a free delivery of the key (the badge would stay)",
                listOf(w.world.memberSubject(MA3.B)),
                w.ledger.all().map { it.subject },
            )
            assertEquals("a free delivery changed the paid count", 1, w.store.count(MA3.A))

            // With no ledger installed the same delivery throws nothing.
            val plain = w.chatVm(HandshakeSeam(MA3.A), ledger = false)
            w.chain(plain, MA3.A, tx = 2)
            awaitIdle(plain)
            assertEquals(2, w.keysPublishedTo(MA3.A))
        }
}
