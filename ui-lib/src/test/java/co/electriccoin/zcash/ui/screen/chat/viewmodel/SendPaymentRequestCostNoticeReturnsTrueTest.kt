package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.nostr.NostrChatBridge
import co.electriccoin.zcash.ui.screen.chat.model.ConversationMode
import co.electriccoin.zcash.ui.screen.chat.viewmodel.HandshakeHarness.awaitIdle
import co.electriccoin.zcash.ui.screen.chat.viewmodel.PaymentHarness.paymentViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * ZC-250 (fix-all 2.13.0, train T5b; t5b SPECS §6 item 3) — a payment request held behind the one-time "messages cost
 * ZEC" notice answers false.
 *
 * At 3ff26bc9a sendPaymentRequest stores the request behind its cost notice (pendingMessage, shown by
 * _showCostDisclaimer) and returns false, which its KDoc calls "refused before anything was stored or sent". Once the
 * Request Payment dialog closes only on true (ZC-250: a refused request keeps the typed amount and reason), that false
 * would leave the dialog open behind the notice, and a second Send there would hold a second request.
 *
 * Wanted: the request behind its notice answers true (its question is on screen, as the KDoc says), the notice shows,
 * the request is held, and nothing is sent, stored as a row or built yet. Synthetic addresses only; no money.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SendPaymentRequestCostNoticeReturnsTrueTest {
    private val sendScopes = SendHarnessScopes()

    private companion object {
        const val PEER = "usyntheticzc250costnotice01"
        const val AMOUNT = 10_000L
        const val REASON = "synthetic zc250 cost notice"
    }

    @Before
    fun setUp() {
        sendScopes.open()
        Dispatchers.setMain(UnconfinedTestDispatcher())
        NostrChatBridge.unregisterPublisher()
    }

    @After
    fun tearDown() {
        sendScopes.close()
        NostrChatBridge.unregisterPublisher()
        Dispatchers.resetMain()
    }

    @Test
    fun `a request held behind the cost notice answers true and is held, not sent`() =
        runBlocking {
            val prefs = PaymentPrefs(PEER, mode = ConversationMode.TUNNEL).apply { costAcknowledged = false }
            val seam = PaymentSeam(PEER)
            val vm = paymentViewModel(prefs, seam)

            val answered = vm.sendPaymentRequest(PEER, AMOUNT, REASON)
            awaitIdle(vm)

            assertTrue("a request held behind its cost notice must answer true, or its dialog stays open behind it", answered)
            assertEquals("the cost notice is not on screen", true, showCostDisclaimer(vm))
            val held =
                ChatViewModel::class.java
                    .getDeclaredField("pendingMessage")
                    .apply { isAccessible = true }
                    .get(vm)
            assertNotNull("the request is not held behind its notice", held)
            assertEquals(AMOUNT, read(held!!, "getPaymentRequestAmount"))
            assertEquals(REASON, read(held, "getPaymentRequestReason"))
            assertEquals("nothing may be built before the notice is accepted", 0, seam.proposalUris.size)
            assertTrue("no row before the notice is accepted", prefs.pending.isEmpty())
            assertEquals("fixture sanity", emptyList<String>(), prefs.unexpectedCalls)
        }

    private fun read(
        target: Any,
        getter: String,
    ): Any? =
        target.javaClass
            .getDeclaredMethod(getter)
            .apply { isAccessible = true }
            .invoke(target)

    @Suppress("UNCHECKED_CAST")
    private fun showCostDisclaimer(vm: ChatViewModel): Boolean =
        (
            ChatViewModel::class.java
                .getDeclaredField("_showCostDisclaimer")
                .apply { isAccessible = true }
                .get(vm) as MutableStateFlow<Boolean>
        ).value
}
