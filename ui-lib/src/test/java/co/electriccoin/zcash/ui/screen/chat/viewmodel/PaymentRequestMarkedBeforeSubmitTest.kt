package co.electriccoin.zcash.ui.screen.chat.viewmodel

import cash.z.ecc.android.sdk.model.Zatoshi
import co.electriccoin.zcash.ui.common.model.OrchardSealedException
import co.electriccoin.zcash.ui.common.model.SubmitResult
import co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferences
import co.electriccoin.zcash.ui.screen.chat.model.SendMessageState
import co.electriccoin.zcash.ui.screen.chat.viewmodel.HandshakeHarness.awaitIdle
import co.electriccoin.zcash.ui.screen.chat.viewmodel.PaymentHarness.paymentViewModel
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy

/**
 * C3 (B2 review item 5, P2) — the paid marker of a payment request is written only after the broadcast returns.
 *
 * `fulfilPaymentRequestToOutcome` passes no `onSubmitStarting`, and `markRequestPaid` runs only once
 * `createChunkedMessageProposal` has returned. A kill after the SDK stored the payment but before that write
 * leaves no marker on disk. After the restart a free (NOSTR) request bubble is built from the on-disk paid set,
 * so Pay is live from the first frame, before the ZREQ_FULFILL history scan-back has seen the stored payment,
 * and a tap pays the full amount again.
 *
 * Wanted:
 * - the marker is on disk before the SDK is asked to store the payment (a kill from then on keeps it paid);
 * - a marker that cannot be saved submits nothing, and the request stays payable;
 * - the marker is removed again only when the failure proves nothing was sent (a refusal before the submit, or a
 *   rejection that stored no transaction); a rejection after the SDK stored the payment keeps it paid and tells
 *   the user the payment may have been sent.
 *
 * Drives the real fulfillPaymentRequest, CreateChunkedMessageProposalUseCase and ZashiProposalRepositoryImpl
 * ([PaymentHarness]). All ids and addresses are synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PaymentRequestMarkedBeforeSubmitTest {
    private val sendScopes = SendHarnessScopes()

    private companion object {
        const val AMOUNT = 50_000_000L
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
    fun `the paid marker is on disk before the payment is stored, so a kill during the broadcast never pays twice`() =
        runBlocking {
            val peer = "usyntheticmarkbefore00000001"
            val requestId = "synthetic-c3-request-kill-1"
            val prefs = PaymentPrefs(peer)
            val seam = PaymentSeam(peer, holdFirstSubmit = true)

            paymentViewModel(prefs, seam).fulfillPaymentRequest(peer, AMOUNT, requestId)
            withTimeout(PaymentHarness.TIMEOUT_MS) { seam.firstSubmitEntered.await() }
            // The SDK is storing and broadcasting the payment. The process is killed here: only the disk survives.
            val diskAtKill = prefs.getPaidRequestIdsDirect()
            assertTrue(
                "the payment was handed to the SDK before the paid marker was written; a kill now leaves Pay live " +
                    "after the restart",
                requestId in diskAtKill,
            )

            // The restarted app: a new process (no in-flight claim), a new chat screen, the same disk.
            clearProcessClaims()
            val restartedPrefs = PaymentPrefs(peer).also { it.paid.addAll(diskAtKill) }
            val restartedSeam = PaymentSeam(peer)
            val restarted = paymentViewModel(restartedPrefs, restartedSeam)
            restarted.fulfillPaymentRequest(peer, AMOUNT, requestId)
            awaitIdle(restarted)

            assertEquals("fixture sanity", emptyList<String>(), prefs.unexpectedCalls + restartedPrefs.unexpectedCalls)
            assertEquals(
                "after the kill the request was paid a second time",
                1,
                seam.submits.get() + restartedSeam.submits.get(),
            )
        }

    @Test
    fun `a paid marker that cannot be saved sends nothing and leaves the request payable`() =
        runBlocking {
            val peer = "usyntheticmarkbefore00000002"
            val requestId = "synthetic-c3-request-unsaved-2"
            val prefs = PaymentPrefs(peer)
            val seam = PaymentSeam(peer)

            val vm = paymentViewModel(prefs, seam)
            // Like Android's SharedPreferencesImpl: a failed commit() still leaves the value in the in-memory map.
            PaymentHarness.setField(vm, "zchatPreferences", markerNotSaved(prefs))
            vm.fulfillPaymentRequest(peer, AMOUNT, requestId)
            awaitIdle(vm)

            assertEquals(
                "the paid marker could not be saved, yet the payment was sent with nothing on disk to stop a second one",
                0,
                seam.submits.get(),
            )
            assertFalse("nothing was sent, so the request must not stay marked paid", requestId in prefs.paid)
            assertTrue(
                "nothing was sent, but the screen says it may have been",
                (PaymentHarness.sendMessageState(vm) as? SendMessageState.Error)?.message?.contains("may have been sent") != true,
            )

            // The store works again: the same screen pays the request once.
            PaymentHarness.setField(vm, "zchatPreferences", prefs.proxy)
            vm.fulfillPaymentRequest(peer, AMOUNT, requestId)
            awaitIdle(vm)

            assertEquals("fixture sanity", emptyList<String>(), prefs.unexpectedCalls)
            assertEquals("the request stayed unpayable after a failure that sent nothing", 1, seam.submits.get())
            assertTrue(requestId in prefs.paid)
        }

    @Test
    fun `a rejection that stored the payment keeps the request paid and says it may have been sent`() =
        runBlocking {
            val peer = "usyntheticmarkbefore00000003"
            val requestId = "synthetic-c3-request-stored-3"
            val prefs = PaymentPrefs(peer)
            // The SDK stored the transaction, then the server rejected it: the SDK may still broadcast it.
            val seam =
                PaymentSeam(
                    peer,
                    firstSubmitOutcome =
                        SubmitResult.Failure(txIds = listOf("synthetic-stored-txid"), code = 18, description = "rejected"),
                )

            val vm = paymentViewModel(prefs, seam)
            vm.fulfillPaymentRequest(peer, AMOUNT, requestId)
            awaitIdle(vm)

            assertTrue("a stored payment may still go out, so the request must stay paid", requestId in prefs.paid)
            val state = PaymentHarness.sendMessageState(vm)
            assertTrue(
                "a stored payment may still go out, but the screen said $state",
                state is SendMessageState.Error && state.message.contains("may have been sent"),
            )

            // A reopened chat screen and a tap on the same request.
            val reopened = paymentViewModel(prefs, seam)
            reopened.fulfillPaymentRequest(peer, AMOUNT, requestId)
            awaitIdle(reopened)

            assertEquals("fixture sanity", emptyList<String>(), prefs.unexpectedCalls)
            assertEquals("the stored payment was paid a second time", 1, seam.submits.get())
        }

    /** Control: a refusal before the submit (sealed Orchard funds) stores nothing, so the request stays payable. */
    @Test
    fun `control - a refusal before the submit leaves the request payable`() =
        runBlocking {
            val peer = "usyntheticmarkbefore00000004"
            val requestId = "synthetic-c3-request-sealed-4"
            val prefs = PaymentPrefs(peer)
            val seam = PaymentSeam(peer, failFirstProposalWith = OrchardSealedException(orchardAvailable = Zatoshi(AMOUNT)))

            val vm = paymentViewModel(prefs, seam)
            vm.fulfillPaymentRequest(peer, AMOUNT, requestId)
            awaitIdle(vm)
            assertFalse("a refused payment must not mark the request paid", requestId in prefs.paid)

            vm.fulfillPaymentRequest(peer, AMOUNT, requestId)
            awaitIdle(vm)

            assertEquals("fixture sanity", emptyList<String>(), prefs.unexpectedCalls)
            assertEquals(1, seam.submits.get())
            assertTrue(requestId in prefs.paid)
        }

    /** Control: a rejection that stored nothing proves nothing was sent, so the request stays payable. */
    @Test
    fun `control - a rejection that stored nothing leaves the request payable`() =
        runBlocking {
            val peer = "usyntheticmarkbefore00000005"
            val requestId = "synthetic-c3-request-nothing-5"
            val prefs = PaymentPrefs(peer)
            val seam =
                PaymentSeam(
                    peer,
                    firstSubmitOutcome = SubmitResult.Failure(txIds = emptyList(), code = 0, description = "synthetic"),
                )

            val vm = paymentViewModel(prefs, seam)
            vm.fulfillPaymentRequest(peer, AMOUNT, requestId)
            awaitIdle(vm)
            assertFalse("nothing was stored, so the request must not stay marked paid", requestId in prefs.paid)

            vm.fulfillPaymentRequest(peer, AMOUNT, requestId)
            awaitIdle(vm)

            assertEquals("fixture sanity", emptyList<String>(), prefs.unexpectedCalls)
            assertEquals(2, seam.submits.get())
            assertTrue(requestId in prefs.paid)
        }

    // --------------------------------------------------------------- harness

    /** A process restart forgets the process-wide in-flight claims (ChatViewModel companion). */
    private fun clearProcessClaims() {
        val field =
            ChatViewModel::class.java
                .getDeclaredField("fulfilInFlight")
                .apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        (field.get(null) as MutableSet<String>).clear()
    }

    /**
     * [prefs], except that marking a request paid reports that the write did not reach the disk. The id is still
     * added to the in-memory set, as Android's SharedPreferencesImpl keeps a value whose commit() failed.
     */
    private fun markerNotSaved(prefs: PaymentPrefs): ZchatPreferences =
        Proxy.newProxyInstance(
            ZchatPreferences::class.java.classLoader,
            arrayOf(ZchatPreferences::class.java),
        ) { p, method, args ->
            when (method.name) {
                "markRequestPaid" -> {
                    prefs.paid.add(args!![0] as String)
                    false
                }
                // Round 8 P2: the fulfil writes its paid marker together with its started record in one write.
                "markFulfilStarted" -> {
                    prefs.paid.add(args!![0] as String)
                    false
                }
                "toString" -> "MarkerNotSaved"
                "hashCode" -> System.identityHashCode(p)
                "equals" -> p === args?.get(0)
                else ->
                    try {
                        method.invoke(prefs.proxy, *(args ?: emptyArray()))
                    } catch (e: InvocationTargetException) {
                        throw e.targetException
                    }
            }
        } as ZchatPreferences
}
