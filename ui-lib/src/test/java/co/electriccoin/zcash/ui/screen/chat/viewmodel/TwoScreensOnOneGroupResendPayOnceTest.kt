package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.common.model.SubmitResult
import co.electriccoin.zcash.ui.screen.chat.model.GroupSettingsState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * F8-G G13 (ZC-112 part 2; G4-5 with G4-2, G4-3, G4-4) — two screens that show the same "not delivered" badge pay for
 * one Resend, not two.
 *
 * Each screen has its own GroupViewModel. Both can draw the same badge, and both can have its price question open.
 *
 * Wanted: the badge carries the failed attempt's key and marker. Screen A's Resend pays once. Screen B's tap on the
 * same, now out-of-date badge reads the attempt again, asks nothing and pays nothing; B's Pay on a question it opened
 * before A paid pays nothing either, also after A's copy was mined and its entry deleted. Two Pays at the same moment
 * are one payment (a process-wide set, cleared when the first ends, whatever it did). A kill between the attempt's
 * begin and its ids leaves the badge and its offer exactly as they were. A Resend whose submit fails after its ids were
 * recorded leaves the attempt "on its way", never "failed": no badge and no second Resend until the chain proves it.
 *
 * Every address, key, group id and transaction id is synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TwoScreensOnOneGroupResendPayOnceTest {
    private val sendScopes = SendHarnessScopes()
    private val scheduler = TestCoroutineScheduler()

    @Before
    fun setUp() = GroupLedgerTests.setUp(sendScopes, scheduler)

    @After
    fun tearDown() = GroupLedgerTests.tearDown(sendScopes)

    private suspend fun twoScreens(world: GroupLedgerWorld): Triple<GroupScreen, GroupScreen, ChatViewModel> {
        val a = world.screen()
        val b = world.screen()
        val chat = world.chatVm()
        world.keyMemosProvenNotSent(a, chat)
        world.seam.outcome = { _, ids -> SubmitResult.Success(ids) }
        return Triple(a, b, chat)
    }

    @Test
    fun `both screens draw the same badge, A's Resend pays once, and B's stale tap asks nothing and pays nothing`() =
        runBlocking {
            val world = GroupLedgerWorld(scheduler = scheduler)
            val (a, b, _) = twoScreens(world)
            val onA = world.keyBadges(a).getValue(MA3.A)
            val onB = world.keyBadges(b).getValue(MA3.A)
            assertEquals(
                "the two screens draw different offers",
                world.offerKey(onA) to world.offerStartedAt(onA),
                world.offerKey(onB) to world.offerStartedAt(onB),
            )

            world.tapResend(a, "resendKeyToMember", MA3.A, onA)
            world.confirm(a)
            assertEquals(4, world.seam.proposals.size)

            world.tapResend(b, "resendKeyToMember", MA3.A, onB)
            assertNull("the second screen's stale badge raises a price question", world.question(b))
            assertEquals("the second screen paid for the same key again", 4, world.seam.proposals.size)
            val shownOnB = world.badges(b.vm.groupSettingsState.value as GroupSettingsState.Success, "keyNotDelivered")
            assertTrue("the second screen still shows the badge after its tap", MA3.A !in shownOnB)
        }

    @Test
    fun `B's Pay on a question opened before A paid pays nothing, also after A's copy mined and its entry is gone`() =
        runBlocking {
            val world = GroupLedgerWorld(scheduler = scheduler)
            val (a, b, chat) = twoScreens(world)
            val offer = world.keyBadges(a).getValue(MA3.A)
            world.tapResend(b, "resendKeyToMember", MA3.A, offer)
            assertEquals("GROUP_KEY_SYNC", world.question(b))
            world.tapResend(a, "resendKeyToMember", MA3.A, offer)
            world.confirm(a)
            assertEquals(4, world.seam.proposals.size)

            world.confirm(b)
            assertEquals("the second screen's Pay paid for the same key again", 4, world.seam.proposals.size)

            // The same with a third screen whose question is still open when A's copy is mined and deep.
            val c = world.screen()
            val offerB = world.keyBadges(c)[MA3.B]!!
            world.tapResend(c, "resendKeyToMember", MA3.B, offerB)
            world.tapResend(a, "resendKeyToMember", MA3.B, offerB)
            world.confirm(a)
            assertEquals(5, world.seam.proposals.size)
            world.walletLists(mined = { n -> if (n == 5) world.wallet.scanned + 1 else null })
            world.pass(chat, world.wallet.scanned + 2)
            world.pass(chat, world.wallet.scanned + 500)
            assertTrue(
                "the mined copy's entry is kept past the reorg depth: ${world.ledger.all()}",
                world.entries("KEY_MEMO", MA3.B).isEmpty(),
            )
            world.confirm(c)
            assertEquals("a quote for a failed state that is gone paid", 5, world.seam.proposals.size)
        }

    @Test
    fun `two Pays at the same moment are one payment, and after a refused Pay a later Resend pays once`() =
        runBlocking {
            val world = GroupLedgerWorld(scheduler = scheduler)
            val (a, b, _) = twoScreens(world)
            val offer = world.keyBadges(a).getValue(MA3.A)
            world.tapResend(a, "resendKeyToMember", MA3.A, offer)
            world.tapResend(b, "resendKeyToMember", MA3.A, offer)
            // A's Pay is held inside its submit; B's Pay arrives while it runs.
            val hold = CompletableDeferred<Unit>()
            world.seam.holdSubmits = hold
            val first = launch { world.confirm(a) }
            withTimeout(HandshakeHarness.TIMEOUT_MS) { while (world.seam.submits.get() < 4) delay(5) }
            world.confirm(b)
            assertEquals("the second Pay built a proposal while the first was running", 4, world.seam.proposals.size)
            assertTrue("the badge of a running Resend is drawn", MA3.A !in world.keyBadges(b))
            hold.complete(Unit)
            first.join()
            world.seam.holdSubmits = null
            assertEquals(4, world.seam.submits.get())
            assertEquals("the set of running Resends is not cleared", emptySet<String>(), GroupLedgerTests.resendsInFlight()?.toSet())

            // A Pay that is refused before anything is stored leaves the offer; a later Resend pays once.
            val offerB = world.keyBadges(a).getValue(MA3.B)
            world.seam.onProposal = { _, _ -> throw IllegalStateException("synthetic refusal before anything is stored") }
            world.tapResend(a, "resendKeyToMember", MA3.B, offerB)
            world.confirm(a)
            world.seam.onProposal = null
            assertEquals("nothing was stored by the refused Pay", 4, world.seam.stored.size)
            assertEquals(emptySet<String>(), GroupLedgerTests.resendsInFlight()?.toSet())
            assertEquals(
                "the refused Pay changed the offer",
                world.offerStartedAt(offerB),
                world.offerStartedAt(world.keyBadges(a).getValue(MA3.B)),
            )
            world.tapResend(a, "resendKeyToMember", MA3.B, offerB)
            world.confirm(a)
            assertEquals("a later Resend after a refused one does not pay once", 5, world.seam.stored.size)
        }

    @Test
    fun `a kill between begin and the ids leaves the badge and its offer, and a failed submit after the ids never becomes failed`() =
        runBlocking {
            val world = GroupLedgerWorld(scheduler = scheduler)
            val (a, _, _) = twoScreens(world)
            val offer = world.keyBadges(a).getValue(MA3.A)
            world.ledger.afterMethod("begin") { throw LedgerProcess.Killed() }
            world.tapResend(a, "resendKeyToMember", MA3.A, offer)
            world.confirm(a)
            world.ledger.clearHooks()
            world.restart()

            val a2 = world.screen()
            val after = world.keyBadges(a2)[MA3.A]
            assertTrue("the badge is gone after a kill before the ids", after != null)
            assertEquals("the offer changed after a kill before the ids", world.offerStartedAt(offer), world.offerStartedAt(after!!))
            assertEquals("nothing was submitted", 3, world.seam.submits.get())

            // A Resend whose submit is rejected after its ids were recorded: the stored transaction may still go out.
            world.seam.outcome = { _, ids -> SubmitResult.Failure(txIds = ids, code = 18, description = "rejected") }
            world.tapResend(a2, "resendKeyToMember", MA3.A, after)
            world.confirm(a2)
            val entry = world.entries("KEY_MEMO", MA3.A).single()
            assertEquals("a failed submit after the ids made the attempt failed again", "LIVE", entry.state)
            assertTrue("the badge is shown although the attempt may still go out", MA3.A !in world.keyBadges(a2))
            val stored = world.seam.stored.size
            world.tapResend(a2, "resendKeyToMember", MA3.A, after)
            assertNull(world.question(a2))
            assertEquals("a second Resend was possible before the proof", stored, world.seam.stored.size)
        }

    @Test
    fun `two screens that show the same Message not delivered badge pay for one copy`() =
        runBlocking {
            val world = GroupLedgerWorld(scheduler = scheduler)
            val a = world.screen()
            val b = world.screen()
            val chat = world.chatVm()
            world.storedNeverMined()
            world.sendMessage(a, "synthetic two screens")
            world.walletLists()
            world.twoPassesPastExpiry(chat)
            world.seam.outcome = { _, ids -> SubmitResult.Success(ids) }
            val onA = world.messageBadges(a)[MA3.A]
            val onB = world.messageBadges(b)[MA3.A]
            assertTrue("the dead copies show no badge: ${world.ledger.all()}", onA != null && onB != null)

            world.tapResend(b, "resendMessageCopy", MA3.A, onB!!)
            world.tapResend(a, "resendMessageCopy", MA3.A, onA!!)
            world.confirm(a)
            assertEquals(4, world.seam.proposals.size)
            world.confirm(b)

            assertEquals("the second screen paid for the same copy again", 4, world.seam.proposals.size)
        }
}
