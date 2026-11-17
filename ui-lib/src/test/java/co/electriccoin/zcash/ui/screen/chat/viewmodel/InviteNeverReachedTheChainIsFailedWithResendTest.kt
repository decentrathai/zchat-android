package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.common.model.SubmitResult
import co.electriccoin.zcash.ui.screen.chat.model.GroupMessageType
import co.electriccoin.zcash.ui.screen.chat.model.InviteStatus
import co.electriccoin.zcash.ui.screen.chat.model.MemberStatus
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
 * F8-G G3 (ZC-112 part 2) — an invite that never reached the chain is shown as failed, with the existing Resend.
 *
 * The creator adds a member; the invite is paid on-chain. When that transaction is stored but never mined, the member
 * row says "Invited" for good: no Resend is offered (a sent invite has none), and every later key rotation pays a key
 * message for a member who never got the invite.
 *
 * Wanted: once the chain proves the invite can no longer be mined, the row becomes "Invite failed" in ONE checked roster
 * write under the group's lock, and only then is the attempt's entry deleted. No banner is raised. The row stops
 * counting as a key holder, so a rotation sends it nothing. Resend still asks its price and pays exactly once; a
 * second Resend while that one is unresolved pays nothing and writes no status. A member who joined meanwhile is
 * left alone.
 *
 * Every address, key, group id and transaction id is synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class InviteNeverReachedTheChainIsFailedWithResendTest {
    private val sendScopes = SendHarnessScopes()
    private val scheduler = TestCoroutineScheduler()

    @Before
    fun setUp() = GroupLedgerTests.setUp(sendScopes, scheduler)

    @After
    fun tearDown() = GroupLedgerTests.tearDown(sendScopes)

    private fun world() = GroupLedgerWorld.twoActive(scheduler)

    @Test
    fun `the row becomes Invite failed in one roster write and then the entry is deleted, with no banner`() =
        runBlocking {
            val world = world()
            val s = world.screen()
            val chat = world.chatVm()
            world.inviteThatNeverMines(s, MA3.X)
            val writesBefore = world.store.rosterWrites.size
            world.timeline.clear()

            world.twoPassesPastExpiry(chat)

            assertEquals(
                "the invite was stored, never mined and is past its expiry on two passes, but the row still says " +
                    "\"Invited\" (ledger: ${world.ledger.all()}): no Resend is offered and rotations keep paying for this member",
                InviteStatus.FAILED,
                world.row(MA3.X).inviteStatus,
            )
            assertEquals("the member is still an invited member", MemberStatus.INVITED, world.row(MA3.X).status)
            assertEquals("the failed badge is one roster write", 1, world.store.rosterWrites.size - writesBefore)
            assertEquals("the write is a checked one", "saveGroupMembersDurably", world.store.rosterWrites.last())
            assertEquals("a roster write was made without the group's lock", 0, world.store.unlockedRosterWrites.get())
            assertTrue("the entry is deleted after the row's write: ${world.timeline}", world.ledger.of("INVITE").isEmpty())
            assertTrue(
                "the order is commit, row, delete: ${world.timeline}",
                world.timeline.indexOf("ledger:markNotSent") < world.timeline.indexOf("prefs:saveGroupMembersDurably") &&
                    world.timeline.indexOf("prefs:saveGroupMembersDurably") < world.timeline.lastIndexOf("ledger:delete"),
            )
            assertNull("the failed invite raises a banner (its text says \"check your balance\")", s.vm.groupSendEvent.value)

            // A later pass writes nothing more.
            world.pass(chat, world.wallet.scanned + 1)
            assertEquals("a later pass writes the row again", 1, world.store.rosterWrites.size - writesBefore)
            assertEquals("the invite was paid again with no tap", 1, world.seam.proposals.size)
        }

    @Test
    fun `the failed row is no key holder for a rotation, and Resend asks its price and pays exactly once`() =
        runBlocking {
            val world = world()
            val s = world.screen()
            val chat = world.chatVm()
            world.inviteThatNeverMines(s, MA3.X)
            world.twoPassesPastExpiry(chat)
            world.seam.outcome = { _, ids -> SubmitResult.Success(ids) }

            world.rotate(s)
            assertEquals(
                "a rotation paid a key message for a member whose invite never reached the chain",
                0,
                world.proposalsOf(GroupMessageType.GROUP_KEY, MA3.X) + world.proposalsOf(GroupMessageType.GROUP_KICK, MA3.X),
            )
            val before = world.seam.proposals.size

            assertTrue("Resend asks no price", world.resendInvite(s, MA3.X))
            assertEquals("Resend pays exactly one invite", before + 1, world.seam.proposals.size)
            assertEquals("the row is shown as sent again", InviteStatus.SENT, world.row(MA3.X).inviteStatus)
            assertEquals(
                "the new invite's attempt is recorded: ${world.ledger.all()}",
                "LIVE",
                world.entries("INVITE", MA3.X).singleOrNull()?.state,
            )

            // A second Resend while the first one's transaction is unresolved.
            val writes = world.store.rosterWrites.size
            world.resendInvite(s, MA3.X)
            assertEquals("a second Resend paid for the same invite again", before + 1, world.seam.proposals.size)
            assertEquals("the second Resend wrote a status", writes, world.store.rosterWrites.size)
            assertEquals(InviteStatus.SENT, world.row(MA3.X).inviteStatus)
        }

    @Test
    fun `a member who joined meanwhile is left alone and the entry is dropped`() =
        runBlocking {
            val world = world()
            val s = world.screen()
            val chat = world.chatVm()
            world.inviteThatNeverMines(s, MA3.X)
            world.setRow(MA3.X) { it.copy(status = MemberStatus.ACTIVE) }
            val writesBefore = world.store.rosterWrites.size

            world.twoPassesPastExpiry(chat)

            assertEquals("a joined member's row was rewritten", writesBefore, world.store.rosterWrites.size)
            assertEquals(InviteStatus.SENT, world.row(MA3.X).inviteStatus)
            assertTrue("this build keeps no record of a paid invite's attempt", world.ledger.exists)
            assertTrue("the entry of a joined member is dropped: ${world.ledger.all()}", world.ledger.of("INVITE").isEmpty())
        }
}
