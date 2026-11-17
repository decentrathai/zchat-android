package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.nostr.NostrChatBridge
import co.electriccoin.zcash.ui.screen.chat.model.ConversationMode
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * SR-3 — DATA LOSS + FALSE DELIVERY CLAIM: a payment request sent in a TUNNEL/OPEN chat is published over
 * NOSTR, and at b256fcb3f sendPaymentRequest then deletes its persisted row unconditionally and toasts
 * "Payment request sent" whatever happened.
 *
 * - Not connected (bridge not ready) or 0 relay acks: the toast says sent while nothing was delivered.
 * - Any outcome: the persisted row is removed, so after a chat reopen or restart the requester has no
 *   record of what they asked for. The row was also written without its amount/reason and never re-keyed
 *   to the shared `nmsg-<rumorId>` id, unlike every other NOSTR send (publishNostrAndRenderLocal).
 *
 * No ZEC moves on this path (a NOSTR request is never sent on-chain), so the seam must record no proposal.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PaymentRequestNostrPersistsTest {
    private val sendScopes = SendHarnessScopes()

    private companion object {
        const val PEER = "usyntheticpayreqnostrpeer001"
        const val PEER_NOSTR = "synthetic-peer-nostr-pubkey"
        const val AMOUNT = 25_000_000L
        const val REASON = "synthetic dinner"
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
    fun `a request that could not be published is kept as FAILED with its amount and never claims sent`() =
        runBlocking {
            val prefs = PaymentPrefs(PEER, mode = ConversationMode.TUNNEL, peerNostrPubkey = PEER_NOSTR)
            val seam = PaymentSeam(PEER)
            val vm = paymentViewModel(prefs, seam)

            // The NOSTR publisher is not registered: the chat is not connected.
            vm.sendPaymentRequest(PEER, AMOUNT, REASON)
            awaitIdle(vm)

            assertEquals("fixture sanity", emptyList<String>(), prefs.unexpectedCalls)
            assertNoFalseSentClaim(PaymentHarness.sendMessageState(vm))
            assertFailedRowKept(prefs)
            assertEquals("a NOSTR payment request must never go on-chain", 0, seam.proposalUris.size)
        }

    @Test
    fun `a request no relay acknowledged is kept as FAILED with its amount and never claims sent`() =
        runBlocking {
            val prefs = PaymentPrefs(PEER, mode = ConversationMode.TUNNEL, peerNostrPubkey = PEER_NOSTR)
            val seam = PaymentSeam(PEER)
            NostrChatBridge.registerPublisher { _, _ -> NostrChatBridge.PublishResult(acks = 0, messageId = "r0") }
            val vm = paymentViewModel(prefs, seam)

            vm.sendPaymentRequest(PEER, AMOUNT, REASON)
            awaitIdle(vm)

            assertEquals("fixture sanity", emptyList<String>(), prefs.unexpectedCalls)
            assertNoFalseSentClaim(PaymentHarness.sendMessageState(vm))
            assertFailedRowKept(prefs)
            assertEquals(0, seam.proposalUris.size)
        }

    @Test
    fun `an acknowledged request is persisted under its shared id with its amount and survives a reopen`() =
        runBlocking {
            val prefs = PaymentPrefs(PEER, mode = ConversationMode.OPEN, peerNostrPubkey = PEER_NOSTR)
            val seam = PaymentSeam(PEER)
            NostrChatBridge.registerPublisher { _, _ -> NostrChatBridge.PublishResult(acks = 1, messageId = "r1") }
            val vm = paymentViewModel(prefs, seam)

            vm.sendPaymentRequest(PEER, AMOUNT, REASON)
            awaitIdle(vm)

            assertEquals("fixture sanity", emptyList<String>(), prefs.unexpectedCalls)
            assertEquals(SendMessageState.Success("Payment request sent"), PaymentHarness.sendMessageState(vm))
            val row = prefs.pending["nmsg-r1"]
            assertNotNull(
                "the delivered request was deleted from storage (rows: ${prefs.pending.keys}); it vanishes on reopen",
                row,
            )
            row!!
            assertEquals(AMOUNT, row.paymentRequestAmountZatoshi)
            assertEquals(REASON, row.paymentRequestReason)
            assertTrue(row.isOutgoing)
            assertFalse(row.isPending)
            assertEquals(MessageStatus.SENT.name, row.status)
            assertEquals("no stale pending_ row may remain", setOf("nmsg-r1"), prefs.pending.keys)
            assertEquals(0, seam.proposalUris.size)

            // The chat is reopened: a fresh ChatViewModel rebuilds the bubble from storage.
            val reopen = paymentViewModel(prefs, seam)
            PaymentHarness.loadPendingMessagesFromPrefs(reopen)
            val bubble = PaymentHarness.pendingMessages(reopen).single { it.id == "nmsg-r1" }
            assertTrue(bubble.isOutgoing)
            assertEquals(MessageStatus.SENT, bubble.status)
            assertEquals(AMOUNT, bubble.paymentRequest?.amountZatoshi)
        }

    private fun assertNoFalseSentClaim(state: SendMessageState) {
        assertFalse(
            "the toast claims the payment request was sent although nothing was delivered (state: $state)",
            state is SendMessageState.Success,
        )
        assertTrue("an undelivered request must surface as an error (state: $state)", state is SendMessageState.Error)
    }

    private fun assertFailedRowKept(prefs: PaymentPrefs) {
        val row = prefs.pending.values.singleOrNull()
        assertNotNull("the undelivered request was deleted from storage; it vanishes on reopen", row)
        row!!
        assertEquals(AMOUNT, row.paymentRequestAmountZatoshi)
        assertEquals(REASON, row.paymentRequestReason)
        assertTrue(row.isOutgoing)
        assertFalse("a FAILED row must not reload as an orphaned in-flight send", row.isPending)
        assertEquals(MessageStatus.FAILED.name, row.status)
    }
}
