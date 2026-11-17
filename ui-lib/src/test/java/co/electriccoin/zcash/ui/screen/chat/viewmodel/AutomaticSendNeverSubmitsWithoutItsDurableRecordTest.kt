package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.common.model.SubmitResult
import co.electriccoin.zcash.ui.common.sendattempt.SendAttemptRules
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * F8 R12 (ZC-112; review finding GPT 1) — no transaction of an automatic setup reaches the network without a durable
 * record of it first.
 *
 * If the phone cannot record an attempt (its entry before the SDK is asked, its transaction ids before the first
 * submit), a later look at the chain cannot tell whether that attempt is still alive, and a second payment for the same
 * key exchange could follow. Today nothing is recorded at all: the KEX is simply submitted.
 *
 * Wanted (fail closed): when the entry cannot be written, or no ZCHAT account is known, nothing is created or
 * submitted and the sender's own stamp is withdrawn, so the next trigger pays once. When the stored ids cannot be
 * recorded, nothing is submitted and the attempt blocks a second one until the chain proves it dead. The same for a
 * Retry: its ids commit failing submits nothing and leaves the failed attempt, and its notice, as they were.
 *
 * Every address, key and transaction id is synthetic.
 */
class AutomaticSendNeverSubmitsWithoutItsDurableRecordTest {
    private val sendScopes = SendHarnessScopes()

    @Before
    fun setUp() = LedgerTests.setUp(sendScopes)

    @After
    fun tearDown() = LedgerTests.tearDown(sendScopes)

    @Test
    fun `an entry that cannot be written sends nothing, and the next trigger pays once`() =
        runBlocking {
            val world = LedgerWorld()
            val vm = world.openVm()
            world.ledger.failBegin = true

            world.chatOpen(vm)

            assertEquals("nothing may be stored by the SDK without the attempt's entry", 0, world.seam.stored.size)
            assertEquals("nothing may be submitted without the attempt's entry", 0, world.seam.submits.get())
            assertTrue("the paid record of an attempt that sent nothing is withdrawn", world.base.paidRecords.isEmpty())
            assertEquals("the bootstrap is re-armed, as for any attempt that sent nothing", false, world.base.ownBootSent[world.peer])
            assertTrue("no entry is left", world.ledger.all().isEmpty())

            world.ledger.failBegin = false
            world.chatOpen(vm)
            assertEquals("with a working ledger the next trigger pays once", 1, world.seam.submits.get())
        }

    @Test
    fun `stored ids that cannot be recorded are never submitted, and the attempt blocks a second one until proven dead`() =
        runBlocking {
            val world = LedgerWorld()
            val vm = world.openVm()
            world.ledger.failRecordStored = true

            world.chatOpen(vm)

            assertEquals("fixture sanity: the SDK stored one transaction", 1, world.seam.stored.size)
            assertEquals("a transaction whose ids were not recorded must not be submitted", 0, world.seam.submits.get())
            val entry = world.ledger.one("KEX")
            assertEquals("the attempt stays, with no ids", "LIVE", entry?.state)
            assertEquals(emptyList<String>(), entry?.txIds)
            assertEquals("its stamp stays: nothing is rolled back", true, world.base.ownBootSent[world.peer])

            // The wallet holds that stored, unsubmitted transaction: a candidate, so nothing is decided yet.
            world.ledger.failRecordStored = false
            world.walletListsStoredUnmined()
            world.agePaidRecords(minutes = 61)
            repeat(3) { world.chatOpen(vm) }
            world.chatOpen(vm, force = true)
            assertEquals("no second attempt while the first is unresolved", 1, world.seam.proposals.get())

            // Past the candidate's expiry and the anchor window, on two passes at rising tips: proven, and shown.
            val dead = world.seam.expiry + LedgerWorld.MARGIN
            world.pass(vm, dead)
            val past = dead + SendAttemptRules.window(dead, legacy = false)
            world.pass(vm, past)
            world.pass(vm, past + 1)
            assertEquals("NOT_SENT", world.ledger.one("KEX")?.state)
            assertTrue("the notice is shown; nothing was paid by itself", world.notice(vm).containsKey(world.peer))
            assertEquals(1, world.seam.proposals.get())
        }

    @Test
    fun `with no account known nothing is begun`() =
        runBlocking {
            val world = LedgerWorld()
            val vm = world.openVm()
            world.scriptAccountHash(null)

            world.chatOpen(vm)

            assertEquals("no ZCHAT account is known: nothing may be stored", 0, world.seam.stored.size)
            assertEquals(0, world.seam.submits.get())
            assertTrue("no entry without an account", world.ledger.all().isEmpty())
            assertTrue("the paid record is withdrawn", world.base.paidRecords.isEmpty())
        }

    @Test
    fun `a Retry whose ids cannot be recorded submits nothing and leaves the failed attempt as it was`() =
        runBlocking {
            val world = LedgerWorld()
            val vm = world.openVm()
            PaymentHarness.setField(vm, "accountDataSource", fundedAccounts()) // ZC-1822: a wallet that can pay
            val offer = world.kexProvenNotSent(vm)
            val failed = world.ledger.one("KEX")!!
            world.ledger.failRecordStored = true

            world.tapRetry(vm, offer)
            HandshakeHarness.awaitIdle(vm)
            vm.confirmPaidAction()
            HandshakeHarness.awaitIdle(vm)

            assertEquals("the Retry's transaction was not submitted", 1, world.seam.submits.get())
            val now = world.ledger.one("KEX")!!
            assertEquals("NOT_SENT", now.state)
            assertEquals("the failed entry is unchanged", failed.startedAt, now.startedAt)
            assertEquals(failed.txIds, now.txIds)
            assertEquals("the notice stays", offer, world.notice(vm)[world.peer])

            world.ledger.failRecordStored = false
            world.seam.outcome = { _, ids -> SubmitResult.Success(ids) }
            world.tapRetry(vm, offer)
            HandshakeHarness.awaitIdle(vm)
            vm.confirmPaidAction()
            HandshakeHarness.awaitIdle(vm)
            assertEquals("a later Retry with a working ledger pays once", 2, world.seam.submits.get())
        }
}
