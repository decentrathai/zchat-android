package co.electriccoin.zcash.ui.screen.chat.viewmodel

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * F8 R13 (ZC-112; review findings GPT 3, GPT-3 #3) — while an earlier attempt of the same key is unresolved, a new
 * attempt is PENDING: it builds nothing, stamps nothing and changes nothing.
 *
 * Today an unresolved attempt is invisible. After the hour of the paid record a forced bootstrap builds a second key
 * exchange, an owed KEX reads "already sent" and clears its owed mark, and a key reply whose marker was re-armed is
 * paid again.
 *
 * Wanted: with an entry of the key that is LIVE, MINED and not yet deep, or NOT_SENT, every automatic attempt builds no
 * proposal, writes no paid record, leaves every obligation exactly as it was (the owed mark stays owed, the "acked"
 * marker is not written), writes nothing to the ledger, and nothing it wrote before is undone.
 *
 * Every address, key and transaction id is synthetic.
 */
class AliveAttemptIsPendingAndChangesNothingTest {
    private val sendScopes = SendHarnessScopes()

    @Before
    fun setUp() = LedgerTests.setUp(sendScopes)

    @After
    fun tearDown() = LedgerTests.tearDown(sendScopes)

    @Test
    fun `a forced bootstrap over an unresolved key exchange builds nothing and writes nothing back`() =
        runBlocking {
            val world = LedgerWorld()
            val vm = world.openVm()
            world.kexThatNeverMines(vm)
            world.agePaidRecords(minutes = 61)
            val ledgerWrites = world.ledger.writes().size
            val paid = HashMap(world.base.paidRecords)
            val mark = world.prefs.mark()

            world.chatOpen(vm, force = true)

            assertEquals("an unresolved key exchange blocks a second one", 1, world.seam.proposals.get())
            assertEquals("the gate only reads: no ledger write", ledgerWrites, world.ledger.writes().size)
            assertEquals("no paid record is written for a refused attempt", paid, HashMap(world.base.paidRecords))
            val after = world.prefs.writesSince(mark)
            assertTrue(
                "nothing is undone: no marker is written false (${after.map { it.name to it.args.drop(1) }})",
                after.none { it.name == "setOwnBootSent" && it.args[1] == false },
            )
            assertEquals(true, world.base.ownBootSent[world.peer])
        }

    @Test
    fun `an owed key exchange over an unresolved attempt of its key stays owed`() =
        runBlocking {
            val world = LedgerWorld()
            val vm = world.openVm()
            world.kexThatNeverMines(vm)
            world.prefs.kexOwed += world.peer

            vm.retryOwedKeyExchange(world.peer)
            HandshakeHarness.awaitIdle(vm)
            world.blockTick(vm, LedgerWorld.H + 2)

            assertEquals("no second key exchange", 1, world.seam.proposals.get())
            assertTrue(
                "the owed mark was cleared as if the key exchange had been sent: the attempt on the chain is unresolved, " +
                    "so nothing may be settled by this call",
                world.peer in world.prefs.kexOwed,
            )
        }

    @Test
    fun `a key reply over an unresolved attempt is not paid again and its marker is not written`() =
        runBlocking {
            val world = LedgerWorld()
            val vm = world.openVm()
            world.asResponder()
            world.storedNeverMined()
            vm.retryKexAckIfResponder(world.peer)
            HandshakeHarness.awaitIdle(vm)
            assertEquals("fixture sanity: one key reply was paid", 1, world.seam.submits.get())

            // A new process (the in-memory "acked" map is gone) in which the durable marker was re-armed.
            world.restart()
            world.prefs.sentKexAck.remove(world.peer)
            val again = world.openVm()
            val mark = world.prefs.mark()
            again.retryKexAckIfResponder(world.peer)
            HandshakeHarness.awaitIdle(again)

            assertEquals("the first key reply is still unresolved on the chain: no second one", 1, world.seam.proposals.get())
            assertTrue(
                "PENDING changes nothing: the marker is not written",
                world.prefs.writesSince(mark).none { it.name == "setSentKexAckPubkey" },
            )
        }

    @Test
    fun `a mined attempt that is not yet deep and a proven one both answer the same`() =
        runBlocking {
            val world = LedgerWorld()
            val vm = world.openVm()
            world.kexProvenNotSent(vm)
            world.agePaidRecords(minutes = 61)
            val ledgerWrites = world.ledger.writes().size

            world.chatOpen(vm, force = true)
            world.prefs.kexOwed += world.peer
            vm.retryOwedKeyExchange(world.peer)
            HandshakeHarness.awaitIdle(vm)

            assertEquals("a proven failure waits for the user's Retry: nothing automatic pays", 1, world.seam.proposals.get())
            assertEquals("no ledger write by a refused attempt", ledgerWrites, world.ledger.writes().size)
            assertTrue("the owed mark stays", world.peer in world.prefs.kexOwed)
        }
}
