package co.electriccoin.zcash.ui.screen.chat.viewmodel

import androidx.lifecycle.viewModelScope
import co.electriccoin.zcash.ui.common.model.SubmitResult
import co.electriccoin.zcash.ui.screen.chat.viewmodel.HandshakeHarness.awaitIdle
import co.electriccoin.zcash.ui.screen.chat.viewmodel.PaymentHarness.eventually
import co.electriccoin.zcash.ui.screen.chat.viewmodel.PaymentHarness.paymentViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * SR-4 — DOUBLE-PAY (user payment): backing out of a chat while a payment-request fulfil is being broadcast,
 * then reopening it, shows the request unpaid with Pay live, and a second tap pays the full amount again.
 *
 * fulfillPaymentRequest pays on viewModelScope, but the repository proves and broadcasts in its own detached
 * scope, so backing out cancels only the await: the payment still reaches the network. At b256fcb3f the
 * CancellationException is swallowed by `catch (e: Exception)`, markRequestPaid never runs, and the reopened
 * chat (a new ChatViewModel, which loads the paid set from preferences) has nothing telling it the request
 * was paid. There is also no process-wide in-flight claim, so a second ChatViewModel tapping Pay while the
 * first broadcast is still open submits a second payment. Same shape as the KEX/KEXACK back-out bugs (A1).
 *
 * Drives the real fulfillPaymentRequest -> real CreateChunkedMessageProposalUseCase -> real
 * ZashiProposalRepositoryImpl, with the settlement seam holding the broadcast open.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FulfillBackOutMidSendNeverReArmsPayTest {
    private val sendScopes = SendHarnessScopes()

    private companion object {
        const val AMOUNT = 50_000_000L // 0.5 ZEC: a value transfer, gated by spend-auth
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
    fun `backing out while the fulfil broadcast is in flight marks the request paid and reopening never pays again`() =
        runBlocking {
            val peer = "usyntheticfulfilbackout00001"
            val requestId = "synthetic-request-backout-1"
            val prefs = PaymentPrefs(peer)
            val seam = PaymentSeam(peer, holdFirstSubmit = true)
            val firstOpen = paymentViewModel(prefs, seam)

            firstOpen.fulfillPaymentRequest(peer, AMOUNT, requestId)
            withTimeout(PaymentHarness.TIMEOUT_MS) { seam.firstSubmitEntered.await() }
            // The user backs out: the ViewModel is cleared and its scope cancelled mid-broadcast.
            firstOpen.viewModelScope.coroutineContext.job
                .cancel()
            // The detached repository scope carries on and the payment reaches the network anyway.
            seam.release.complete(Unit)
            withTimeout(PaymentHarness.TIMEOUT_MS) { seam.firstSubmitDone.await() }

            assertTrue(
                "the payment was broadcast, but backing out left the request unpaid: markRequestPaid never ran, " +
                    "so the reopened chat shows Pay again. Cancellation is not failure.",
                eventually { requestId in prefs.paid },
            )

            // The user reopens the chat and taps Pay on the same request.
            val reopen = paymentViewModel(prefs, seam)
            reopen.fulfillPaymentRequest(peer, AMOUNT, requestId)
            awaitIdle(reopen)

            assertEquals("fixture sanity", emptyList<String>(), prefs.unexpectedCalls)
            assertEquals("the request was paid twice", 1, seam.submits.get())
        }

    @Test
    fun `a second chat screen tapping Pay while the first fulfil is still broadcasting does not pay again`() =
        runBlocking {
            val peer = "usyntheticfulfilbackout00002"
            val requestId = "synthetic-request-concurrent-2"
            val prefs = PaymentPrefs(peer)
            val seam = PaymentSeam(peer, holdFirstSubmit = true)
            val first = paymentViewModel(prefs, seam)
            val second = paymentViewModel(prefs, seam)

            first.fulfillPaymentRequest(peer, AMOUNT, requestId)
            withTimeout(PaymentHarness.TIMEOUT_MS) { seam.firstSubmitEntered.await() }
            second.fulfillPaymentRequest(peer, AMOUNT, requestId)
            // Give a (wrongly) admitted second fulfil time to reach settlement before the first completes.
            eventually(timeoutMs = 500L) { seam.submits.get() > 1 }
            seam.release.complete(Unit)
            awaitIdle(first, second)

            assertEquals("fixture sanity", emptyList<String>(), prefs.unexpectedCalls)
            assertEquals(
                "a second ChatViewModel paid the same request while the first payment was still being broadcast; " +
                    "the in-flight claim must be process-wide",
                1,
                seam.submits.get(),
            )
            assertTrue("the paid marker must be set once the payment is submitted", requestId in prefs.paid)
        }

    /**
     * Control, GREEN before and after the fix: a fulfil the network rejected outright (nothing landed) leaves
     * the request payable, so the user can pay it once it can go through.
     *
     * C3 (B2 review item 5): "nothing landed" is a rejection with no stored transaction (empty txIds, the P2a
     * `nothingStored` rule). A Failure that names a stored transaction may still be broadcast by the SDK, so it
     * keeps the request paid; PaymentRequestMarkedBeforeSubmitTest covers that case.
     */
    @Test
    fun `control - a rejected fulfil leaves the request payable`() =
        runBlocking {
            val peer = "usyntheticfulfilbackout00003"
            val requestId = "synthetic-request-rejected-3"
            val prefs = PaymentPrefs(peer)
            val seam =
                PaymentSeam(
                    peer,
                    firstSubmitOutcome = SubmitResult.Failure(txIds = emptyList(), code = 18, description = "rejected"),
                )
            val vm = paymentViewModel(prefs, seam)
            vm.fulfillPaymentRequest(peer, AMOUNT, requestId)
            awaitIdle(vm)
            assertTrue("a rejected payment must not mark the request paid", requestId !in prefs.paid)

            vm.fulfillPaymentRequest(peer, AMOUNT, requestId)
            awaitIdle(vm)

            assertEquals("fixture sanity", emptyList<String>(), prefs.unexpectedCalls)
            assertEquals(2, seam.submits.get())
            assertTrue(requestId in prefs.paid)
        }
}
