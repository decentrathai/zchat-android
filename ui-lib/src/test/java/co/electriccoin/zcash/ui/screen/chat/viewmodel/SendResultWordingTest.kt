package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.common.model.SubmitResult
import co.electriccoin.zcash.ui.nostr.NostrChatBridge
import co.electriccoin.zcash.ui.screen.chat.model.ConversationMode
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * SR-2 — the send toast must say only what is known.
 *
 * The chokepoint returns SubmitResult.GrpcFailure (stored, not delivered yet, re-broadcast by the SDK) and
 * SentUnconfirmed (the settlement call threw after the broadcast) without throwing, so callers can word
 * them. At b256fcb3f only fulfillPaymentRequest does; doSendMessage, sendReply, sendReaction (VAULT) and
 * sendPaymentRequest (VAULT) toast a flat "Message sent" / "Payment request sent". A NOSTR reply toasts
 * "Message sent" before its publish has even resolved, and a Tunnel reaction that is dropped (no NOSTR
 * route, never charged on-chain) toasts "Message sent" too.
 *
 * Drives the real entry points through the real use case and repository; the seam decides the outcome.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SendResultWordingTest {
    private val sendScopes = SendHarnessScopes()

    private companion object {
        const val PEER = "usyntheticsendwordingpeer001"
        const val PEER_NOSTR = "synthetic-wording-peer-nostr"
        const val QUOTED_ID = "synthetic-quoted-message-id"
        val GRPC_FAILURE = SubmitResult.GrpcFailure(txIds = listOf("synthetic-stored-txid"))
        val SENT_UNCONFIRMED = SubmitResult.SentUnconfirmed(txIds = listOf("synthetic-txid"), description = "synthetic")
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
    fun `a message stored but not yet delivered says submitted, not confirmed`() =
        runBlocking {
            val state = vaultSend(GRPC_FAILURE) { sendMessage(PEER, "synthetic hello") }
            assertUnconfirmedWording(state)
        }

    @Test
    fun `a message whose settlement threw after the broadcast says submitted, not confirmed`() =
        runBlocking {
            val state = vaultSend(SENT_UNCONFIRMED) { sendMessage(PEER, "synthetic hello again") }
            assertUnconfirmedWording(state)
        }

    @Test
    fun `an on-chain reply not yet delivered says submitted, not confirmed`() =
        runBlocking {
            val state = vaultSend(GRPC_FAILURE) { sendReply(PEER, "synthetic reply", QUOTED_ID, "synthetic quote") }
            assertUnconfirmedWording(state)
        }

    @Test
    fun `a Vault reaction not yet delivered says submitted, not confirmed`() =
        runBlocking {
            // R2-2 (b): a Shielded reaction asks for its cost first; this drives the send the user confirmed.
            val state =
                vaultSend(GRPC_FAILURE) {
                    sendReaction(PEER, QUOTED_ID, "👍")
                    confirmPaidAction()
                }
            assertUnconfirmedWording(state)
        }

    @Test
    fun `a Vault payment request not yet confirmed says submitted, not confirmed`() =
        runBlocking {
            // Round 9 R9-1: a Shielded payment request asks for its cost first; this drives the send the user confirmed.
            val state =
                vaultSend(SENT_UNCONFIRMED) {
                    sendPaymentRequest(PEER, 10_000_000L, "synthetic rent")
                    confirmPaidAction()
                }
            assertUnconfirmedWording(state)
        }

    @Test
    fun `a NOSTR reply never toasts sent before any relay has taken it`() =
        runBlocking {
            val prefs = PaymentPrefs(PEER, mode = ConversationMode.TUNNEL, peerNostrPubkey = PEER_NOSTR)
            NostrChatBridge.registerPublisher { _, _ -> NostrChatBridge.PublishResult(acks = 0, messageId = null) }
            val vm = paymentViewModel(prefs, PaymentSeam(PEER))

            vm.sendReply(PEER, "synthetic tunnel reply", QUOTED_ID, "synthetic quote")
            awaitIdle(vm)

            assertEquals("fixture sanity", emptyList<String>(), prefs.unexpectedCalls)
            val state = PaymentHarness.sendMessageState(vm)
            assertFalse(
                "a reply no relay acknowledged was toasted as sent (state: $state); the bubble shows FAILED",
                state is SendMessageState.Success,
            )
            assertFalse("the send must not stay in Sending", state is SendMessageState.Sending)
            val row = prefs.pending.values.single()
            assertEquals("the reply went over NOSTR and its bubble resolved FAILED", "FAILED", row.status)
            assertEquals(QUOTED_ID, row.replyToId)
        }

    @Test
    fun `a Tunnel reaction dropped for lack of a NOSTR route never toasts sent`() =
        runBlocking {
            // Tunnel chat whose peer NOSTR key is not known yet: the reaction is dropped, never charged.
            val prefs = PaymentPrefs(PEER, mode = ConversationMode.TUNNEL, peerNostrPubkey = null)
            val seam = PaymentSeam(PEER)
            val vm = paymentViewModel(prefs, seam)

            vm.sendReaction(PEER, QUOTED_ID, "👍")
            awaitIdle(vm)

            assertEquals("fixture sanity", emptyList<String>(), prefs.unexpectedCalls)
            val state = PaymentHarness.sendMessageState(vm)
            assertFalse("a dropped reaction was toasted as sent (state: $state)", state is SendMessageState.Success)
            assertEquals("a Tunnel reaction must never go on-chain", 0, seam.proposalUris.size)
        }

    /** QA2 Q2-43: an on-chain send says the network took it and that it is confirming, not "Message sent". */
    @Test
    fun `a delivered on-chain message says it is sent to the Zcash network and confirming`() =
        runBlocking {
            val state = vaultSend(SubmitResult.Success(txIds = listOf("synthetic-txid"))) { sendMessage(PEER, "synthetic ok") }
            assertEquals(SendMessageState.Success("Sent to the Zcash network — confirming"), state)
        }

    private suspend fun vaultSend(
        outcome: SubmitResult,
        send: ChatViewModel.() -> Unit,
    ): SendMessageState {
        val prefs = PaymentPrefs(PEER)
        val seam = PaymentSeam(PEER, firstSubmitOutcome = outcome)
        val vm = paymentViewModel(prefs, seam)
        PaymentHarness.setField(vm, "accountDataSource", fundedAccounts()) // ZC-1822: a wallet that can pay
        vm.send()
        awaitIdle(vm)
        assertEquals("fixture sanity", emptyList<String>(), prefs.unexpectedCalls)
        assertEquals("the send must reach settlement exactly once", 1, seam.submits.get())
        return PaymentHarness.sendMessageState(vm)
    }

    private fun assertUnconfirmedWording(state: SendMessageState) {
        assertTrue(
            "an outcome the chokepoint reports as not delivered / not confirmed was toasted as a plain success " +
                "(state: $state); it must say it is submitted but not confirmed",
            state is SendMessageState.Success && state.label.contains("not confirmed", ignoreCase = true),
        )
    }
}
