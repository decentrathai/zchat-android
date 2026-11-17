package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.screen.chat.model.MessageStatus
import co.electriccoin.zcash.ui.screen.chat.viewmodel.HandshakeHarness.awaitIdle
import co.electriccoin.zcash.ui.screen.chat.viewmodel.SendAttemptHarness.AMOUNT
import co.electriccoin.zcash.ui.screen.chat.viewmodel.SendAttemptHarness.H
import co.electriccoin.zcash.ui.screen.chat.viewmodel.SendAttemptHarness.Wallet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test

/**
 * F4 (fix-all 2.13.0, train T4; SPECS §1.2 "in-flight guard", §1.3 R7), a guard and the most important one: a send whose
 * submit is still running in this process is never judged, however far the tip runs.
 *
 * The situation of RetryNeverPaysTwiceTest "a stale empty transaction view at a far later height never authorizes
 * ambiguous resend", driven through the history pass: the first screen's submit is held; a reopened screen loads the
 * row (FAILED, with a marker, no id) and runs passes at 10,000 blocks past, then more than 52 blocks later on two
 * rising passes, over a wallet that shows nothing. Without an in-flight guard, the no-id rule (release anchor + 52,
 * no candidate) would call it "not sent" and Retry would pay a second time while the first is being submitted.
 * Green at HEAD and after F4.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class InFlightAttemptIsNeverResolvedTest {
    private val sendScopes = SendHarnessScopes()

    private companion object {
        const val PEER = "usyntheticf4inflightpeer0001"
    }

    @Before
    fun setUp() {
        sendScopes.open()
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        sendScopes.close()
        Dispatchers.resetMain()
    }

    @Test
    fun `a submit held in this process is never resolved by the pass`() =
        runBlocking {
            val prefs = PaymentPrefs(PEER)
            val seam = PaymentSeam(PEER, holdFirstSubmit = true)
            val first = PaymentHarness.paymentViewModel(prefs, seam)
            PaymentHarness.setBlockHeight(first, H)
            first.sendMessage(PEER, "synthetic in-flight hello", AMOUNT)
            withTimeout(PaymentHarness.TIMEOUT_MS) { seam.firstSubmitEntered.await() }
            try {
                val wallet = Wallet(scanned = H + 2)
                // ZC-1071: a screen opened beside the live send now shows it as sending; this F4 case (the row shown FAILED
                // while its submit is held here) is kept by emptying the live-sender registry.
                LiveSenders.resetForTest()
                val reopen = SendAttemptHarness.openChat(prefs, seam, wallet)
                val orphan = PaymentHarness.pendingMessages(reopen).single()
                assertEquals("fixture sanity", MessageStatus.FAILED, orphan.status)

                for (tip in listOf(H + 10_000, H + 10_060, H + 10_061, H + 10_062)) {
                    wallet.scanned = tip
                    wallet.network = tip
                    SendAttemptHarness.runPass(reopen, wallet)
                }
                assertFalse("Retry was accepted while the first submit is still running", reopen.retryMessage(PEER, orphan.id))
                awaitIdle(reopen)
                assertEquals("a second payment was proposed while the first is in flight", 1, seam.proposalUris.size)
                assertEquals(1, seam.submits.get())
            } finally {
                seam.release.complete(Unit)
                withTimeout(PaymentHarness.TIMEOUT_MS) { seam.firstSubmitDone.await() }
                awaitIdle(first)
            }
        }
}
