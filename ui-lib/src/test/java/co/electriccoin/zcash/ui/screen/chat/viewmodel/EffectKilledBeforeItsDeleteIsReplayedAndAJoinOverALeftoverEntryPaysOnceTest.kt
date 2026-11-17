package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.common.model.SubmitResult
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * F8 R21 (ZC-112; review finding G4-1) — a visibility effect is replayable, and a Join over a leftover failed entry
 * pays once.
 *
 * The pass writes the effect (here: the group's waiting record) and then deletes the entry. A kill, or a failed write,
 * between the two must neither write the effect a second time nor strand the entry. And while such a leftover entry
 * stands, the user's Join must still be able to pay: an automatic attempt meets "pending" on it, the Join is the quoted
 * tap that retries exactly that failed state.
 *
 * Wanted: a later pass that finds the record already there writes nothing and deletes the entry; a delete that fails is
 * tried again at every pass, with no second record. A Join over the leftover entry pays exactly one reply, and the new
 * transaction's ids replace the failed entry.
 *
 * Every address, key, group id and transaction id is synthetic.
 */
class EffectKilledBeforeItsDeleteIsReplayedAndAJoinOverALeftoverEntryPaysOnceTest {
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
    fun `a kill between the record and the delete is replayed with no second write`() =
        runBlocking {
            val (world, vm) = invited()
            world.joinPaidNeverMined(vm)
            val mark = world.prefs.mark()
            world.ledger.beforeMethod("delete") { throw LedgerProcess.Killed() }

            runCatching { world.twoPassesPastExpiry(vm) }

            assertTrue("the record was written before the kill", world.base.needsJoin.containsKey(GROUP))
            assertEquals("the entry is still there, proven", "NOT_SENT", world.ledger.one("GROUP_ACCEPT")?.state)
            assertEquals(1, world.recordWrites(mark))

            world.ledger.clearHooks()
            world.restart()
            AutoSpendVm.setHistoryRead(true)
            val again = world.openVm()
            world.pass(again, world.wallet.scanned + 1)
            assertEquals("the replay writes no second record", 1, world.recordWrites(mark))
            assertTrue("and deletes the entry: ${world.ledger.all()}", world.ledger.of("GROUP_ACCEPT").isEmpty())
        }

    @Test
    fun `a delete that fails is repeated every pass, and a Join over the leftover entry pays exactly once`() =
        runBlocking {
            val (world, vm) = invited()
            world.joinPaidNeverMined(vm)
            val mark = world.prefs.mark()
            world.ledger.failDelete = true

            world.twoPassesPastExpiry(vm)
            world.pass(vm, world.wallet.scanned + 1)
            world.pass(vm, world.wallet.scanned + 1)

            val leftover = world.ledger.one("GROUP_ACCEPT")
            assertEquals("the entry stays until its delete sticks", "NOT_SENT", leftover?.state)
            assertEquals("the record is never written twice", 1, world.recordWrites(mark))
            assertTrue("the delete is tried again at every pass", world.ledger.calls.count { it == "delete" } >= 3)

            // The user taps Join while the leftover entry stands.
            world.seam.outcome = { _, ids -> SubmitResult.Success(ids) }
            assertTrue(GroupInviteDriver.join(vm, GROUP))

            assertEquals(
                "a Join over a leftover failed entry must pay its one reply (without the retry marker it would answer " +
                    "\"pending\" for good and the Join would pay nothing)",
                2,
                world.seam.submits.get(),
            )
            val now = world.ledger.one("GROUP_ACCEPT")!!
            assertEquals("LIVE", now.state)
            assertEquals("the failed reply's id is kept as superseded", leftover!!.txIds, now.superseded)
        }
}
