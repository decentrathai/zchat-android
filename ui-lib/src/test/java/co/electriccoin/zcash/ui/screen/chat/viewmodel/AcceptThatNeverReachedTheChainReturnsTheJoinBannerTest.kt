package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.common.model.SubmitResult
import co.electriccoin.zcash.ui.screen.chat.model.ZMSGGroupProtocol
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * F8 R8 (ZC-112) — a Join whose paid reply never reached the chain is offered at Join again.
 *
 * The user taps Join on a group invite; the "I joined" reply cannot go over the relays, so it is paid on-chain. When
 * that transaction is stored but never mined, today only a log line says so: the inviter never learns that this phone
 * joined, and nothing on the screen offers the reply again.
 *
 * Wanted: once the chain proves the reply can no longer be mined, the group's waiting record is written again ("the
 * relays did not take it", the same reply), so the Join banner returns with its price. Nothing is paid by itself. Join
 * then sends exactly one new reply. The entry is deleted only after the record's write; a second pass writes nothing
 * more. A group that was left meanwhile gets no record.
 *
 * Every address, key, group id and transaction id is synthetic.
 */
class AcceptThatNeverReachedTheChainReturnsTheJoinBannerTest {
    private val sendScopes = SendHarnessScopes()

    @Before
    fun setUp() = LedgerTests.setUp(sendScopes)

    @After
    fun tearDown() = LedgerTests.tearDown(sendScopes)

    private companion object {
        const val GROUP = "GSYNTHF8LEDG"
    }

    /** A phone invited to [GROUP] by its contact, whose "I joined" reply cannot go over the relays: it waits for Join. */
    private suspend fun invited(): Pair<LedgerWorld, ChatViewModel> {
        val world =
            LedgerWorld(
                peer = "usyntheticf8ledgerinviter01",
                // A Shielded chat with the inviter: no Tunnel bootstrap of its own rides on a block.
                mode = co.electriccoin.zcash.ui.screen.chat.model.ConversationMode.VAULT,
                exchangeComplete = true,
                accountSource = SelectedAccountFeed.create(),
            )
        val vm = world.openVm()
        GroupInviteDriver.processInvite(vm, GROUP, GroupInviteDriver.plaintextInvite(world.peer))
        HandshakeHarness.awaitIdle(vm)
        assertTrue("fixture sanity: the reply waits for Join", world.base.needsJoin.containsKey(GROUP))
        return world to vm
    }

    /** Join, whose paid reply is stored and never mined; the wallet lists it. */
    private suspend fun LedgerWorld.joinPaidNeverMined(vm: ChatViewModel) {
        storedNeverMined()
        assertTrue("fixture sanity: Join released the reply", GroupInviteDriver.join(vm, GROUP))
        assertEquals("fixture sanity: the reply was paid on-chain once", 1, seam.submits.get())
        assertTrue("fixture sanity: the waiting record is gone after Join", !base.needsJoin.containsKey(GROUP))
        walletListsStoredUnmined()
    }

    private fun LedgerWorld.recordWrites(since: Int): Int =
        prefs.writesSince(since).count { it.name == "setGroupInviteNeedsJoin" && it.args[1] != null }

    @Test
    fun `the waiting record comes back after the proof, nothing is paid by itself, and Join pays exactly one new reply`() =
        runBlocking {
            val (world, vm) = invited()
            world.joinPaidNeverMined(vm)
            val mark = world.prefs.mark()
            world.timeline.clear()

            world.twoPassesPastExpiry(vm)

            val record = world.base.needsJoin[GROUP]
            assertTrue(
                "the paid reply was stored, never mined and is past its expiry on two passes, but the group has no " +
                    "waiting record again: the Join banner does not return and the inviter never learns of the join",
                record != null,
            )
            assertTrue("the record says the relays did not take it: $record", record!!.contains("relay"))
            assertEquals("the record is written once", 1, world.recordWrites(mark))
            assertTrue("the entry is deleted after the record's write: ${world.timeline}", world.ledger.of("GROUP_ACCEPT").isEmpty())
            assertTrue(
                "the order is commit, record, delete: ${world.timeline}",
                world.timeline.indexOf("ledger:markNotSent") < world.timeline.indexOf("prefs:setGroupInviteNeedsJoin") &&
                    world.timeline.indexOf("prefs:setGroupInviteNeedsJoin") < world.timeline.lastIndexOf("ledger:delete"),
            )

            // Nothing is paid by itself, also under the standing consent.
            repeat(3) { world.blockTick(vm, world.wallet.scanned + it) }
            world.pass(vm, world.wallet.scanned + 1)
            assertEquals("no reply is paid without Join", 1, world.seam.proposals.get())
            assertEquals("a later pass writes no second record", 1, world.recordWrites(mark))

            // Join is the tap that pays: exactly one new reply.
            world.seam.outcome = { _, ids -> SubmitResult.Success(ids) }
            assertTrue(GroupInviteDriver.join(vm, GROUP))
            assertEquals("Join pays exactly one new reply", 2, world.seam.submits.get())
        }

    @Test
    fun `a group left meanwhile gets no record and its entry is dropped`() =
        runBlocking {
            val (world, vm) = invited()
            world.joinPaidNeverMined(vm)
            val info = ZMSGGroupProtocol.deserializeGroupInfo(world.base.groupInfo.getValue(GROUP))!!
            world.base.groupInfo[GROUP] = ZMSGGroupProtocol.serializeGroupInfo(info.copy(isActive = false))

            world.twoPassesPastExpiry(vm)

            assertTrue("a left group's reply is not offered again", !world.base.needsJoin.containsKey(GROUP))
            assertTrue("this build keeps no record of the paid reply's attempt", world.ledger.exists)
            assertTrue("its entry is dropped: ${world.ledger.all()}", world.ledger.of("GROUP_ACCEPT").isEmpty())
        }
}
