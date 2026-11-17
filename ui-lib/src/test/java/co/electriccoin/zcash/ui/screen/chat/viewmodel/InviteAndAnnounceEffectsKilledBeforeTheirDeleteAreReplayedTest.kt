package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.common.model.SubmitResult
import co.electriccoin.zcash.ui.screen.chat.model.InviteStatus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestCoroutineScheduler
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * F8-G G12 (ZC-112 part 2; G4-1) — a visibility effect killed before its entry is deleted is replayed without a second
 * write, and a tap over a leftover failed entry pays exactly once.
 *
 * After "Invite failed" (or the announce record's removal) is written, the entry is deleted. A process killed between
 * the two, or a delete that fails, leaves the failed entry behind. Without a replay rule the next pass would either
 * write the effect again or leave the entry for ever, and the leftover entry would turn the user's own Resend or
 * "Introduce them" into an attempt that is refused and pays nothing.
 *
 * Wanted: the next pass finds the effect already written, writes nothing and deletes the entry; a delete that keeps
 * failing is tried at every pass and never rewrites the row. A row left "Inviting…" by a Resend that died is written
 * "Invite failed"; while a Resend runs the entry is left alone. A Resend, or "Introduce them", raised over a leftover
 * failed entry pays exactly once against that entry; when the entry was replaced meanwhile, nothing is paid.
 *
 * Every address, key, group id and transaction id is synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class InviteAndAnnounceEffectsKilledBeforeTheirDeleteAreReplayedTest {
    private val sendScopes = SendHarnessScopes()
    private val scheduler = TestCoroutineScheduler()

    @Before
    fun setUp() = GroupLedgerTests.setUp(sendScopes, scheduler)

    @After
    fun tearDown() = GroupLedgerTests.tearDown(sendScopes)

    private val net = RelayNet()

    private fun world() = GroupLedgerWorld.twoActive(scheduler)

    @Test
    fun `E1 killed before its delete - the next process writes no roster row and deletes the entry`() =
        runBlocking {
            val world = world()
            val s = world.screen()
            val chat = world.chatVm()
            world.inviteThatNeverMines(s, MA3.X)
            world.ledger.beforeMethod("delete") { throw LedgerProcess.Killed() }

            runCatching { world.twoPassesPastExpiry(chat) }

            assertEquals("the badge was not written before the kill", InviteStatus.FAILED, world.row(MA3.X).inviteStatus)
            assertEquals("NOT_SENT", world.entries("INVITE", MA3.X).single().state)
            val writes = world.store.rosterWrites.size

            // A delete that keeps failing: tried at every pass, never a second roster write.
            world.ledger.clearHooks()
            world.restart()
            val chat2 = world.chatVm()
            world.ledger.failDelete = true
            repeat(2) { world.pass(chat2, world.wallet.scanned + 1) }
            assertEquals("the replay wrote the row again", writes, world.store.rosterWrites.size)
            assertEquals("NOT_SENT", world.entries("INVITE", MA3.X).single().state)

            world.ledger.failDelete = false
            world.pass(chat2, world.wallet.scanned + 1)
            assertEquals(writes, world.store.rosterWrites.size)
            assertTrue("the entry is not deleted once the delete works", world.ledger.of("INVITE").isEmpty())
        }

    @Test
    fun `a row left Inviting by a dead Resend is written failed, and a running Resend keeps the entry`() =
        runBlocking {
            val world = GroupLedgerWorld.activeAndInvited(scheduler)
            world.setRow(MA3.B) { it.copy(inviteStatus = InviteStatus.INVITE_PENDING) }
            val failed = world.ledger.seedNotSent(world.seed("INVITE", world.memberSubject(MA3.B), "epoch:1"), GroupLedgerWorld.H + 60)
            val chat = world.chatVm()

            // A Resend of this invite is running in this process: its row and its entry are its own.
            val running = GroupLedgerTests.resendsInFlight()
            assertTrue("this build has no process-wide set of running group Resends", running != null)
            running!!.add(failed.key)
            world.pass(chat, GroupLedgerWorld.H + 61)
            assertEquals("the pass rewrote the row of a running Resend", InviteStatus.INVITE_PENDING, world.row(MA3.B).inviteStatus)
            assertEquals("NOT_SENT", world.entries("INVITE", MA3.B).single().state)

            // The Resend died (nothing is running): the row says what happened.
            running.clear()
            world.pass(chat, GroupLedgerWorld.H + 62)
            assertEquals(InviteStatus.FAILED, world.row(MA3.B).inviteStatus)
            assertTrue(world.ledger.of("INVITE").isEmpty())
        }

    @Test
    fun `Resend over a leftover failed entry pays exactly once, and a replaced entry pays nothing`() =
        runBlocking {
            val world = GroupLedgerWorld.activeAndInvited(scheduler)
            // E1 ran (the row is failed) and its delete did not: the failed entry is still there.
            world.setRow(MA3.B) { it.copy(inviteStatus = InviteStatus.FAILED) }
            world.ledger.seedNotSent(world.seed("INVITE", world.memberSubject(MA3.B), "epoch:1"), GroupLedgerWorld.H + 60)
            val s = world.screen()

            assertTrue(world.resendInvite(s, MA3.B))

            assertEquals("a Resend over a leftover failed entry does not pay exactly once", 1, world.seam.proposals.size)
            assertEquals(1, world.seam.submits.get())
            val entry = world.entries("INVITE", MA3.B).single()
            assertEquals("the Resend's ids commit replaces the failed entry", "LIVE", entry.state)
            assertEquals(1, entry.superseded.size)
            assertEquals(InviteStatus.SENT, world.row(MA3.B).inviteStatus)

            // The question is raised for one failed state; before Pay, another screen's Resend replaced it and failed again.
            val world2 = GroupLedgerWorld.activeAndInvited(scheduler)
            world2.setRow(MA3.B) { it.copy(inviteStatus = InviteStatus.FAILED) }
            val first = world2.ledger.seedNotSent(world2.seed("INVITE", world2.memberSubject(MA3.B), "epoch:1"), GroupLedgerWorld.H + 60)
            val s2 = world2.screen()
            world2.run(s2) { s2.vm.resendInvite(MA3.GID, MA3.B) }
            assertEquals("GROUP_RESEND_INVITE", world2.question(s2))
            world2.ledger.remove(first)
            world2.ledger.seedNotSent(world2.seed("INVITE", world2.memberSubject(MA3.B), "epoch:1"), GroupLedgerWorld.H + 61)
            world2.confirm(s2)
            assertEquals("a quote shown for one failed state paid for another", 0, world2.seam.proposals.size)
            assertEquals(InviteStatus.FAILED, world2.row(MA3.B).inviteStatus)
        }

    @Test
    fun `E2 killed before its delete - the next pass finds the pair owed, writes nothing and deletes, and Introduce pays once`() =
        runBlocking {
            val world = GroupLedgerWorld(scheduler = scheduler)
            net.register()
            val s = world.screen()
            val chat = world.chatVm()
            world.storedNeverMined()
            MA3.load(s.vm, s.f)
            assertTrue(MA3.callGroupVm(s.vm, s.f, "introduceMembers"))
            world.confirm(s)
            assertEquals(6, world.seam.proposals.size)
            world.walletLists()
            world.ledger.failDelete = true

            world.twoPassesPastExpiry(chat)

            assertEquals("the pairs are not owed again: ${world.admin.ledger().map { it.state }}", 0, world.admin.ledger().size)
            assertEquals(
                "the entries whose delete failed are not kept as failed: ${world.ledger.all()}",
                6,
                world.ledger.of("ANNOUNCE").count { it.state == "NOT_SENT" },
            )
            world.timeline.clear()
            world.pass(chat, world.wallet.scanned + 1)
            assertTrue("the replay wrote the announce records again: ${world.timeline}", "prefs:casGroupAnnounce" !in world.timeline)

            // "Introduce them" over the leftover failed entries pays each pair exactly once.
            world.seam.outcome = { _, ids -> SubmitResult.Success(ids) }
            assertTrue(MA3.callGroupVm(s.vm, s.f, "introduceMembers"))
            world.confirm(s)
            assertEquals("Introduce them over leftover failed entries does not pay each pair once", 12, world.seam.proposals.size)
            assertEquals(6, world.ledger.of("ANNOUNCE").count { it.state == "LIVE" })

            // A pair being paid right now (its record says so) keeps its failed entry.
            world.ledger.failDelete = false
        }
}
