package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.common.datasource.StoredTransaction
import co.electriccoin.zcash.ui.common.model.SubmitResult
import co.electriccoin.zcash.ui.screen.chat.model.PaymentRequestInfo
import co.electriccoin.zcash.ui.screen.chat.view.ChatResourceText
import co.electriccoin.zcash.ui.screen.chat.viewmodel.FulfilStoredIdsHarness.Companion.paidInMemory
import co.electriccoin.zcash.ui.screen.chat.viewmodel.FulfilStoredIdsHarness.Companion.paymentPending
import co.electriccoin.zcash.ui.screen.chat.viewmodel.HandshakeHarness.awaitIdle
import co.electriccoin.zcash.ui.screen.chat.viewmodel.SendAttemptHarness.H
import co.electriccoin.zcash.ui.screen.chat.viewmodel.SendAttemptHarness.Wallet
import co.electriccoin.zcash.ui.screen.chat.viewmodel.SendAttemptHarness.txId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * F5 (fix-all 2.13.0, train T5; SPECS §1.3 P1; harm 1 of §1.1): a paid request says "Payment pending" until the chain
 * shows the payment mined, and only then "Paid".
 *
 * At 3df251d7f the fulfil marks the request paid in onSubmitStarting and the bubble says "Paid" from that moment:
 * pending, unconfirmed, or a payment that later expires unmined all read "Paid". PaymentRequestInfo has only isPaid
 * (which hides Pay), and the bubble's "Paid" is an English literal.
 *
 * Wanted: `PaymentRequestInfo.paymentPending`. Right after Pay, and on every pass while the payment is not mined, the
 * request row has isPaid (Pay stays hidden) AND paymentPending; once it is mined, paymentPending is false, isPaid stays,
 * and the started record is cleared. The bubble takes `paymentPending` and shows R.string.chat_request_payment_pending
 * or R.string.chat_request_paid (English and Spanish), with no "Paid" literal. Runs the real fulfil (real use case and
 * repository) and the real history pass over [FulfilStoredIdsHarness]. All ids are synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FulfilPendingShowsPaymentPendingNotPaidTest {
    private val sendScopes = SendHarnessScopes()

    private companion object {
        const val PEER = "usyntheticf5pendingpeer000001"
        const val AMOUNT = 1_000L
        const val E = H + 40
        const val T_NUMBER = 0xF5101
        const val DIALOGS = "ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/chat/view/ChatDialogs.kt"
        const val DETAIL = "ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/chat/view/ChatDetailView.kt"
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
    fun `the request info declares paymentPending`() {
        val field = PaymentRequestInfo::class.java.declaredFields.firstOrNull { it.name == "paymentPending" }
        assertNotNull("PaymentRequestInfo has no paymentPending: a pending payment can only say Paid", field)
        assertEquals(Boolean::class.javaPrimitiveType, field!!.type)
    }

    @Test
    fun `a pending payment says Payment pending until it is mined, then Paid`() =
        runBlocking {
            val requestId = "synthetic-f5-p1-request-a"
            val h = FulfilStoredIdsHarness(PEER)
            val t = txId(T_NUMBER)
            val seam = FulfilStoredIdsHarness.IdsSeam(PEER, listOf(StoredTransaction(t, E)), SubmitResult.Success(listOf(t)))
            val vm = h.viewModel(seam, requestId, AMOUNT)
            PaymentHarness.setBlockHeight(vm, E - 40)
            vm.fulfillPaymentRequest(PEER, AMOUNT, requestId)
            awaitIdle(vm)
            assertEquals("fixture: the payment was handed over once", 1, seam.submits.get())
            assertTrue("fixture: the request is marked paid", requestId in h.prefs.paid)

            val atPay = PaymentHarness.pendingMessages(vm).single { it.id == requestId }
            assertEquals("right after Pay, Pay must be hidden", true, atPay.paymentRequest?.isPaid)
            assertEquals("right after Pay the bubble must say Payment pending, not Paid", true, paymentPending(atPay))

            val wallet = Wallet(scanned = E - 39, txs = listOf(h.fulfilTx(T_NUMBER, requestId, null, E, AMOUNT)))
            h.installWallet(vm, wallet)
            h.passAt(vm, wallet, E - 38)
            val pending = h.passAt(vm, wallet, E - 37).single { it.id == requestId }
            assertEquals("a pending payment hid Pay", true, pending.paymentRequest?.isPaid)
            assertEquals("a payment the chain has not mined is shown as Paid", true, paymentPending(pending))

            wallet.txs = listOf(h.fulfilTx(T_NUMBER, requestId, E - 30, E, AMOUNT))
            h.passAt(vm, wallet, E - 20)
            val mined = h.passAt(vm, wallet, E - 19).single { it.id == requestId }
            assertEquals("a mined payment offers Pay again", true, mined.paymentRequest?.isPaid)
            assertEquals("a mined payment still says Payment pending", false, paymentPending(mined))
            assertTrue("the paid marker went away", requestId in h.prefs.paid && requestId in paidInMemory(vm))
            assertFalse("the started record of a mined payment is kept", h.prefs.started.containsKey(requestId))
            assertEquals("fixture sanity", emptyList<String>(), h.prefs.unexpectedCalls)
        }

    @Test
    fun `the bubble shows pending or paid from string resources`() {
        val dialogs = ChatResourceText.source(DIALOGS)
        val content = dialogs.substringAfter("internal fun PaymentRequestContent(")
        val signature = content.substringBefore(") {")
        assertTrue(
            "PaymentRequestContent takes no paymentPending: $signature",
            Regex("""\bpaymentPending\s*:\s*Boolean""").containsMatchIn(signature),
        )
        val body = content.substringBefore("\n}\n")
        assertTrue("the bubble does not say Payment pending", "R.string.chat_request_payment_pending" in body)
        assertTrue("the bubble's Paid is not a string resource", "R.string.chat_request_paid" in body)
        assertFalse("the bubble still holds the English literal Paid", Regex("""text\s*=\s*"Paid"""").containsMatchIn(body))

        val call =
            ChatResourceText
                .source(DETAIL)
                .substringAfter("PaymentRequestContent(")
                .substringBefore("\n                            )")
        assertTrue(
            "the chat does not pass the row's paymentPending to the bubble: $call",
            Regex("""paymentPending\s*=\s*message\.paymentRequest\.paymentPending""").containsMatchIn(call),
        )
    }

    @Test
    fun `pending and paid have English and Spanish texts`() {
        val en = ChatResourceText.strings("values")
        val es = ChatResourceText.strings("values-es")
        for (name in listOf("chat_request_payment_pending", "chat_request_paid")) {
            assertNotNull("no English $name", en[name])
            assertNotNull("no Spanish $name", es[name])
            assertNotEquals("the Spanish $name is the English text", en[name], es[name])
        }
        assertEquals("Paid", en["chat_request_paid"])
        assertFalse(
            "Payment pending must not say it was paid",
            en.getValue("chat_request_payment_pending").contains("paid", ignoreCase = true),
        )
    }
}
