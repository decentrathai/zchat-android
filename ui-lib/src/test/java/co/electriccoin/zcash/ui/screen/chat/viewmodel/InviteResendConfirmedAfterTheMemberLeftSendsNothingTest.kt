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
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test

/**
 * F8G-X2 (fix-all 2.13.0, train T6b fixup; T6b cross review, must-fix 3, GPT F8-G F1) — an invite Resend confirmed after
 * the member was removed (or left) sends nothing.
 *
 * The creator taps Resend on an "Invite failed" row and the price question opens. Before Pay, the member's row stops
 * being INVITED: the creator removes the member from another screen (a removal that also moves the group to a new key),
 * or the row becomes LEFT another way. Today the invite's "only while the row is what it was at the tap" check reads
 * the row's status AFTER Pay, so a LEFT row passes it: the invite is built, paid, and carries the group's CURRENT key,
 * the one the removal minted so that this person could not read the group any more.
 *
 * Wanted: Pay on an invite Resend sends only to a row that is still INVITED; otherwise nothing is built, paid or
 * written, and the row stays LEFT.
 *
 * Every address, key, group id and transaction id is synthetic. Nothing here pays.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class InviteResendConfirmedAfterTheMemberLeftSendsNothingTest {
    private val sendScopes = SendHarnessScopes()
    private val scheduler = TestCoroutineScheduler()

    @Before
    fun setUp() = GroupLedgerTests.setUp(sendScopes, scheduler)

    @After
    fun tearDown() = GroupLedgerTests.tearDown(sendScopes)

    /** [MA3.X]'s invite never reached the chain: its row says "Invite failed" and the ledger no longer holds it. */
    private suspend fun failedInvite(
        world: GroupLedgerWorld,
        s: GroupScreen,
    ) {
        val chat = world.chatVm()
        world.inviteThatNeverMines(s, MA3.X)
        world.twoPassesPastExpiry(chat)
        assertEquals("fixture sanity: the row says Invite failed", InviteStatus.FAILED, world.row(MA3.X).inviteStatus)
        world.seam.outcome = { _, ids -> SubmitResult.Success(ids) }
    }

    /** Resend on the "Invite failed" row of [MA3.X]: the price question opens and stays open. */
    private suspend fun openResendQuestion(
        world: GroupLedgerWorld,
        s: GroupScreen,
    ) {
        world.run(s) { s.vm.resendInvite(MA3.GID, MA3.X) }
        assertNotNull("fixture sanity: the invite Resend asks its price", world.question(s))
    }

    @Test
    fun `the member is removed from another screen while the question is open - Pay sends no invite and no key`() =
        runBlocking {
            val world = GroupLedgerWorld.twoActive(scheduler)
            val s = world.screen()
            failedInvite(world, s)
            openResendQuestion(world, s)

            val other = world.screen()
            world.remove(other, MA3.X)
            assertEquals("fixture sanity: the member is removed", MemberStatus.LEFT, world.row(MA3.X).status)
            val invitesBefore = world.proposalsOf(GroupMessageType.GROUP_INVITE, MA3.X)
            val paidBefore = world.seam.proposals.size

            world.confirm(s)

            assertEquals(
                "the member was removed while the Resend question was open, and Pay still built an invite for that person " +
                    "with the group's current key (the key the removal made so they could not read the group)",
                invitesBefore,
                world.proposalsOf(GroupMessageType.GROUP_INVITE, MA3.X),
            )
            assertEquals("Pay built something after the removal", paidBefore, world.seam.proposals.size)
            assertEquals("the removed member's row changed", MemberStatus.LEFT, world.row(MA3.X).status)
        }

    @Test
    fun `the row becomes LEFT while the question is open - Pay builds nothing and writes nothing`() =
        runBlocking {
            val world = GroupLedgerWorld.twoActive(scheduler)
            val s = world.screen()
            failedInvite(world, s)
            openResendQuestion(world, s)

            world.setRow(MA3.X) { it.copy(status = MemberStatus.LEFT) }
            val writes = world.store.rosterWrites.size
            val paidBefore = world.seam.proposals.size

            world.confirm(s)

            assertEquals(
                "the member's row is LEFT, and Pay on the invite Resend still built a paid invite for it",
                paidBefore,
                world.seam.proposals.size,
            )
            assertEquals("Pay wrote the roster of a row that is LEFT", writes, world.store.rosterWrites.size)
            assertEquals(MemberStatus.LEFT, world.row(MA3.X).status)
            assertEquals("the LEFT row's invite badge changed", InviteStatus.FAILED, world.row(MA3.X).inviteStatus)
        }

    /** Control, green before and after: a row that is still INVITED at Pay gets exactly one invite. */
    @Test
    fun `control - a row still invited at Pay is sent exactly one invite`() =
        runBlocking {
            val world = GroupLedgerWorld.twoActive(scheduler)
            val s = world.screen()
            failedInvite(world, s)
            val before = world.proposalsOf(GroupMessageType.GROUP_INVITE, MA3.X)

            openResendQuestion(world, s)
            world.confirm(s)

            assertEquals(before + 1, world.proposalsOf(GroupMessageType.GROUP_INVITE, MA3.X))
            assertEquals(InviteStatus.SENT, world.row(MA3.X).inviteStatus)
        }
}
