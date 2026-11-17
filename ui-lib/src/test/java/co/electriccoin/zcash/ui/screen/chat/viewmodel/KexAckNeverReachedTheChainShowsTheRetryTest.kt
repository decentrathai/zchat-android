package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.common.model.SubmitResult
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * F8 R6 (ZC-121, part A) — a key reply whose marker was stamped but which never reached the chain is shown, and its
 * Retry pays exactly one.
 *
 * The KEXACK's durable marker is stamped before the submit. A kill between the stamp and the submit, or a transaction
 * that expires unmined, leaves the marker (and, in the same process, the in-memory "acked" map) saying "sent" for
 * good: the contact never gets our key and no later chat open sends it.
 *
 * Wanted: the attempt's entry, with no transaction id, is proven not sent after a restart (another process of this
 * wallet, a wallet that holds transactions but none that could be it) on two passes; the notice is shown; the marker is
 * untouched and a chat open still sends nothing; the Retry pays exactly one key reply. The same in the process that
 * sent it, where the in-memory map also says "acked".
 *
 * Every address, key and transaction id is synthetic.
 */
class KexAckNeverReachedTheChainShowsTheRetryTest {
    private val sendScopes = SendHarnessScopes()

    @Before
    fun setUp() = LedgerTests.setUp(sendScopes)

    @After
    fun tearDown() = LedgerTests.tearDown(sendScopes)

    @Test
    fun `a key reply cut off after its stamp is proven not sent after the restart, and its Retry pays exactly one`() =
        runBlocking {
            val world = LedgerWorld()
            val vm = world.openVm()
            PaymentHarness.setField(vm, "accountDataSource", fundedAccounts()) // ZC-1822: a wallet that can pay
            val theirs = world.asResponder()
            LedgerProcess.armKillAfterBegin()
            vm.retryKexAckIfResponder(world.peer)
            HandshakeHarness.awaitIdle(vm)
            LedgerProcess.disarm()
            assertEquals("the process died before anything was created", 0, world.seam.stored.size)
            assertEquals("fixture sanity: the marker was stamped before the kill", theirs, world.prefs.sentKexAck[world.peer])

            val cut =
                world.ledger.one("KEXACK")
                    ?: kotlin.test.fail("the cut-off key reply left no record: nothing shows that its marker is stale")
            world.ledger.put(cut.with("processId" to "an-ended-process"))
            world.restart()
            AutoSpendVm.setHistoryRead(true)
            val again = world.openVm()
            PaymentHarness.setField(again, "accountDataSource", fundedAccounts()) // ZC-1822: a wallet that can pay
            // The wallet holds transactions of this account, but none that could be the key reply.
            world.wallet.txs = listOf(SendAttemptHarness.ChainTx(8_601, "usyntheticsomeoneelse0000001", null, LedgerWorld.H - 5, null))
            world.pass(again, LedgerWorld.H + 3)
            world.pass(again, LedgerWorld.H + 4)

            val offer = world.notice(again)[world.peer]
            assertTrue("the notice is shown: ${world.ledger.all()}", offer != null)
            assertEquals("the marker is untouched", theirs, world.prefs.sentKexAck[world.peer])
            again.retryKexAckIfResponder(world.peer)
            HandshakeHarness.awaitIdle(again)
            assertEquals("a chat open sends nothing", 0, world.seam.stored.size)

            world.retryAndConfirm(again, offer!!)
            assertEquals("the Retry pays exactly one key reply", 1, world.seam.submits.get())
        }

    @Test
    fun `in the process that sent it the Retry is not stopped by the in-memory acked map`() =
        runBlocking {
            val world = LedgerWorld()
            val vm = world.openVm()
            PaymentHarness.setField(vm, "accountDataSource", fundedAccounts()) // ZC-1822: a wallet that can pay
            val offer = world.kexAckProvenNotSent(vm)
            world.seam.outcome = { _, ids -> SubmitResult.Success(ids) }

            world.retryAndConfirm(vm, offer)

            assertEquals("the Retry pays exactly one key reply in the same process", 2, world.seam.submits.get())
        }
}
