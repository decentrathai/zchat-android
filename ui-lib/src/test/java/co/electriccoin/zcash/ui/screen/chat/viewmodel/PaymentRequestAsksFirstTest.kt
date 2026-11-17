package co.electriccoin.zcash.ui.screen.chat.viewmodel

import cash.z.ecc.android.sdk.model.Zatoshi
import co.electriccoin.zcash.ui.nostr.NostrChatBridge
import co.electriccoin.zcash.ui.screen.chat.model.ChatSendCopy
import co.electriccoin.zcash.ui.screen.chat.model.ConversationMode
import co.electriccoin.zcash.ui.screen.chat.model.SendMessageState
import co.electriccoin.zcash.ui.screen.chat.model.onChainSendMinimumCost
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
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Round 9 R9-1 (REVIEW_7C F10 request side, device LEDGER row 5) — a payment request in a Shielded chat pays on-chain
 * with no question.
 *
 * In a Shielded (VAULT) chat a payment request is one on-chain transaction: the dust output that carries the ZREQ memo,
 * the platform fee and the network fee (17,000 zatoshi on the device, for a second request, with nothing asked). The
 * only gate in sendPaymentRequest is the one-time "messages cost ZEC" notice; the Request dialog shows no cost either.
 * 3d9382db1 fixed only the Pay side of 7c F10.
 *
 * Wanted:
 * - a payment request in a Shielded chat asks first (PaidAction.PAYMENT_REQUEST, one on-chain transaction, at least
 *   onChainSendMinimumCost(1, dust)) before any row is stored or any proposal is made; confirming sends it once;
 *   declining stores nothing and pays nothing;
 * - a Retry of a failed request still asks once with PAYMENT_REQUEST_RETRY and is not asked a second time;
 * - Control: in a Tunnel or Open chat the request goes over the relays, free, with no question.
 *
 * Harness: [PaymentHarness] (the message-cost notice already accepted). All addresses are synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PaymentRequestAsksFirstTest {
    private val sendScopes = SendHarnessScopes()

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
    fun `a payment request in a Shielded chat asks first with its price and stores nothing before the answer`() =
        runBlocking {
            val prefs = PaymentPrefs(PEER, mode = ConversationMode.VAULT)
            val seam = PaymentSeam(PEER)
            val vm = paymentViewModel(prefs, seam)
            PaymentHarness.setField(vm, "accountDataSource", fundedAccounts()) // ZC-1822: a wallet that can pay

            vm.sendPaymentRequest(PEER, AMOUNT, REASON)
            awaitIdle(vm)

            assertEquals("the request was paid on-chain with no question on screen", 0, seam.proposalUris.size)
            assertEquals("nothing may be submitted before the answer", 0, seam.submits.get())
            assertTrue("a row was stored before the answer: ${prefs.pending.keys}", prefs.pending.isEmpty())
            assertTrue(
                "a bubble was shown before the answer",
                PaymentHarness.pendingMessages(vm).isEmpty(),
            )
            val confirmation = askedConfirmation(vm)
            assertEquals("PAYMENT_REQUEST", action(confirmation))
            assertEquals(1, confirmation.javaClass.getMethod("getOnChainMessages").invoke(confirmation))
            val total = confirmation.javaClass.getMethod("getTotalCost").invoke(confirmation) as Zatoshi
            val floor = onChainSendMinimumCost(1, Zatoshi(DUST))
            assertTrue("quoted ${total.value} is below the ${floor.value} one request costs", total.value >= floor.value)

            ChatViewModel::class.java.getMethod("confirmPaidAction").invoke(vm)
            awaitIdle(vm)

            assertEquals("confirming sends the request once", 1, seam.submits.get())
            assertEquals("fixture sanity", emptyList<String>(), prefs.unexpectedCalls)
        }

    @Test
    fun `declining the payment request question stores nothing and pays nothing`() =
        runBlocking {
            val prefs = PaymentPrefs(PEER, mode = ConversationMode.VAULT)
            val seam = PaymentSeam(PEER)
            val vm = paymentViewModel(prefs, seam)
            PaymentHarness.setField(vm, "accountDataSource", fundedAccounts()) // ZC-1822: a wallet that can pay

            vm.sendPaymentRequest(PEER, AMOUNT, REASON)
            awaitIdle(vm)
            assertEquals(
                "the question was never shown",
                "NeedsPaidActionConfirmation",
                PaymentHarness.sendMessageState(vm).javaClass.simpleName,
            )
            ChatViewModel::class.java.getMethod("dismissPaidAction").invoke(vm)
            awaitIdle(vm)

            assertEquals("declining must pay nothing", 0, seam.proposalUris.size)
            assertEquals("declining must submit nothing", 0, seam.submits.get())
            assertTrue("declining must store no row: ${prefs.pending.keys}", prefs.pending.isEmpty())
            assertTrue("declining must show no bubble", PaymentHarness.pendingMessages(vm).isEmpty())
        }

    @Test
    fun `a retried request that goes on-chain is asked once, as a retry, and not asked again`() =
        runBlocking {
            val prefs = PaymentPrefs(PEER, mode = ConversationMode.TUNNEL, peerNostrPubkey = PEER_NOSTR)
            val seam = PaymentSeam(PEER)
            val vm = paymentViewModel(prefs, seam)
            PaymentHarness.setField(vm, "accountDataSource", fundedAccounts()) // ZC-1822: a wallet that can pay
            vm.sendPaymentRequest(PEER, AMOUNT, REASON) // no relay: kept as FAILED, free
            awaitIdle(vm)
            val failed = PaymentHarness.pendingMessages(vm).single()

            prefs.mode = ConversationMode.VAULT
            vm.retryMessage(PEER, failed.id)
            awaitIdle(vm)
            assertEquals("PAYMENT_REQUEST_RETRY", action(askedConfirmation(vm)))

            ChatViewModel::class.java.getMethod("confirmPaidAction").invoke(vm)
            awaitIdle(vm)

            assertNotEquals(
                "a confirmed retry was asked a second time",
                "NeedsPaidActionConfirmation",
                PaymentHarness.sendMessageState(vm).javaClass.simpleName,
            )
            assertEquals("the confirmed retry sends once", 1, seam.submits.get())
        }

    /** ZC-1272: in a Shielded chat the price question answers for the cost, so the one-time notice does not come first. */
    @Test
    fun `a first payment request in a Shielded chat with the notice unread asks its question, with no notice`() =
        runBlocking {
            val prefs = PaymentPrefs(PEER, mode = ConversationMode.VAULT)
            prefs.costAcknowledged = false
            val seam = PaymentSeam(PEER)
            val vm = paymentViewModel(prefs, seam)
            PaymentHarness.setField(vm, "accountDataSource", fundedAccounts()) // ZC-1822: a wallet that can pay

            vm.sendPaymentRequest(PEER, AMOUNT, REASON)
            awaitIdle(vm)

            assertFalse("the cost notice stands in front of the request's price question", costNoticeShown(vm))
            assertEquals("PAYMENT_REQUEST", action(askedConfirmation(vm)))
            assertEquals("nothing may be built before the answer", 0, seam.proposalUris.size)
            assertEquals("nothing may be submitted before the answer", 0, seam.submits.get())
            assertTrue("a row was stored before the answer: ${prefs.pending.keys}", prefs.pending.isEmpty())
            assertFalse("the notice was marked read before the answer", prefs.costAcknowledged)

            vm.confirmPaidAction()
            awaitIdle(vm)

            assertTrue("Pay on the question did not mark the notice as read", prefs.costAcknowledged)
            assertFalse("the cost notice followed the question the user confirmed", costNoticeShown(vm))
            assertEquals("confirming sends the request once", 1, seam.submits.get())
        }

    /** ZC-1272: Retry with the notice unread is the question alone, and a Pay on it is never refused as a mode change. */
    @Test
    fun `a Retry of a Shielded request with the notice unread asks only its question and Pay marks it read`() =
        runBlocking {
            val prefs = PaymentPrefs(PEER, mode = ConversationMode.TUNNEL, peerNostrPubkey = PEER_NOSTR)
            val seam = PaymentSeam(PEER)
            val vm = paymentViewModel(prefs, seam)
            PaymentHarness.setField(vm, "accountDataSource", fundedAccounts()) // ZC-1822: a wallet that can pay
            vm.sendPaymentRequest(PEER, AMOUNT, REASON) // no relay: kept as FAILED, free
            awaitIdle(vm)
            val failed = PaymentHarness.pendingMessages(vm).single()

            prefs.mode = ConversationMode.VAULT
            prefs.costAcknowledged = false
            vm.retryMessage(PEER, failed.id)
            awaitIdle(vm)

            assertFalse("the cost notice stands in front of the Retry's price question", costNoticeShown(vm))
            assertEquals("PAYMENT_REQUEST_RETRY", action(askedConfirmation(vm)))
            assertEquals("nothing may be submitted before the answer", 0, seam.submits.get())
            assertFalse("the notice was marked read before the answer", prefs.costAcknowledged)

            vm.confirmPaidAction()
            awaitIdle(vm)

            assertTrue("Pay on the question did not mark the notice as read", prefs.costAcknowledged)
            assertNotEquals(
                "a confirmed Retry was refused as a changed mode",
                SendMessageState.Error(ChatSendCopy.RETRY_MODE_CHANGED),
                PaymentHarness.sendMessageState(vm),
            )
            assertFalse("the cost notice followed the question the user confirmed", costNoticeShown(vm))
            assertEquals("the confirmed Retry sends once", 1, seam.submits.get())
        }

    /** Control, green before and after: an Open chat with the contact's relay key publishes the request, free. */
    @Test
    fun `control - a payment request in an Open chat is published over the relays with no question`() =
        runBlocking {
            assertFreeRequestPublishedUnasked(ConversationMode.OPEN)
        }

    /** Control, green before and after: the same in a Tunnel chat. */
    @Test
    fun `control - a payment request in a Tunnel chat is published over the relays with no question`() =
        runBlocking {
            assertFreeRequestPublishedUnasked(ConversationMode.TUNNEL)
        }

    private suspend fun assertFreeRequestPublishedUnasked(mode: ConversationMode) {
        val prefs = PaymentPrefs(PEER, mode = mode, peerNostrPubkey = PEER_NOSTR)
        val seam = PaymentSeam(PEER)
        val published = mutableListOf<String>()
        NostrChatBridge.registerPublisher { memo, _ ->
            published += memo
            NostrChatBridge.PublishResult(acks = 1, messageId = "r-free-${published.size}")
        }
        val vm = paymentViewModel(prefs, seam)

        vm.sendPaymentRequest(PEER, AMOUNT, REASON)
        awaitIdle(vm)

        assertEquals(SendMessageState.Success("Payment request sent"), PaymentHarness.sendMessageState(vm))
        assertEquals("the free request was published once", 1, published.size)
        assertEquals("a free request never goes on-chain", 0, seam.proposalUris.size)
        assertEquals("fixture sanity", emptyList<String>(), prefs.unexpectedCalls)
    }

    private fun askedConfirmation(vm: ChatViewModel): Any {
        val state = PaymentHarness.sendMessageState(vm)
        assertEquals(
            "the request must be asked about before it is paid for, was $state",
            "NeedsPaidActionConfirmation",
            state.javaClass.simpleName,
        )
        return checkNotNull(state.javaClass.getMethod("getConfirmation").invoke(state))
    }

    private fun action(confirmation: Any): String = (confirmation.javaClass.getMethod("getAction").invoke(confirmation) as Enum<*>).name

    private companion object {
        const val PEER = "usyntheticpayreqasksfirst001"
        const val PEER_NOSTR = "synthetic-peer-nostr-pubkey"
        const val AMOUNT = 25_000_000L
        const val REASON = "synthetic rent"

        /** The dust output a payment request carries its memo in (CreateChunkedMessageProposalUseCase default). */
        const val DUST = 1_000L
    }
}
