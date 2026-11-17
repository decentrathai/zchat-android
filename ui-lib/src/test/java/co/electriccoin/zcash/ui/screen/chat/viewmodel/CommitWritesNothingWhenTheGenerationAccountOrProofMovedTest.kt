package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.common.sendattempt.SendAttemptProof
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * F8 R14 (ZC-112; review finding GPT-3 #5) — "proven not sent" is written in one commit, and only while the wallet,
 * the account and the proof are still the ones the pass read.
 *
 * The proof is two passes at strictly rising tips. Between the second pass's read of the chain and its write, the
 * phrase can change (another wallet's stores are live), a rescan can clear every proof, or the process can die. A
 * verdict written then would raise a notice, and offer a paid Retry, for a wallet or a proof it does not belong to.
 *
 * Wanted: if the wallet-scope generation moved, or the proof was cleared, between the read and the commit, nothing is
 * written and the entry stays LIVE; later passes prove it again from the start (two passes). A kill before the commit
 * leaves LIVE too, and a new process proves it again and commits once. A second screen's pass after the commit finds
 * the entry settled and writes nothing.
 *
 * Every address, key and transaction id is synthetic.
 */
class CommitWritesNothingWhenTheGenerationAccountOrProofMovedTest {
    private val sendScopes = SendHarnessScopes()

    @Before
    fun setUp() = LedgerTests.setUp(sendScopes)

    @After
    fun tearDown() = LedgerTests.tearDown(sendScopes)

    @Test
    fun `a wallet change between the read and the commit writes nothing, and the next wallet scope proves it again`() =
        runBlocking {
            val world = LedgerWorld()
            val vm = world.openVm()
            world.kexThatNeverMines(vm)
            world.ledger.beforeMethod("markNotSent") { world.prefs.generation++ }

            world.twoPassesPastExpiry(vm)

            assertEquals("the commit must not land after the wallet scope moved", "LIVE", world.ledger.one("KEX")?.state)
            assertTrue("no notice", world.notice(vm).isEmpty())

            world.ledger.clearHooks()
            world.twoPassesPastExpiry(vm, from = world.wallet.scanned + 1)
            assertEquals("under the new scope it is proven again and committed", "NOT_SENT", world.ledger.one("KEX")?.state)
        }

    @Test
    fun `a proof cleared between the second sighting and the commit is never reinstated`() =
        runBlocking {
            val world = LedgerWorld()
            val vm = world.openVm()
            world.kexThatNeverMines(vm)
            world.ledger.beforeMethod("markNotSent") {
                SendAttemptProof.clearAll()
            }

            world.twoPassesPastExpiry(vm)

            assertEquals("a rescan cleared the proof: nothing is committed on it", "LIVE", world.ledger.one("KEX")?.state)
            world.ledger.clearHooks()
            world.pass(vm, world.wallet.scanned + 1)
            assertEquals("one pass after the clear is one sighting, not a proof", "LIVE", world.ledger.one("KEX")?.state)
            world.pass(vm, world.wallet.scanned + 1)
            assertEquals("two new passes prove it", "NOT_SENT", world.ledger.one("KEX")?.state)
        }

    @Test
    fun `a kill before the commit leaves the entry live, and a new process proves it again and commits once`() =
        runBlocking {
            val world = LedgerWorld()
            val vm = world.openVm()
            world.kexThatNeverMines(vm)
            world.ledger.beforeMethod("markNotSent") { throw LedgerProcess.Killed() }

            runCatching { world.twoPassesPastExpiry(vm) }

            assertEquals("LIVE", world.ledger.one("KEX")?.state)
            world.ledger.clearHooks()
            world.restart()
            val again = world.openVm()
            world.pass(again, world.wallet.scanned + 1)
            assertEquals("the new process starts the proof again", "LIVE", world.ledger.one("KEX")?.state)
            world.pass(again, world.wallet.scanned + 1)
            assertEquals("NOT_SENT", world.ledger.one("KEX")?.state)
            assertEquals("committed once", 1, world.ledger.calls.count { it == "markNotSent" } - 1)

            // A second screen's pass judges what the first left: nothing to write.
            val writes = world.ledger.writes().size
            val list = world.openVm()
            world.pass(list, world.wallet.scanned + 1)
            assertEquals(writes, world.ledger.writes().size)
        }
}
