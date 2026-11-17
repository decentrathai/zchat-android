package co.electriccoin.zcash.ui.screen.chat.viewmodel

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * F8 R19 (ZC-112; review finding GPT-3 #8) — an attempt that was cut off before it had a transaction is proven not
 * sent even when an unrelated payment to the same contact was mined meanwhile.
 *
 * The process dies after the attempt's entry is written and before anything is created. This build records an entry
 * before the SDK is asked and the ids before the first submit, so an entry with no ids never handed anything to the
 * network. A mined payment of ours to that contact therefore cannot be this attempt's transaction, and must not leave
 * the attempt "maybe mined" for ever (the marker would say "sent" for good).
 *
 * Wanted: after the restart, two passes at rising tips prove it and the notice is shown; the chat sends nothing by
 * itself. An unmined candidate that is still alive defers the verdict until its own expiry plus the margin.
 *
 * Every address, key and transaction id is synthetic.
 */
class NoIdAttemptOfThisBuildIsNotSentEvenWhenAnUnrelatedPaymentMinedTest {
    private val sendScopes = SendHarnessScopes()

    @Before
    fun setUp() = LedgerTests.setUp(sendScopes)

    @After
    fun tearDown() = LedgerTests.tearDown(sendScopes)

    @Test
    fun `a cut-off attempt is proven not sent although an unrelated payment to the contact was mined`() =
        runBlocking {
            val world = LedgerWorld()
            val vm = world.openVm()
            LedgerProcess.armKillAfterBegin()
            world.chatOpen(vm)
            LedgerProcess.disarm()
            assertEquals("the process died before anything was created", 0, world.seam.stored.size)
            val cut = world.ledger.one("KEX")
            assertEquals("the attempt's entry was written before the SDK was asked", "LIVE", cut?.state)

            // A new process of the same wallet. An unrelated payment of ours to this contact is mined after the attempt.
            world.ledger.put(cut!!.with("processId" to "an-ended-process"))
            world.restart()
            AutoSpendVm.setHistoryRead(true)
            val again = world.openVm()
            world.wallet.txs =
                listOf(SendAttemptHarness.ChainTx(8_401, world.peer, null, LedgerWorld.H + 2, null, amount = 50_000L))

            world.pass(again, LedgerWorld.H + 3)
            world.pass(again, LedgerWorld.H + 4)

            assertEquals("a mined unrelated payment must not leave the attempt stuck", "NOT_SENT", world.ledger.one("KEX")?.state)
            assertTrue("the notice is shown", world.notice(again).containsKey(world.peer))
            world.chatOpen(again)
            assertEquals("and nothing is sent by itself", 0, world.seam.stored.size)
        }

    @Test
    fun `an unmined candidate that is still alive defers the verdict to its own expiry`() =
        runBlocking {
            val world = LedgerWorld()
            val vm = world.openVm()
            LedgerProcess.armKillAfterBegin()
            world.chatOpen(vm)
            LedgerProcess.disarm()
            val cut = world.ledger.one("KEX") ?: kotlin.test.fail("this build keeps no record of an automatic send attempt")
            world.ledger.put(cut.with("processId" to "an-ended-process"))
            world.restart()
            AutoSpendVm.setHistoryRead(true)
            val again = world.openVm()
            world.wallet.txs = listOf(SendAttemptHarness.ChainTx(8_402, world.peer, null, null, world.seam.expiry, amount = 1_000L))

            world.pass(again, LedgerWorld.H + 3)
            world.pass(again, LedgerWorld.H + 4)
            assertEquals("a live candidate could be it: waiting", "LIVE", world.ledger.one("KEX")?.state)

            world.twoPassesPastExpiry(again)
            assertEquals("past the candidate's expiry it is proven", "NOT_SENT", world.ledger.one("KEX")?.state)
        }
}
