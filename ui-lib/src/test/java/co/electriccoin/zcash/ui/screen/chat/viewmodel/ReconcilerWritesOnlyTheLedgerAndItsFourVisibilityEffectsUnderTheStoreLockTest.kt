package co.electriccoin.zcash.ui.screen.chat.viewmodel

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * F8 R20 (ZC-112; review findings GPT-3 #4, #7, G-4) — the pass that settles send attempts writes only the ledger and
 * its visibility effects, in the order commit, effect, delete.
 *
 * The pass must never be a way to a payment: it writes no marker an automatic sender reads. Where a proven failure has
 * a visible effect (here: the note of a locked message that never went out), the effect is written after the commit,
 * with no ledger lock held, and the entry is deleted only after the effect's write succeeded. An effect whose entry was
 * replaced meanwhile writes nothing.
 *
 * (The Join record's effect, with its store lock, is pinned in EffectKilledBeforeItsDeleteIsReplayed...; the two source
 * scans that pin "no payer, no marker setter" in the pass's code ride with the fix.)
 *
 * Every address, key and transaction id is synthetic.
 */
class ReconcilerWritesOnlyTheLedgerAndItsFourVisibilityEffectsUnderTheStoreLockTest {
    private val sendScopes = SendHarnessScopes()

    @Before
    fun setUp() = LedgerTests.setUp(sendScopes)

    @After
    fun tearDown() = LedgerTests.tearDown(sendScopes)

    private fun LedgerWorld.seedLockedMessage(number: Int): EntryView {
        assertTrue("this build keeps no record of a locked message's send attempt", ledger.exists)
        val id = SendAttemptHarness.txId(number)
        wallet.txs = wallet.txs + SendAttemptHarness.ChainTx(number, peer, null, null, seam.expiry, amount = 1_000L)
        return ledger.seedLive(
            "TIME_LOCK",
            peer,
            "synthetic-memo-hash-$number",
            startedAt = 5_000L + number,
            attemptHeight = LedgerWorld.H,
            accountHash = accountHash(),
            txIds = listOf(id),
            expiries = mapOf(id to seam.expiry),
        )
    }

    @Test
    fun `a proven key exchange writes nothing outside the ledger`() =
        runBlocking {
            val world = LedgerWorld()
            val vm = world.openVm()
            world.kexThatNeverMines(vm)
            world.timeline.clear()

            world.twoPassesPastExpiry(vm)

            assertEquals("NOT_SENT", world.ledger.one("KEX")?.state)
            assertEquals(
                "the pass's only writes are the ledger's (the anchor, then the commit); no marker, owed flag, allowance or paid record",
                listOf("ledger:setAnchor", "ledger:markNotSent"),
                world.timeline.toList(),
            )
        }

    @Test
    fun `a proven locked message writes its note after the commit and deletes its entry after the note`() =
        runBlocking {
            val world = LedgerWorld(mode = co.electriccoin.zcash.ui.screen.chat.model.ConversationMode.VAULT, exchangeComplete = true)
            val vm = world.openVm()
            world.seedLockedMessage(8_701)
            world.timeline.clear()
            val mark = world.prefs.mark()

            world.twoPassesPastExpiry(vm)

            assertEquals(
                "commit, then the effect, then the delete",
                listOf("ledger:setAnchor", "ledger:markNotSent", "prefs:addPendingMessage", "ledger:delete"),
                world.timeline.toList(),
            )
            assertTrue("no ledger lock is held at the effect's write", world.prefs.writesSince(mark).none { it.ledgerLockHeld })
            assertTrue("the entry is gone after its note", world.ledger.all().isEmpty())
        }

    @Test
    fun `an effect whose entry was replaced writes nothing, and a failed effect write keeps the entry for the next pass`() =
        runBlocking {
            val world = LedgerWorld(mode = co.electriccoin.zcash.ui.screen.chat.model.ConversationMode.VAULT, exchangeComplete = true)
            val vm = world.openVm()
            world.seedLockedMessage(8_702)
            // The note's write does not reach the store.
            world.prefs.failRowWrites = true
            world.twoPassesPastExpiry(vm)
            val proven = world.ledger.one("TIME_LOCK")!!
            assertEquals("the entry is kept until its effect is written", "NOT_SENT", proven.state)
            assertTrue(world.prefs.rows.isEmpty())

            // Between the pass's look and the effect's write the entry is replaced by another attempt's.
            world.prefs.failRowWrites = false
            world.ledger.afterMethod("attemptRunning") {
                world.ledger.clearHooks()
                world.ledger.put(proven.with("startedAtMillis" to proven.startedAt + 7))
            }
            world.pass(vm, world.wallet.scanned + 1)
            assertTrue("a stale effect writes nothing", world.prefs.rows.isEmpty())

            // With the entry it was proven for back in place, the next pass writes the effect and then deletes.
            world.ledger.remove(world.ledger.one("TIME_LOCK")!!)
            world.ledger.put(proven.raw)
            world.pass(vm, world.wallet.scanned + 1)
            assertEquals("the note is written once", 1, world.prefs.rows.size)
            assertTrue(world.ledger.all().isEmpty())
        }
}
