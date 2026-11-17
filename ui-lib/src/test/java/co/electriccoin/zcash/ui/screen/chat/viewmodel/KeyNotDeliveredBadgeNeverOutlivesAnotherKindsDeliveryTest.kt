package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.screen.chat.model.GroupMessageType
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestCoroutineScheduler
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * F8-G (ZC-112 part 2; the KI-23 hand-off) — a member's "Key not delivered" badge never outlives a delivery of the
 * current key to that member by any route.
 *
 * The current key can reach a member as the creator's key message (KEY_MEMO) or as the answer to the member's own
 * request (KEY_GRANT). They are two different ledger keys for the same member and key number, so settling one does not
 * settle the other, and hiding the badge only while the other is on its way would bring it back when that entry is
 * deleted at depth.
 *
 * Wanted: with a failed key grant, the badge is gone and does not return after (1) the Resend, also once its copy is
 * mined and deleted at depth; (2) a free delivery of the key over the relays; (3) a key message that mined, also once
 * that entry is deleted at depth. The reverse too: a failed key message, and a key grant that mined.
 *
 * Every address, key, group id and transaction id is synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class KeyNotDeliveredBadgeNeverOutlivesAnotherKindsDeliveryTest {
    private val sendScopes = SendHarnessScopes()
    private val scheduler = TestCoroutineScheduler()

    @Before
    fun setUp() = GroupLedgerTests.setUp(sendScopes, scheduler)

    @After
    fun tearDown() = GroupLedgerTests.tearDown(sendScopes)

    private val net = RelayNet()

    private fun failed(world: GroupLedgerWorld, kind: String): EntryView =
        world.ledger.seedNotSent(world.seed(kind, world.memberSubject(MA3.A), "epoch:1"), GroupLedgerWorld.H + 60)

    private suspend fun badgeShown(world: GroupLedgerWorld, s: GroupScreen): Any {
        val badge = world.keyBadges(s)[MA3.A]
        assertTrue("a failed key grant of the current key shows no \"Key not delivered\" badge", badge != null)
        return badge!!
    }

    @Test
    fun `after the Resend the badge is gone and does not return when its copy is deleted at depth`() =
        runBlocking {
            val world = GroupLedgerWorld(scheduler = scheduler)
            failed(world, "KEY_GRANT")
            val s = world.screen()
            val chat = world.chatVm()
            val badge = badgeShown(world, s)

            world.tapResend(s, "resendKeyToMember", MA3.A, badge)
            assertEquals("GROUP_KEY_SYNC", world.question(s))
            world.confirm(s)
            assertEquals("the Resend of a failed key grant pays one key message", 1, world.seam.proposals.size)
            val resent = world.storedOf(GroupMessageType.GROUP_KEY).single()
            assertEquals("the Resend carries the current key", 1, keyEpochOf(resent.memo.memo))
            assertTrue("the badge is still shown after the Resend", world.keyBadges(s).isEmpty())
            assertTrue("a failed entry of the member is left: ${world.ledger.all()}", world.ledger.all().none { it.state == "NOT_SENT" })

            world.walletLists(mined = { GroupLedgerWorld.H + 100 })
            world.pass(chat, GroupLedgerWorld.H + 101)
            world.pass(chat, GroupLedgerWorld.H + 600)
            assertTrue("the mined copy's entry is kept past the reorg depth: ${world.ledger.all()}", world.ledger.all().isEmpty())
            assertTrue("the badge returns when the Resend's entry is deleted", world.keyBadges(s).isEmpty())
        }

    @Test
    fun `after a free relay delivery of the key the badge is gone`() =
        runBlocking {
            val world = GroupLedgerWorld(scheduler = scheduler)
            failed(world, "KEY_GRANT")
            val s = world.screen()
            badgeShown(world, s)
            // The member is owed the key for another reason and is reachable over the relays now.
            world.setRow(MA3.A) { it.copy(pendingKeyEpoch = 1) }
            world.admin.base.nostrKeys[MA3.A] = MA3.NPUB.getValue(MA3.A)
            net.register()

            world.open(s)

            assertEquals(
                "fixture sanity: the key went out over the relays",
                1,
                net.ofType(GroupMessageType.GROUP_KEY, MA3.NPUB.getValue(MA3.A)).size,
            )
            assertEquals(0, world.seam.proposals.size)
            assertTrue("the badge outlives a free delivery of the key", world.keyBadges(s).isEmpty())
            assertTrue("the failed grant's entry is kept: ${world.ledger.all()}", world.ledger.all().isEmpty())
        }

    @Test
    fun `after a key message that mined the badge of a failed grant is gone and does not return at depth, and the reverse`() =
        runBlocking {
            for ((failedKind, minedKind) in listOf("KEY_GRANT" to "KEY_MEMO", "KEY_MEMO" to "KEY_GRANT")) {
                GroupLedgerTests.resetProcess()
                val world = GroupLedgerWorld(scheduler = scheduler)
                failed(world, failedKind)
                val s = world.screen()
                val chat = world.chatVm()
                assertTrue("[$failedKind] a failed entry of the current key shows no badge", world.keyBadges(s)[MA3.A] != null)
                val other = world.seed(minedKind, world.memberSubject(MA3.A), "epoch:1")
                assertTrue("[$failedKind] the badge is shown while the other kind's attempt is on its way", world.keyBadges(s).isEmpty())
                world.walletListsSeeded(other, MA3.A, mined = GroupLedgerWorld.H + 100)

                world.pass(chat, GroupLedgerWorld.H + 101)
                assertEquals(
                    "[$failedKind] the $minedKind entry is not settled as mined",
                    "MINED",
                    world.ledger.one(minedKind)?.state,
                )
                assertTrue(
                    "[$failedKind] the failed entry outlives the mined $minedKind: ${world.ledger.all()}",
                    world.ledger.of(failedKind).isEmpty(),
                )
                assertTrue(world.keyBadges(s).isEmpty())

                world.pass(chat, GroupLedgerWorld.H + 600)
                assertTrue("[$failedKind] the mined entry is kept past the reorg depth", world.ledger.all().isEmpty())
                assertTrue("[$failedKind] the badge returns when the mined entry is deleted at depth", world.keyBadges(s).isEmpty())
                assertEquals(0, world.seam.proposals.size)
            }
        }
}
