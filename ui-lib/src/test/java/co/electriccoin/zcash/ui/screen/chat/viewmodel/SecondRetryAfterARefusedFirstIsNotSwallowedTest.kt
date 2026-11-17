package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.common.model.SubmitResult
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * F8 R23 (ZC-112; review finding G4-3) — a Retry that was refused never swallows the next one.
 *
 * The confirmed Retry is single-flight per failed attempt. If that claim were kept after a refusal, every later Retry
 * would be swallowed and the chat could never finish connecting.
 *
 * Wanted: the claim is released when the confirmed call ends by any path. After a refusal (the wallet cannot pay it
 * now) and after a payer that throws, no Retry is left running, the notice is back, and a second Retry asks its price
 * and pays exactly once.
 *
 * Every address, key and transaction id is synthetic.
 */
class SecondRetryAfterARefusedFirstIsNotSwallowedTest {
    private val sendScopes = SendHarnessScopes()

    @Before
    fun setUp() = LedgerTests.setUp(sendScopes)

    @After
    fun tearDown() = LedgerTests.tearDown(sendScopes)

    @Test
    fun `after a refused Retry a second one runs and pays exactly once`() =
        runBlocking {
            val world = LedgerWorld()
            val vm = world.openVm()
            PaymentHarness.setField(vm, "accountDataSource", fundedAccounts()) // ZC-1822: a wallet that can pay
            val offer = world.kexProvenNotSent(vm)
            world.seam.failNextProposalWith = IllegalStateException("synthetic: refused before anything was stored")

            world.retryAndConfirm(vm, offer)

            assertEquals("the refused Retry submitted nothing", 1, world.seam.submits.get())
            assertTrue("no Retry is left running after a refusal", LedgerProcess.retryInFlight().isEmpty())
            assertEquals("the notice is back", offer, world.notice(vm)[world.peer])

            world.seam.outcome = { _, ids -> SubmitResult.Success(ids) }
            world.tapRetry(vm, offer)
            HandshakeHarness.awaitIdle(vm)
            assertEquals("the second Retry asks its price", "HANDSHAKE_RETRY", world.question(vm))
            vm.confirmPaidAction()
            HandshakeHarness.awaitIdle(vm)
            assertEquals("and pays exactly once", 2, world.seam.submits.get())
        }

    @Test
    fun `after a Retry that could not read the ledger a second one is not swallowed`() =
        runBlocking {
            val world = LedgerWorld()
            val vm = world.openVm()
            PaymentHarness.setField(vm, "accountDataSource", fundedAccounts()) // ZC-1822: a wallet that can pay
            val offer = world.kexProvenNotSent(vm)
            // The ledger itself fails under the payer (its read throws): the Retry ends with an error.
            world.ledger.beforeMethod("gate") { throw IllegalStateException("synthetic: the store cannot be read") }

            world.retryAndConfirm(vm, offer)

            assertEquals(1, world.seam.proposals.get())
            assertTrue("no Retry is left running after a failure", LedgerProcess.retryInFlight().isEmpty())
            world.ledger.clearHooks()
            world.pass(vm, world.wallet.scanned + 1)
            assertEquals(offer, world.notice(vm)[world.peer])

            world.seam.outcome = { _, ids -> SubmitResult.Success(ids) }
            world.retryAndConfirm(vm, offer)
            assertEquals("the next Retry pays exactly once", 2, world.seam.submits.get())
        }
}
