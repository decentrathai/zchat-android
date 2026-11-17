package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.screen.chat.model.MessageStatus
import co.electriccoin.zcash.ui.screen.chat.model.SendMessageState
import co.electriccoin.zcash.ui.screen.chat.viewmodel.HandshakeHarness.awaitIdle
import co.electriccoin.zcash.ui.screen.chat.viewmodel.PaymentHarness.firstOutputAmount
import co.electriccoin.zcash.ui.screen.chat.viewmodel.PaymentHarness.paymentViewModel
import co.electriccoin.zcash.ui.screen.chat.viewmodel.PaymentHarness.zec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * NEW retryMessage finding (batch A4): Retry on a FAILED message the user sent with a chosen amount re-sends
 * it at the 1000-zatoshi default.
 *
 * The chat composer lets the user pick the value carried by a message (ChatViewModel.MESSAGE_AMOUNTS). At
 * b256fcb3f neither the in-memory bubble nor the persisted pending row keeps that amount, and retryMessage
 * calls sendMessage(peer, raw) with the default. The retried message then carries a different value from the
 * one the user chose.
 *
 * Drives the real sendMessage / retryMessage through the real use case; the amount is read from the ZIP-321
 * URI the use case builds for each attempt.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RetryKeepsChosenAmountTest {
    private val sendScopes = SendHarnessScopes()

    private companion object {
        const val PEER = "usyntheticretryamountpeer001"
        const val CHOSEN = 50_000L // 0.0005 ZEC, one of the composer's amounts
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
    fun `retrying a failed message re-sends it with the amount the user chose`() =
        runBlocking {
            val prefs = PaymentPrefs(PEER)
            // The first attempt fails before anything is sent (the node is unreachable while proposing).
            val seam = PaymentSeam(PEER, failFirstProposalWith = IllegalStateException("synthetic node unreachable"))
            val vm = paymentViewModel(prefs, seam)

            vm.sendMessage(PEER, "synthetic paid hello", CHOSEN)
            awaitIdle(vm)
            val failed = PaymentHarness.pendingMessages(vm).single { it.status == MessageStatus.FAILED }
            assertEquals(
                "fixture sanity: the first attempt carried the chosen amount",
                zec(CHOSEN),
                firstOutputAmount(seam.proposalUris[0]),
            )

            assertTrue(vm.retryMessage(PEER, failed.id))
            awaitIdle(vm)

            assertEquals("fixture sanity", emptyList<String>(), prefs.unexpectedCalls)
            assertEquals(2, seam.proposalUris.size)
            assertEquals(
                "Retry re-sent the message with a different amount from the one the user chose",
                zec(CHOSEN),
                firstOutputAmount(seam.proposalUris[1]),
            )
        }

    @Test
    fun `retrying an interrupted message after a reopen re-sends it with the amount the user chose`() =
        runBlocking {
            val prefs = PaymentPrefs(PEER)
            val seam = PaymentSeam(PEER)
            val first = paymentViewModel(prefs, seam)
            // Another send is in flight, so this one is queued and persisted as pending...
            PaymentHarness.setSendMessageState(first, SendMessageState.Sending)
            first.sendMessage(PEER, "synthetic queued paid hello", CHOSEN)
            assertEquals("fixture sanity: the queued send is persisted", 1, prefs.pending.size)

            // ...and the app is closed before it went out. The reopened chat shows it FAILED with Retry.
            // ZC-1071: a closed app is a new process, whose live-sender registry is empty (the first screen's run is only held here).
            LiveSenders.resetForTest()
            val reopen = paymentViewModel(prefs, seam)
            PaymentHarness.loadPendingMessagesFromPrefs(reopen)
            val orphan = PaymentHarness.pendingMessages(reopen).single()
            assertEquals(MessageStatus.FAILED, orphan.status)

            assertTrue(reopen.retryMessage(PEER, orphan.id))
            awaitIdle(reopen)

            assertEquals("fixture sanity", emptyList<String>(), prefs.unexpectedCalls)
            assertEquals(1, seam.proposalUris.size)
            assertEquals(
                "Retry after a reopen re-sent the message with a different amount from the one the user chose",
                zec(CHOSEN),
                firstOutputAmount(seam.proposalUris[0]),
            )
        }

    /** Control, GREEN before and after: a default-amount message is retried at the default amount. */
    @Test
    fun `control - a default-amount message is retried at the default amount`() =
        runBlocking {
            val prefs = PaymentPrefs(PEER)
            val seam = PaymentSeam(PEER, failFirstProposalWith = IllegalStateException("synthetic node unreachable"))
            val vm = paymentViewModel(prefs, seam)

            vm.sendMessage(PEER, "synthetic dust hello")
            awaitIdle(vm)
            val failed = PaymentHarness.pendingMessages(vm).single { it.status == MessageStatus.FAILED }
            assertTrue(vm.retryMessage(PEER, failed.id))
            awaitIdle(vm)

            assertEquals("fixture sanity", emptyList<String>(), prefs.unexpectedCalls)
            assertEquals(zec(ChatViewModel.DEFAULT_MESSAGE_AMOUNT), firstOutputAmount(seam.proposalUris[1]))
        }
}
