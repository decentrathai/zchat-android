package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.common.model.SubmitResult
import co.electriccoin.zcash.ui.screen.chat.model.GroupMessageType
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
 * KI-23 (ZC-020, ZC-124; SPEC D2, D6, Part B, the GroupViewModel half) — the creator's Resend key: quoted, tapped,
 * paid once, and it closes the automatic door behind it.
 *
 * A key that was paid for and proven never to have reached the chain shows "Key not delivered" with Resend on the
 * creator's phone (F8-G). That tap is the user's own quoted decision: nothing of this batch may refuse, count or gate
 * it (not the paid count, not the daily budget, not the Settings switch). But after it paid, a later automatic key
 * request of that member must not pay beside it. Today the Resend writes nothing a request can see: its ledger entry
 * is a different key (or is deleted about twelve minutes after it is mined), so the request is paid for as well.
 *
 * Wanted: a Resend that pays sets the member's paid count for the current key to 1 when it is 0, on disk before the
 * submit; it is put back only when the Resend provably sent nothing; a count that cannot be saved submits nothing.
 *
 * Everything is synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ResendKeyForANotSentGrantIsQuotedUserTappedAndPaysOnceTest {
    private val sendScopes = SendHarnessScopes()
    private val scheduler = TestCoroutineScheduler()

    @Before
    fun setUp() = GroupLedgerTests.setUp(sendScopes, scheduler)

    @After
    fun tearDown() = GroupLedgerTests.tearDown(sendScopes)

    private fun failed(
        w: KeyGrantWorld,
        kind: String,
    ): EntryView = w.world.ledger.seedNotSent(w.world.seed(kind, w.world.memberSubject(MA3.A), "epoch:1"), GroupLedgerWorld.H + 60)

    private suspend fun badge(
        w: KeyGrantWorld,
        s: GroupScreen,
    ): Any =
        w.world.keyBadges(s)[MA3.A]
            ?: throw AssertionError("no \"Key not delivered\" badge for the member (ledger: ${w.ledger.all()})")

    @Test
    fun `the Resend of a failed key grant is offered, quoted and paid once, whatever the count, the budget and the switch say`() =
        runBlocking {
            val w = KeyGrantWorld(scheduler, consent = false)
            failed(w, "KEY_GRANT")
            w.store.counts[w.store.grantKey(MA3.A)] = 1
            w.store.budgetUsedUp = true
            val s = w.screen()
            val chat = w.chatVm(w.world.seam, ledger = true)

            // (a) the offer is not suppressed
            val offer = badge(w, s)
            val ledgerBefore = w.ledgerText()

            // (b) quoted, nothing before Confirm; Cancel does nothing
            w.world.tapResend(s, "resendKeyToMember", MA3.A, offer)
            assertEquals("GROUP_KEY_SYNC", w.world.question(s))
            assertEquals("the Resend's question does not quote one transaction", 1, w.world.questionCount(s))
            assertEquals(0, w.world.seam.proposals.size)
            assertTrue("something was written before Confirm: ${w.store.writes}", w.store.writes.isEmpty())
            assertEquals(ledgerBefore, w.ledgerText())
            w.world.run(s) { s.vm.dismissPaidAction() }
            assertEquals(0, w.world.seam.proposals.size)
            assertEquals(ledgerBefore, w.ledgerText())

            // (c) one payment, for the current key, with no consent question and no budget unit
            w.world.tapResend(s, "resendKeyToMember", MA3.A, badge(w, s))
            w.world.confirm(s)
            assertEquals("the Resend was refused or paid more than once", 1, w.submits())
            val resent = w.world.storedOf(GroupMessageType.GROUP_KEY, MA3.A).single()
            assertEquals(1, keyEpochOf(resent.memo.memo))
            assertTrue("the Resend asked the grant budget", w.store.consumeCalls.isEmpty())
            assertEquals("the paid count is no longer 1", 1, w.store.count(MA3.A))
            assertFalse("the Resend turned the automatic setup payments on", w.store.consent)

            // (d) the badge goes with the payment (F8-G), and the payment is settled and forgotten at depth
            assertTrue("the badge outlives the Resend", w.world.keyBadges(s).isEmpty())
            w.world.walletLists(mined = { GroupLedgerWorld.H + 100 })
            w.world.pass(chat, GroupLedgerWorld.H + 101)
            w.world.pass(chat, GroupLedgerWorld.H + 600)
            assertTrue("fixture sanity: the mined Resend is forgotten at depth: ${w.ledger.all()}", w.ledger.all().isEmpty())
            assertTrue(w.world.keyBadges(s).isEmpty())

            // (e) the automatic door stays shut
            w.store.consent = true
            w.store.budgetUsedUp = false
            w.chain(chat, MA3.A, tx = 1)
            awaitIdle(chat)
            assertEquals(
                "after the Resend paid for the member's key, an automatic key request of that member was paid for too",
                1,
                w.world.seam.proposals.size,
            )
        }

    @Test
    fun `the Resend of a failed key message sets the member's paid count, and a later key request pays nothing`() =
        runBlocking {
            val w = KeyGrantWorld(scheduler)
            failed(w, "KEY_MEMO")
            val s = w.screen()
            val chat = w.chatVm(w.world.seam, ledger = true)

            w.world.tapResend(s, "resendKeyToMember", MA3.A, badge(w, s))
            w.world.confirm(s)

            assertEquals("fixture sanity: the Resend paid one key message", 1, w.submits())
            assertEquals(
                "the Resend paid for the member's key and left no paid count: an automatic key request can pay beside it",
                1,
                w.store.count(MA3.A),
            )
            assertEquals("the Resend wrote more than the count", listOf("setGroupKeyGrantPaidCount:1"), w.store.writes.toList())

            w.chain(chat, MA3.A, tx = 1)
            awaitIdle(chat)
            assertEquals(
                "the creator's Resend paid for this key, and the member's key request was then paid for as well",
                1,
                w.world.seam.proposals.size,
            )
            assertFalse(w.chainRequestId(1) in w.store.granted)
        }

    /** Control, green before and after: a refusal before anything is stored. */
    @Test
    fun `control - a Resend refused before anything is stored changes nothing`() =
        runBlocking {
            val w = KeyGrantWorld(scheduler)
            val entry = failed(w, "KEY_MEMO")
            val s = w.screen()
            w.world.seam.onProposal = { _, _ -> throw IllegalStateException("synthetic: refused before anything is stored") }

            w.world.tapResend(s, "resendKeyToMember", MA3.A, badge(w, s))
            w.world.confirm(s)

            assertEquals(0, w.submits())
            assertEquals(0, w.store.count(MA3.A))
            val left = w.ledger.all().single()
            assertEquals("NOT_SENT", left.state)
            assertEquals(entry.startedAt, left.startedAt)
            assertTrue("the badge is gone although nothing was sent", w.world.keyBadges(s).containsKey(MA3.A))
        }

    @Test
    fun `a Resend the network rejected with nothing stored puts the count back`() =
        runBlocking {
            val w = KeyGrantWorld(scheduler)
            failed(w, "KEY_MEMO")
            val s = w.screen()
            w.world.seam.outcome = { _, _ -> SubmitResult.Failure(emptyList(), 18, "rejected") }

            w.world.tapResend(s, "resendKeyToMember", MA3.A, badge(w, s))
            w.world.confirm(s)

            assertEquals(
                "the count was not on disk before the submit",
                1,
                w.store.highestCount[w.store.grantKey(MA3.A)],
            )
            assertEquals("a Resend that provably sent nothing left the paid count at 1", 0, w.store.count(MA3.A))
        }

    @Test
    fun `a Resend whose count cannot be saved submits nothing and the badge stays`() =
        runBlocking {
            val w = KeyGrantWorld(scheduler)
            failed(w, "KEY_MEMO")
            w.store.failCountWrite = true
            val s = w.screen()

            w.world.tapResend(s, "resendKeyToMember", MA3.A, badge(w, s))
            w.world.confirm(s)

            assertTrue(
                "the Resend never asked to save the paid count: ${w.store.writes}",
                w.store.writes.any { it.startsWith("setGroupKeyGrantPaidCount") },
            )
            assertEquals("the Resend was submitted although its paid count did not reach the disk", 0, w.submits())
            assertEquals(0, w.store.count(MA3.A))
            assertTrue("the badge is gone although nothing was sent", w.world.keyBadges(s).containsKey(MA3.A))
        }

    @Test
    fun `a Resend that follows a paid grant leaves the count at 1 and writes nothing`() =
        runBlocking {
            val w = KeyGrantWorld(scheduler)
            failed(w, "KEY_GRANT")
            w.store.counts[w.store.grantKey(MA3.A)] = 1
            w.store.failCountWrite = true
            val s = w.screen()

            w.world.tapResend(s, "resendKeyToMember", MA3.A, badge(w, s))
            w.world.confirm(s)

            assertEquals("a count that is already 1 refused the Resend", 1, w.submits())
            assertTrue("the Resend wrote ${w.store.writes}", w.store.writes.isEmpty())
            assertEquals(1, w.store.count(MA3.A))
        }
}
