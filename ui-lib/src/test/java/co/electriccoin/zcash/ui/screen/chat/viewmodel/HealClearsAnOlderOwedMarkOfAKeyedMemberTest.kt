package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.screen.chat.model.GroupMessageType
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
 * F8G-X3 (fix-all 2.13.0, train T6b fixup; T6b cross review, must-fix 4, GPT F8-G F3) — completing a cut-off rotation
 * never leaves an owed-key mark on a member the rotation already sent the new key to.
 *
 * Every member is still owed an older key (a fan-out of before that did not reach them all: `pendingKeyEpoch` is set).
 * The creator rotates; the first member is sent the new key and the process dies before the roster is written. At the
 * next group open the rotation is completed (`healInterruptedRotation`): that member has a ledger entry for the new
 * key's message, so it is "covered" and its row is kept AS IT IS, with the OLDER owed mark still on it. While the key
 * message's entry stands, the owed-key pass meets it and pays nothing. But the completion removed the rotation's
 * record, so ten blocks after the key message mined its entry is deleted, and the next group open (under the standing
 * consent) pays that member the same key a second time.
 *
 * Wanted: the completion records a covered member as up to date (no owed mark), as a finished rotation does; the
 * member's key message, and only that one, is paid for, also after it passed the reorg depth.
 *
 * Every address, key, group id and transaction id is synthetic. Nothing here pays.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HealClearsAnOlderOwedMarkOfAKeyedMemberTest {
    private val sendScopes = SendHarnessScopes()
    private val scheduler = TestCoroutineScheduler()

    @Before
    fun setUp() = GroupLedgerTests.setUp(sendScopes, scheduler)

    @After
    fun tearDown() = GroupLedgerTests.tearDown(sendScopes)

    @Test
    fun `a member keyed by the cut-off rotation is not paid the same key again after its message is deep`() =
        runBlocking {
            val world = GroupLedgerWorld(scheduler = scheduler)
            val chat = world.chatVm()
            val members = listOf(MA3.A, MA3.B, MA3.D)
            // Every member still owes the key of before (number 1).
            members.forEach { member -> world.setRow(member) { it.copy(pendingKeyEpoch = 1) } }

            // The rotation to key number 2 dies when the second key message is about to be built.
            world.seam.onProposal = { n, _ -> if (n == 2) throw LedgerProcess.Killed() }
            world.rotate(world.screen())
            world.seam.onProposal = null
            assertEquals("fixture sanity: one key message was paid before the cut", 1, world.seam.submits.get())
            assertEquals("fixture sanity: the creator is still at the old key number", 1, world.epoch())
            world.restart()
            val keyed = world.firstPaid()

            // The next group open completes the rotation and sends the others their key.
            val s = world.screen()
            world.open(s)
            assertEquals("fixture sanity: the rotation was completed", 2, world.epoch())
            assertEquals("fixture sanity: the keyed member was paid once", 1, world.storedOf(GroupMessageType.GROUP_KEY, keyed).size)
            assertNull(
                "the cut-off rotation sent $keyed the new key, and its completion left the member's OLDER owed-key mark " +
                    "on the row: once the key message is deep, the owed-key pass pays the same key again",
                world.row(keyed).pendingKeyEpoch,
            )

            // Every key message mines; then the chain moves far past the reorg depth.
            world.walletLists(mined = { GroupLedgerWorld.H + 1 })
            world.pass(chat, GroupLedgerWorld.H + 2)
            world.pass(chat, GroupLedgerWorld.H + 500)
            world.pass(chat, GroupLedgerWorld.H + 501)
            assertTrue(
                "fixture sanity: the keyed member's mined key message is deleted at depth: ${world.ledger.all()}",
                world.entries("KEY_MEMO", keyed).isEmpty(),
            )

            world.open(world.screen())

            assertEquals(
                "a group open under the standing consent paid $keyed the key a second time",
                1,
                world.storedOf(GroupMessageType.GROUP_KEY, keyed).size,
            )
            for (member in members - keyed) {
                assertEquals("$member was not paid exactly once", 1, world.storedOf(GroupMessageType.GROUP_KEY, member).size)
            }
        }
}
