package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.screen.chat.model.ConversationMode
import co.electriccoin.zcash.ui.screen.chat.model.MessageStatus
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * P2a follow-up (review finding 7) — Retry on a failed FREE (Nostr) reply while the message-cost notice is
 * not accepted.
 *
 * retryMessage claims the row (SENDING, persisted) and calls sendReply with the retry ID. At 1a650c932 sendReply
 * checks the cost notice before it routes over Nostr, parks the reply in the cost prompt WITHOUT its retry ID
 * and reports it accepted. Dismissing the prompt leaves the claimed row stuck as "sending"; after a reopen the
 * row reads as FAILED while its stored copy is still in flight, so Retry refuses it with a false "may already
 * have been sent — paying twice" error. (Accepting the prompt would send a new copy instead.)
 *
 * The notice is stored once in the settings store; that store is reset on its own (corrupt keyset recovery)
 * while the pending store survives, which is how a failed free reply can exist with the notice unaccepted.
 * A free reply's Retry never falls through to a paid transport, so the notice has nothing to guard there.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FreeReplyRetryNeverDefersTest {
    private val sendScopes = SendHarnessScopes()

    private companion object {
        const val PEER = "usyntheticfreereplyretry0001"
        const val CHOSEN = 50_000L
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
    fun `a free reply retry is never parked behind the cost prompt and stays retryable after a reopen`() =
        runBlocking {
            // OPEN without the peer's NOSTR key: every publish attempt fails, free, and is retryable.
            val prefs = PaymentPrefs(PEER, mode = ConversationMode.OPEN)
            val seam = PaymentSeam(PEER)
            val screen = paymentViewModel(prefs, seam)
            assertTrue(screen.sendReply(PEER, "synthetic free reply", "synthetic-quoted-id", "synthetic quote", CHOSEN))
            awaitIdle(screen)
            val failed = PaymentHarness.pendingMessages(screen).single()
            assertEquals("fixture sanity", MessageStatus.FAILED, failed.status)
            assertEquals("fixture sanity", "synthetic-quoted-id", failed.replyToId)

            // The settings store was reset; the pending store kept the failed reply.
            prefs.costAcknowledged = false
            screen.retryMessage(PEER, failed.id)
            awaitIdle(screen)
            assertFalse("a free reply retry was parked behind the paid-message cost prompt", costPromptShown(screen))
            screen.dismissCostDisclaimer()

            val reopened = paymentViewModel(prefs, seam)
            PaymentHarness.loadPendingMessagesFromPrefs(reopened)
            val restored = PaymentHarness.pendingMessages(reopened).single()
            assertEquals(MessageStatus.FAILED, restored.status)
            assertTrue(
                "the free reply is refused as 'may already have been sent' after the prompt stranded its retry claim",
                reopened.retryMessage(PEER, restored.id),
            )
            awaitIdle(reopened)
            assertEquals("a free reply must never create an on-chain proposal", 0, seam.proposalUris.size)
            assertEquals("fixture sanity", emptyList<String>(), prefs.unexpectedCalls)
        }

    @Suppress("UNCHECKED_CAST")
    private fun costPromptShown(vm: ChatViewModel): Boolean =
        (
            ChatViewModel::class.java
                .getDeclaredField("_showCostDisclaimer")
                .apply { isAccessible = true }
                .get(vm) as MutableStateFlow<Boolean>
        ).value
}
