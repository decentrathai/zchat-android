package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.screen.chat.model.GroupMessageType
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
 * F8-G G5 (ZC-082) — a key rotation that was cut off is completed at the next group open.
 *
 * A rotation sends each member the new key and only then writes the roster and moves the creator to the new key
 * number. When the process dies after the first member was sent the key, the creator's phone keeps the old number:
 * it goes on sending under a key that member has left behind, a removed member is still ACTIVE on this phone, and
 * nobody is marked as owed the new key. (The new key itself is on the phone since ZC-778: that part is not the bug.)
 *
 * Wanted: the rotation's record is written before anyone is sent the key; a new screen over the same store completes
 * it: the key number moves, the removed member is LEFT with the removal floor, the members not yet sent the key are owed
 * it (and get it once), and a member who already has a key message of that number recorded (on its way or mined) is
 * not owed and not paid for again, also after that message passed the reorg depth. A completion whose roster write is
 * refused writes nothing else and sends nothing. A finished rotation leaves no record.
 *
 * Every address, key, group id and transaction id is synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class InterruptedRotationLeavesNoOrphanKeyTest {
    private val sendScopes = SendHarnessScopes()
    private val scheduler = TestCoroutineScheduler()

    @Before
    fun setUp() = GroupLedgerTests.setUp(sendScopes, scheduler)

    @After
    fun tearDown() = GroupLedgerTests.tearDown(sendScopes)

    /** A rotation (or the removal of [kicked]) whose process dies when the second key message is about to be built. */
    private suspend fun cutOff(world: GroupLedgerWorld, s: GroupScreen, kicked: String? = null) {
        world.seam.onProposal = { n, _ -> if (n == 2) throw LedgerProcess.Killed() }
        if (kicked == null) world.rotate(s) else world.remove(s, kicked)
        world.seam.onProposal = null
        assertEquals("fixture sanity: one key message was paid before the cut", 1, world.seam.submits.get())
        assertEquals("fixture sanity: the creator is still at the old key number", 1, world.epoch())
        assertTrue("fixture sanity: the new key is on this phone (ZC-778)", world.hasKey(2))
        world.restart()
    }

    @Test
    fun `a new screen completes the rotation, owes the key only to the members not sent it, and pays each once`() =
        runBlocking {
            val world = GroupLedgerWorld(scheduler = scheduler)
            cutOff(world, world.screen())
            val keyed = world.firstPaid()
            val unkeyed = listOf(MA3.A, MA3.B, MA3.D) - keyed

            val s2 = world.screen()
            world.open(s2)

            assertEquals(
                "a rotation was cut off after $keyed was sent the new key, and after a new group open the creator is " +
                    "still at the old key number: it goes on sending under a key that member left behind",
                2,
                world.epoch(),
            )
            assertEquals("the keyed member was paid for again", 1, world.storedOf(GroupMessageType.GROUP_KEY, keyed).size)
            assertNull("the keyed member is owed the key it already has", world.row(keyed).pendingKeyEpoch)
            for (member in unkeyed) {
                assertEquals("$member was not sent the key exactly once", 1, world.storedOf(GroupMessageType.GROUP_KEY, member).size)
            }
            assertTrue("the rotation's record is left behind: ${world.ledger.all()}", world.ledger.of("ROTATION").isEmpty())

            // A second open pays nothing more, and a new rotation takes the next number.
            val before = world.seam.stored.size
            world.open(s2)
            assertEquals("a second open paid again", before, world.seam.stored.size)
            world.rotate(s2)
            assertEquals("the next rotation does not advance to the next number", 3, world.epoch())
        }

    @Test
    fun `the record and the key exist before the first member is sent the key, and a finished rotation leaves no record`() =
        runBlocking {
            val world = GroupLedgerWorld(scheduler = scheduler)
            val s = world.screen()
            var recordAtFirstMemo: List<String>? = null
            var keyAtFirstMemo = false
            world.seam.onProposal = { n, _ ->
                if (n == 1) {
                    recordAtFirstMemo = world.ledger.of("ROTATION").map { it.identity }
                    keyAtFirstMemo = world.hasKey(2)
                }
            }

            world.rotate(s)

            assertEquals("no record of the rotation exists when the first key message is built", listOf("epoch:2"), recordAtFirstMemo)
            assertTrue("the new key is not on this phone when the first key message is built", keyAtFirstMemo)
            assertEquals(2, world.epoch())
            assertTrue("a finished rotation leaves its record: ${world.ledger.all()}", world.ledger.of("ROTATION").isEmpty())
        }

    @Test
    fun `a cut-off removal is completed - the member is LEFT with the floor and is never sent the key`() =
        runBlocking {
            val world = GroupLedgerWorld(scheduler = scheduler)
            cutOff(world, world.screen(), kicked = MA3.D)
            assertEquals("fixture sanity: the removed member is still ACTIVE after the cut", MemberStatus.ACTIVE, world.row(MA3.D).status)

            world.open(world.screen())

            assertEquals(
                "the removed member is still ACTIVE on this phone after the group was opened again",
                MemberStatus.LEFT,
                world.row(MA3.D).status,
            )
            assertEquals(2, world.epoch())
            assertEquals("the removal floor is not set", 2, world.admin.base.floors[MA3.GID])
            assertEquals("the removed member was sent the new key", 0, world.proposalsOf(GroupMessageType.GROUP_KEY, MA3.D))
            assertNull("the removed member is owed the new key", world.row(MA3.D).pendingKeyEpoch)
        }

    @Test
    fun `a member whose key message mined and passed the reorg depth is not paid for again by the completion`() =
        runBlocking {
            val world = GroupLedgerWorld(scheduler = scheduler)
            val chat = world.chatVm()
            cutOff(world, world.screen())
            val keyed = world.firstPaid()
            world.walletLists(mined = { GroupLedgerWorld.H + 1 })
            // Mined, and then far past the reorg depth, before anybody opens the group again.
            world.pass(chat, GroupLedgerWorld.H + 2)
            world.pass(chat, GroupLedgerWorld.H + 500)
            world.pass(chat, GroupLedgerWorld.H + 501)

            world.open(world.screen())

            assertEquals(2, world.epoch())
            assertEquals(
                "the member whose key message mined was paid for a second time",
                1,
                world.storedOf(GroupMessageType.GROUP_KEY, keyed).size,
            )
            assertNull(world.row(keyed).pendingKeyEpoch)
        }

    @Test
    fun `a completion whose roster write is refused writes nothing else and sends the removed member nothing`() =
        runBlocking {
            val world = GroupLedgerWorld(scheduler = scheduler)
            cutOff(world, world.screen(), kicked = MA3.D)
            assertEquals("this build wrote no record of the rotation", 1, world.ledger.of("ROTATION").size)
            world.store.refuseDurableRoster = true
            val before = world.seam.proposals.size

            world.open(world.screen())

            assertEquals("the key number moved although the roster was not saved", 1, world.epoch())
            assertEquals("the record is gone although the rotation is not completed", 1, world.ledger.of("ROTATION").size)
            assertEquals("a key was sent in a call whose roster write was refused", before, world.seam.proposals.size)

            world.store.refuseDurableRoster = false
            world.open(world.screen())
            assertEquals("the next open completes it", 2, world.epoch())
            assertEquals(MemberStatus.LEFT, world.row(MA3.D).status)
        }

    @Test
    fun `a record whose key was never stored is deleted and changes nothing`() =
        runBlocking {
            val world = GroupLedgerWorld(scheduler = scheduler)
            val rotation = world.seed("ROTATION", MA3.GID, "epoch:7", txIds = emptyList(), payload = "{\"epoch\":7}")
            assertEquals("ROTATION", rotation.kind)

            world.open(world.screen())

            assertEquals(1, world.epoch())
            assertTrue("a record with no key is kept: ${world.ledger.all()}", world.ledger.of("ROTATION").isEmpty())
            assertEquals(0, world.seam.proposals.size)
        }
}
