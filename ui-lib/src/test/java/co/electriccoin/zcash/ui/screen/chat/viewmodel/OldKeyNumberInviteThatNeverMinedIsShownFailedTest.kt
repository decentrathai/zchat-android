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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * F8G-X4 (fix-all 2.13.0, train T6b fixup; T6b cross review, must-fix 5, GPT F8-G F5 and Grok F8-G part b F2) — an
 * invite that never reached the chain is shown as failed, also when the group moved to a new key meanwhile.
 *
 * The creator adds a member; the invite is paid on-chain under key number 1 and its transaction is stored but never
 * mined. Before the chain can judge it, the creator rotates the key. Today the history pass deletes the invite's
 * ledger entry at once, because its key number is no longer the group's (`groupLedgerIdentityStands`, INVITE). When the
 * invite then expires, nothing is left to notice it: the row says "Invited" for good, with no Resend, and it keeps
 * counting as a key holder, so every later rotation pays a key message for a person who never got the invite.
 *
 * Wanted: an unresolved invite stands until the chain has judged it, whatever the key number is now. Proven not sent,
 * the row becomes "Invite failed" with its Resend (which sends the CURRENT key), and no later rotation pays for it.
 *
 * Every address, key, group id and transaction id is synthetic. Nothing here pays.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OldKeyNumberInviteThatNeverMinedIsShownFailedTest {
    private val sendScopes = SendHarnessScopes()
    private val scheduler = TestCoroutineScheduler()

    @Before
    fun setUp() = GroupLedgerTests.setUp(sendScopes, scheduler)

    @After
    fun tearDown() = GroupLedgerTests.tearDown(sendScopes)

    @Test
    fun `an invite of key number 1 that never mined after a rotation to 2 becomes Invite failed and is no key holder`() =
        runBlocking {
            val world = GroupLedgerWorld.twoActive(scheduler)
            val s = world.screen()
            val chat = world.chatVm()
            world.inviteThatNeverMines(s, MA3.X)
            assertEquals("fixture sanity: the invite carried key number 1", 1, world.epoch())

            // The key is rotated while the invite is unresolved; a pass reads the ledger in between.
            world.seam.outcome = { _, ids -> SubmitResult.Success(ids) }
            world.rotate(s)
            assertEquals("fixture sanity: the group is at key number 2", 2, world.epoch())
            world.pass(chat, GroupLedgerWorld.H + 1)
            assertEquals(
                "the invite's transaction is still unresolved, and its ledger entry was dropped because the group moved " +
                    "to a new key number: nothing can notice any more that it never reached the chain",
                1,
                world.entries("INVITE", MA3.X).size,
            )

            // The invite expires and is proven not sent.
            world.walletLists(mined = { n -> if (n == 1) null else GroupLedgerWorld.H + 1 })
            world.twoPassesPastExpiry(chat)

            assertEquals(
                "an invite that never reached the chain still says \"Invited\" after the rotation: no Resend is offered",
                InviteStatus.FAILED,
                world.row(MA3.X).inviteStatus,
            )
            assertEquals("the member is still an invited member", MemberStatus.INVITED, world.row(MA3.X).status)
            assertTrue("the invite's entry is left behind: ${world.ledger.all()}", world.entries("INVITE", MA3.X).isEmpty())

            // A later rotation pays no key message for the member who never got the invite.
            val keysBefore = world.proposalsOf(GroupMessageType.GROUP_KEY, MA3.X)
            world.rotate(s)
            assertEquals(
                "a rotation paid a key message for a member whose invite never reached the chain",
                keysBefore,
                world.proposalsOf(GroupMessageType.GROUP_KEY, MA3.X),
            )

            // Resend asks its price and sends the CURRENT key once.
            val invitesBefore = world.proposalsOf(GroupMessageType.GROUP_INVITE, MA3.X)
            assertTrue("Resend asks no price", world.resendInvite(s, MA3.X))
            assertEquals(
                "Resend did not pay exactly one invite",
                invitesBefore + 1,
                world.proposalsOf(GroupMessageType.GROUP_INVITE, MA3.X),
            )
        }

    /** Control, green before and after: an invite of an older key number that mined leaves the row as sent. */
    @Test
    fun `control - an invite of key number 1 that mined after a rotation stays sent`() =
        runBlocking {
            val world = GroupLedgerWorld.twoActive(scheduler)
            val s = world.screen()
            val chat = world.chatVm()
            world.inviteThatNeverMines(s, MA3.X)
            world.seam.outcome = { _, ids -> SubmitResult.Success(ids) }
            world.rotate(s)

            world.walletLists(mined = { GroupLedgerWorld.H + 1 })
            world.pass(chat, GroupLedgerWorld.H + 2)
            world.twoPassesPastExpiry(chat)

            assertEquals(InviteStatus.SENT, world.row(MA3.X).inviteStatus)
            assertEquals(MemberStatus.INVITED, world.row(MA3.X).status)
        }
}
