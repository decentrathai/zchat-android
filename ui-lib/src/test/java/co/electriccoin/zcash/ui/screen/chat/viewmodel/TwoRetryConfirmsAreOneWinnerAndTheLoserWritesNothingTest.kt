package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.common.model.SubmitResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean

/**
 * F8 R15 (ZC-112; review findings GPT-3 #3, #4) — two screens confirm the same Retry: one payment.
 *
 * The chat list and the open chat are two ChatViewModels, and each can show the question of the same Retry. One
 * confirmed quote must pay at most once.
 *
 * Wanted: in one process the second confirm, arriving while the first is still running, does nothing. And without that
 * single-flight (the backstop): when both attempts have stored a transaction, exactly one ids commit replaces the failed
 * entry; the other's transaction is never submitted, it writes no ledger state, and it does not withdraw the winner's
 * paid record.
 *
 * Every address, key and transaction id is synthetic.
 */
class TwoRetryConfirmsAreOneWinnerAndTheLoserWritesNothingTest {
    private val sendScopes = SendHarnessScopes()

    @Before
    fun setUp() = LedgerTests.setUp(sendScopes)

    @After
    fun tearDown() = LedgerTests.tearDown(sendScopes)

    @Test
    fun `a second confirm while the first Retry is running does nothing`() =
        runBlocking {
            val world = LedgerWorld()
            val detail = world.openVm()
            PaymentHarness.setField(detail, "accountDataSource", fundedAccounts()) // ZC-1822: a wallet that can pay
            val offer = world.kexProvenNotSent(detail)
            val list = world.openVm()
            PaymentHarness.setField(list, "accountDataSource", fundedAccounts()) // ZC-1822: a wallet that can pay
            world.pass(list, world.wallet.scanned + 1)
            // Both screens ask before either answers.
            world.tapRetry(detail, offer)
            world.tapRetry(list, offer)
            HandshakeHarness.awaitIdle(detail, list)
            assertEquals("HANDSHAKE_RETRY", world.question(list))
            val hold = CompletableDeferred<Unit>()
            world.seam.holdProposals = hold
            world.seam.outcome = { _, ids -> SubmitResult.Success(ids) }

            detail.confirmPaidAction()
            HandshakeHarness.awaitTrue { world.seam.proposals.get() == 2 }
            list.confirmPaidAction()
            world.seam.holdProposals = null
            hold.complete(Unit)
            HandshakeHarness.awaitIdle(detail, list)

            assertEquals("one confirmed quote builds one proposal", 2, world.seam.proposals.get())
            assertEquals("and pays once", 2, world.seam.submits.get())
            assertTrue("the single-flight is released when the call ends", LedgerProcess.retryInFlight().isEmpty())
        }

    @Test
    fun `without the single-flight exactly one ids commit wins and the loser submits nothing`() =
        runBlocking {
            val world = LedgerWorld()
            val detail = world.openVm()
            PaymentHarness.setField(detail, "accountDataSource", fundedAccounts()) // ZC-1822: a wallet that can pay
            val offer = world.kexProvenNotSent(detail)
            val failed = world.ledger.one("KEX")!!
            val list = world.openVm()
            PaymentHarness.setField(list, "accountDataSource", fundedAccounts()) // ZC-1822: a wallet that can pay
            world.pass(list, world.wallet.scanned + 1)
            world.tapRetry(detail, offer)
            world.tapRetry(list, offer)
            HandshakeHarness.awaitIdle(detail, list)
            world.seam.outcome = { _, ids -> SubmitResult.Success(ids) }
            val interleaved = AtomicBoolean(false)
            world.ledger.beforeMethod("recordStored") {
                if (interleaved.compareAndSet(false, true)) {
                    // The first attempt has stored its transaction and is about to commit its ids. The second screen
                    // confirms now, with the single-flight out of the way, and stores its own transaction.
                    LedgerProcess.clearRetryInFlight()
                    list.confirmPaidAction()
                    val deadline = System.currentTimeMillis() + 3_000L
                    while (world.seam.stored.size < 3 && System.currentTimeMillis() < deadline) Thread.sleep(5)
                }
            }

            detail.confirmPaidAction()
            HandshakeHarness.awaitIdle(detail, list)
            HandshakeHarness.awaitTrue { LedgerProcess.retryInFlight().isEmpty() }

            assertEquals("fixture sanity: both attempts stored a transaction", 3, world.seam.stored.size)
            assertEquals("exactly one of the two is submitted", 2, world.seam.submits.get())
            val now = world.ledger.one("KEX")!!
            assertEquals("LIVE", now.state)
            assertEquals("the winner's one id", 1, now.txIds.size)
            assertEquals("the failed attempt's id is superseded once", failed.txIds, now.superseded)
            assertTrue("the winner's paid record stands: the loser withdrew nothing", world.base.paidRecords.isNotEmpty())
            assertEquals(true, world.base.ownBootSent[world.peer])
        }
}
