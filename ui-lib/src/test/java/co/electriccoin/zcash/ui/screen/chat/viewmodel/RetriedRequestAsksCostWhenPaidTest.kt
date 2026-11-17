package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.nostr.NostrChatBridge
import co.electriccoin.zcash.ui.screen.chat.model.ConversationMode
import co.electriccoin.zcash.ui.screen.chat.model.MessageStatus
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
import org.junit.Before
import org.junit.Test

/**
 * R2-2 (d), sub-item of p2-e357106bb0b6 — a retried free payment request silently becomes a paid one.
 *
 * A payment request in a Tunnel chat travels free over Nostr. When no relay takes it, it is kept as FAILED
 * with Retry (P2h). If the user then switches the chat to Shielded and taps Retry, sendPaymentRequest sends it
 * on-chain. At 2a73ffa23 the only check is the one-time "messages cost ZEC" notice, accepted long ago, so the
 * Retry pays a transaction the user never saw a cost for: they tapped Retry on something that was free.
 *
 * Wanted: that Retry shows the cost (one on-chain transaction, its least total) and pays nothing until the user
 * confirms; confirming pays exactly one.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RetriedRequestAsksCostWhenPaidTest {
    private val sendScopes = SendHarnessScopes()

    @Before
    fun setUp() {
        sendScopes.open()
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        sendScopes.close()
        NostrChatBridge.unregisterPublisher()
        Dispatchers.resetMain()
    }

    @Test
    fun `retrying a failed free request after switching to Shielded asks before paying`() =
        runBlocking {
            val prefs = PaymentPrefs(PEER, mode = ConversationMode.TUNNEL, peerNostrPubkey = PEER_NOSTR)
            val seam = PaymentSeam(PEER)
            val vm = paymentViewModel(prefs, seam)
            PaymentHarness.setField(vm, "accountDataSource", fundedAccounts()) // ZC-1822: a wallet that can pay

            vm.sendPaymentRequest(PEER, AMOUNT, REASON) // no relay: kept as FAILED, free
            awaitIdle(vm)
            val failed = PaymentHarness.pendingMessages(vm).single()
            assertEquals("fixture sanity", MessageStatus.FAILED, failed.status)
            assertEquals("fixture sanity: the request was free", 0, seam.proposalUris.size)

            prefs.mode = ConversationMode.VAULT // the user switched the chat to Shielded
            vm.retryMessage(PEER, failed.id)
            awaitIdle(vm)

            assertEquals(
                "Retry turned a free request into a paid on-chain one with no cost shown",
                0,
                seam.proposalUris.size,
            )
            val state = PaymentHarness.sendMessageState(vm)
            assertEquals("the cost must be shown, was $state", "NeedsPaidActionConfirmation", state.javaClass.simpleName)
            val confirmation = state.javaClass.getMethod("getConfirmation").invoke(state)
            assertEquals(1, confirmation.javaClass.getMethod("getOnChainMessages").invoke(confirmation))

            ChatViewModel::class.java.getMethod("confirmPaidAction").invoke(vm)
            awaitIdle(vm)
            assertEquals("confirming pays the request once", 1, seam.proposalUris.size)
        }

    /** Control (passes before and after): the same Retry in a chat that is still free is published, not paid. */
    @Test
    fun `control - retrying the failed request in a free chat publishes it without asking`() =
        runBlocking {
            val prefs = PaymentPrefs(PEER, mode = ConversationMode.TUNNEL, peerNostrPubkey = PEER_NOSTR)
            val seam = PaymentSeam(PEER)
            val vm = paymentViewModel(prefs, seam)

            vm.sendPaymentRequest(PEER, AMOUNT, REASON)
            awaitIdle(vm)
            val failed = PaymentHarness.pendingMessages(vm).single()
            val published = mutableListOf<String>()
            NostrChatBridge.registerPublisher { memo, _ ->
                published += memo
                NostrChatBridge.PublishResult(acks = 1, messageId = "r-free")
            }

            vm.retryMessage(PEER, failed.id)
            awaitIdle(vm)

            assertEquals(1, published.size)
            assertEquals(0, seam.proposalUris.size)
        }

    private companion object {
        const val PEER = "usyntheticretriedrequest0001"
        const val PEER_NOSTR = "synthetic-peer-nostr-pubkey"
        const val AMOUNT = 25_000_000L
        const val REASON = "synthetic rent"
    }
}
