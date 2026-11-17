package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.common.model.SubmitResult
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * F8 R22 (ZC-112; review finding G4-2) — a Retry cut off before its transaction ids were recorded leaves the offer
 * exactly as it was.
 *
 * If a confirmed Retry turned the failed entry into a new attempt at once, a kill before anything was created would
 * leave an attempt with nothing on the chain and no notice: the user's way out would be gone.
 *
 * Wanted: the failed entry is replaced only in the one write that stores the new transaction's ids. A Retry cut off
 * before that (here: the process dies after the attempt began and before anything was created) leaves the failed entry
 * unchanged; after the restart the notice is shown again, nothing automatic pays, and the Retry asks its price again
 * and pays exactly once.
 *
 * Every address, key and transaction id is synthetic.
 */
class KillBetweenBeginAndTheIdsLeavesTheOfferAndTheNoticeTest {
    private val sendScopes = SendHarnessScopes()

    @Before
    fun setUp() = LedgerTests.setUp(sendScopes)

    @After
    fun tearDown() = LedgerTests.tearDown(sendScopes)

    @Test
    fun `a Retry killed after it began leaves the failed entry, and after the restart the Retry pays once`() =
        runBlocking {
            val world = LedgerWorld()
            val vm = world.openVm()
            PaymentHarness.setField(vm, "accountDataSource", fundedAccounts()) // ZC-1822: a wallet that can pay
            val offer = world.kexProvenNotSent(vm)
            val failed = world.ledger.one("KEX")!!
            world.tapRetry(vm, offer)
            HandshakeHarness.awaitIdle(vm)
            val writes = world.ledger.writes().size
            LedgerProcess.armKillAfterBegin()

            vm.confirmPaidAction()
            HandshakeHarness.awaitIdle(vm)
            LedgerProcess.disarm()

            assertEquals("the process died before anything was created", 1, world.seam.stored.size)
            assertEquals("no ledger write between the confirm and the kill", emptyList<String>(), world.ledger.writes().drop(writes))
            val after = world.ledger.one("KEX")!!
            assertEquals("the failed entry is unchanged", "NOT_SENT", after.state)
            assertEquals(failed.startedAt, after.startedAt)
            assertEquals(failed.txIds, after.txIds)

            world.restart()
            AutoSpendVm.setHistoryRead(true)
            val again = world.openVm()
            PaymentHarness.setField(again, "accountDataSource", fundedAccounts()) // ZC-1822: a wallet that can pay
            world.pass(again, world.wallet.scanned + 1)
            assertEquals("after the restart the notice is shown again", offer, world.notice(again)[world.peer])
            assertTrue("no Retry is left running", LedgerProcess.retryInFlight().isEmpty())
            world.agePaidRecords(minutes = 61)
            val built = world.seam.proposals.get()
            world.chatOpen(again, force = true)
            assertEquals("nothing automatic pays", built, world.seam.proposals.get())

            world.seam.outcome = { _, ids -> SubmitResult.Success(ids) }
            world.retryAndConfirm(again, offer)
            assertEquals("the Retry pays exactly once", 2, world.seam.submits.get())
            assertEquals(failed.txIds, world.ledger.one("KEX")?.superseded)
        }

    @Test
    fun `the same cut for a key-reply Retry leaves its offer`() =
        runBlocking {
            val world = LedgerWorld()
            val vm = world.openVm()
            val offer = world.kexAckProvenNotSent(vm)
            world.tapRetry(vm, offer)
            HandshakeHarness.awaitIdle(vm)
            LedgerProcess.armKillAfterBegin()

            vm.confirmPaidAction()
            HandshakeHarness.awaitIdle(vm)
            LedgerProcess.disarm()

            assertEquals("NOT_SENT", world.ledger.one("KEXACK")?.state)
            assertEquals(offer, world.ledger.one("KEXACK")?.startedAt)
            assertEquals(1, world.seam.stored.size)
        }
}
