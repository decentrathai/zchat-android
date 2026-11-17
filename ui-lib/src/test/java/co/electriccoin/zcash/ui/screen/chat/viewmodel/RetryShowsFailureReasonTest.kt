package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.screen.chat.model.ChatMessage
import co.electriccoin.zcash.ui.screen.chat.model.MessageStatus
import co.electriccoin.zcash.ui.screen.chat.model.SendMessageState
import co.electriccoin.zcash.ui.screen.chat.viewmodel.HandshakeHarness.awaitIdle
import co.electriccoin.zcash.ui.screen.chat.viewmodel.PaymentHarness.paymentViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * R2-7 (c), plan step p2-a46ef68ede15 (TXP-F14): Retry on a failed message says why it failed.
 *
 * At bdaa5103b a FAILED bubble shows only "Retry". The reason is a toast that disappears, is lost when the
 * chat is closed, and a message that failed because ZChat was closed mid-send never had one. The bubble's
 * message must carry the reason the user was shown, keep it across a reopen (the pending row), and a
 * message stranded by a closed app must say that.
 *
 * Drives the real sendMessage / loader through the real use case (PaymentHarness).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RetryShowsFailureReasonTest {
    private val sendScopes = SendHarnessScopes()

    private companion object {
        const val PEER = "usyntheticretryreasonpeer001"
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

    private fun reasonOf(message: ChatMessage): String? {
        val getter =
            ChatMessage::class.java.methods.firstOrNull { it.name == "getFailureReason" && it.parameterCount == 0 }
        assertNotNull("ChatMessage keeps no failure reason, so Retry cannot say why the send failed", getter)
        return getter!!.invoke(message) as String?
    }

    @Test
    fun `a failed message keeps the reason the user was shown, also after a reopen`() =
        runBlocking {
            val prefs = PaymentPrefs(PEER)
            val seam = PaymentSeam(PEER, failFirstProposalWith = IllegalStateException("synthetic node unreachable"))
            val vm = paymentViewModel(prefs, seam)

            vm.sendMessage(PEER, "synthetic hello that fails")
            awaitIdle(vm)
            val failed = PaymentHarness.pendingMessages(vm).single { it.status == MessageStatus.FAILED }
            val shown = PaymentHarness.sendMessageState(vm)
            assertTrue("fixture sanity: the failure was reported: $shown", shown is SendMessageState.Error)
            val shownText = (shown as SendMessageState.Error).message

            assertEquals("the failed bubble does not carry the reason the user was shown", shownText, reasonOf(failed))

            val reopen = paymentViewModel(prefs, seam)
            PaymentHarness.loadPendingMessagesFromPrefs(reopen)
            val reloaded = PaymentHarness.pendingMessages(reopen).single { it.id == failed.id }
            assertEquals(MessageStatus.FAILED, reloaded.status)
            assertEquals("the reason is lost when the chat is reopened", shownText, reasonOf(reloaded))
            assertEquals("fixture sanity", emptyList<String>(), prefs.unexpectedCalls)
        }

    @Test
    fun `a message stranded because ZChat was closed says so`() =
        runBlocking {
            val prefs = PaymentPrefs(PEER)
            val seam = PaymentSeam(PEER)
            val first = paymentViewModel(prefs, seam)
            // Another send is in flight, so this one is queued and persisted as pending...
            PaymentHarness.setSendMessageState(first, SendMessageState.Sending)
            first.sendMessage(PEER, "synthetic queued hello")
            assertEquals("fixture sanity: the queued send is persisted", 1, prefs.pending.size)

            // ...and the app is closed before it went out.
            // ZC-1071: a closed app is a new process, whose live-sender registry is empty (the first screen's run is only held here).
            LiveSenders.resetForTest()
            val reopen = paymentViewModel(prefs, seam)
            PaymentHarness.loadPendingMessagesFromPrefs(reopen)
            val orphan = PaymentHarness.pendingMessages(reopen).single()
            assertEquals(MessageStatus.FAILED, orphan.status)
            val reason = reasonOf(orphan)
            assertNotNull("a message stranded by a closed app shows Retry with no reason", reason)
            assertTrue("the reason must say ZChat was closed: $reason", "closed" in reason!!.lowercase())
            assertEquals("fixture sanity", emptyList<String>(), prefs.unexpectedCalls)
        }

    /** Control, GREEN before and after: a message that is sent carries no failure reason. */
    @Test
    fun `control - a sent message has no failure reason`() =
        runBlocking {
            val prefs = PaymentPrefs(PEER)
            val seam = PaymentSeam(PEER)
            val vm = paymentViewModel(prefs, seam)
            vm.sendMessage(PEER, "synthetic hello that goes out")
            awaitIdle(vm)
            assertTrue(PaymentHarness.pendingMessages(vm).none { it.status == MessageStatus.FAILED })
            val getter = ChatMessage::class.java.methods.firstOrNull { it.name == "getFailureReason" }
            if (getter != null) {
                PaymentHarness.pendingMessages(vm).forEach { assertNull(getter.invoke(it)) }
            }
        }
}
