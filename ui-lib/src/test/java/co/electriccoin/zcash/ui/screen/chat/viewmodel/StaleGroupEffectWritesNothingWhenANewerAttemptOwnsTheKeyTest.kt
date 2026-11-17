package co.electriccoin.zcash.ui.screen.chat.viewmodel

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
 * F8-G G10 (ZC-112 part 2; GPT-3 #4, #7) — the "Invite failed" write of a proven-dead invite never lands on a newer
 * attempt, is finished by the next process after a kill, and keeps its entry when the roster does not take it.
 *
 * The history pass first commits "proven not sent" and then writes the visible state. Between the two the creator may
 * tap Resend: the new attempt replaces the entry and the row is "Invited" again, for the new invite. The effect must
 * read the entry again under the group's lock and write nothing then. A process killed between the commit and the
 * effect leaves a failed entry with no badge: the next process writes the badge once and deletes the entry. A roster
 * write that fails keeps the entry, and the next pass writes it.
 *
 * Every address, key, group id and transaction id is synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StaleGroupEffectWritesNothingWhenANewerAttemptOwnsTheKeyTest {
    private val sendScopes = SendHarnessScopes()
    private val scheduler = TestCoroutineScheduler()

    @Before
    fun setUp() = GroupLedgerTests.setUp(sendScopes, scheduler)

    @After
    fun tearDown() = GroupLedgerTests.tearDown(sendScopes)

    private fun world() = GroupLedgerWorld.twoActive(scheduler)

    @Test
    fun `a Resend between the commit and the effect makes the effect write no roster row`() =
        runBlocking {
            val world = world()
            val s = world.screen()
            val chat = world.chatVm()
            world.inviteThatNeverMines(s, MA3.X)
            val writes = world.store.rosterWrites.size
            // Right after the commit: a Resend's ids commit replaces the entry (a new attempt, on its way), row "Invited".
            world.ledger.afterMethod("markNotSent") {
                val failed = world.entries("INVITE", MA3.X).single()
                world.ledger.remove(failed)
                world.seed("INVITE", world.memberSubject(MA3.X), "epoch:1")
            }

            world.twoPassesPastExpiry(chat)

            assertTrue("this build proves nothing about a paid invite", world.ledger.calls.contains("markNotSent"))
            assertEquals("the stale effect rewrote the row of the newer attempt", writes, world.store.rosterWrites.size)
            assertEquals(InviteStatus.SENT, world.row(MA3.X).inviteStatus)
            assertEquals("the newer attempt's entry was touched", "LIVE", world.entries("INVITE", MA3.X).single().state)
        }

    @Test
    fun `a kill between the commit and the effect is finished by the next process, once`() =
        runBlocking {
            val world = world()
            val s = world.screen()
            val chat = world.chatVm()
            world.inviteThatNeverMines(s, MA3.X)
            val writes = world.store.rosterWrites.size
            world.ledger.afterMethod("markNotSent") { throw LedgerProcess.Killed() }

            runCatching { world.twoPassesPastExpiry(chat) }

            assertEquals(
                "the entry is not the failed state after the commit: ${world.ledger.all()}",
                "NOT_SENT",
                world.entries("INVITE", MA3.X).singleOrNull()?.state,
            )
            assertEquals("the row changed although the process died before the effect", InviteStatus.SENT, world.row(MA3.X).inviteStatus)

            world.ledger.clearHooks()
            world.restart()
            val chat2 = world.chatVm()
            world.pass(chat2, world.wallet.scanned + 1)
            world.pass(chat2, world.wallet.scanned + 1)

            assertEquals("the next process did not write the badge", InviteStatus.FAILED, world.row(MA3.X).inviteStatus)
            assertEquals("the badge was written more than once", 1, world.store.rosterWrites.size - writes)
            assertTrue("the entry is deleted after the effect", world.ledger.of("INVITE").isEmpty())
            assertEquals(0, world.store.unlockedRosterWrites.get())
        }

    @Test
    fun `a roster write that fails keeps the entry, and the next pass writes the badge`() =
        runBlocking {
            val world = world()
            val s = world.screen()
            val chat = world.chatVm()
            world.inviteThatNeverMines(s, MA3.X)
            world.store.refuseDurableRoster = true

            world.twoPassesPastExpiry(chat)

            assertEquals(
                "the entry is gone although its badge was not saved: ${world.ledger.all()}",
                "NOT_SENT",
                world.entries("INVITE", MA3.X).singleOrNull()?.state,
            )
            assertEquals(InviteStatus.SENT, world.row(MA3.X).inviteStatus)

            world.store.refuseDurableRoster = false
            world.pass(chat, world.wallet.scanned + 1)
            assertEquals(InviteStatus.FAILED, world.row(MA3.X).inviteStatus)
            assertTrue(world.ledger.of("INVITE").isEmpty())
            assertEquals("the invite was paid again with no tap", 1, world.seam.proposals.size)
        }
}
