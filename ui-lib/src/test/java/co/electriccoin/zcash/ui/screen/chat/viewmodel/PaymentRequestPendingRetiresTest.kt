package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.common.model.SubmitResult
import co.electriccoin.zcash.ui.nostr.NostrChatBridge
import co.electriccoin.zcash.ui.screen.chat.model.ConversationMode
import co.electriccoin.zcash.ui.screen.chat.model.MessageStatus
import co.electriccoin.zcash.ui.screen.chat.model.ZMSGProtocol
import co.electriccoin.zcash.ui.screen.chat.viewmodel.HandshakeHarness.awaitIdle
import co.electriccoin.zcash.ui.screen.chat.viewmodel.PaymentHarness.paymentViewModel
import co.electriccoin.zcash.ui.screen.chat.viewmodel.PendingReconcileHarness.SentTx
import co.electriccoin.zcash.ui.screen.chat.viewmodel.PendingReconcileHarness.txId
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
 * P2a follow-up (review finding 1) — a payment request's pending row must behave like any other send.
 *
 * At 1a650c932 sendPaymentRequest writes its "pending_" row without submitTracked, records no submit marker
 * and never saves the transaction IDs the SDK returns. Reconciliation retires a "pending_" row only by an
 * exact transaction ID, so an on-chain (VAULT) request that was delivered and mined keeps its pending row for
 * ever: after a reopen it shows as FAILED with "may already have been sent", next to the delivered request.
 * A free (Nostr) request that could not be published keeps the same untracked "pending_" row, and the loader
 * reads every untracked "pending_" FAILED row as a possibly-sent on-chain row from an older build.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PaymentRequestPendingRetiresTest {
    private val sendScopes = SendHarnessScopes()

    private companion object {
        const val PEER = "usyntheticpayreqretire000001"
        const val PEER_NOSTR = "synthetic-peer-nostr-pubkey"
        const val AMOUNT = 25_000_000L
        const val REASON = "synthetic dinner share"
        const val MINED_AT = 3_000_100L
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
    fun `a delivered on-chain payment request is retired once its own transaction is mined`() =
        runBlocking {
            val prefs = PaymentPrefs(PEER, mode = ConversationMode.VAULT)
            val seam = PaymentSeam(PEER, firstSubmitOutcome = SubmitResult.Success(listOf(txId(1))))
            val sender = paymentViewModel(prefs, seam)
            PaymentHarness.setField(sender, "accountDataSource", fundedAccounts()) // ZC-1822: a wallet that can pay

            sender.sendPaymentRequest(PEER, AMOUNT, REASON)
            sender.confirmPaidAction() // Round 9 R9-1: a Shielded request asks for its cost first
            awaitIdle(sender)
            assertEquals("fixture sanity: one on-chain request", 1, seam.submits.get())
            val pendingId =
                prefs.pending.values
                    .single()
                    .id

            val reopened = paymentViewModel(prefs, seam)
            PaymentHarness.setField(reopened, "accountDataSource", fundedAccounts()) // ZC-1822: a wallet that can pay
            PaymentHarness.loadPendingMessagesFromPrefs(reopened)
            val rows =
                PendingReconcileHarness.reconcile(
                    reopened,
                    prefs,
                    listOf(SentTx(1, PEER, ZMSGProtocol.createPaymentRequest(AMOUNT, PaymentHarness.SELF, REASON), MINED_AT)),
                )

            assertEquals("fixture sanity: the mined request was parsed", 1, rows.count { it.id == txId(1) })
            assertFalse(
                "the delivered, mined request kept its pending row; it shows as a FAILED duplicate for ever",
                prefs.pending.containsKey(pendingId),
            )
            assertTrue("the pending row is still rendered next to the delivered request", rows.none { it.id == pendingId })
            assertTrue(PaymentHarness.pendingMessages(reopened).none { it.sendMayHaveGoneOut })
            assertEquals("fixture sanity", emptyList<String>(), prefs.unexpectedCalls)
        }

    @Test
    fun `a free request that was never published does not reload as possibly paid`() =
        runBlocking {
            val prefs = PaymentPrefs(PEER, mode = ConversationMode.TUNNEL, peerNostrPubkey = PEER_NOSTR)
            val seam = PaymentSeam(PEER)
            val sender = paymentViewModel(prefs, seam)

            // No NOSTR publisher is registered: the chat is not connected and nothing leaves the phone.
            sender.sendPaymentRequest(PEER, AMOUNT, REASON)
            awaitIdle(sender)
            assertEquals("fixture sanity: a free request never goes on-chain", 0, seam.proposalUris.size)

            val reopened = paymentViewModel(prefs, seam)
            PaymentHarness.loadPendingMessagesFromPrefs(reopened)
            val row = PaymentHarness.pendingMessages(reopened).single()

            assertEquals(MessageStatus.FAILED, row.status)
            assertEquals(AMOUNT, row.paymentRequest?.amountZatoshi)
            assertFalse(
                "a free request that never left the phone reloads as 'may already have been sent — paying twice'",
                row.sendMayHaveGoneOut,
            )
            assertEquals("fixture sanity", emptyList<String>(), prefs.unexpectedCalls)
        }
}
