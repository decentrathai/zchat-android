package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.screen.chat.model.ChatMessage
import co.electriccoin.zcash.ui.screen.chat.model.ConversationMode
import co.electriccoin.zcash.ui.screen.chat.model.MessageStatus
import co.electriccoin.zcash.ui.screen.chat.model.PaymentRequestInfo
import co.electriccoin.zcash.ui.screen.chat.viewmodel.PaymentHarness.paymentViewModel
import co.electriccoin.zcash.ui.screen.chat.viewmodel.PendingReconcileHarness.SentTx
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant

/**
 * C3 (B2 review items 7 and 8) — what the ZREQ_FULFILL history scan-back does with a payment it sees.
 *
 * Item 7 (existed before): a free (NOSTR) payment request bubble is a pending row whose `isPaid` is fixed when
 * the row is built. The conversations combine passes pending rows through without reading the paid set, and the
 * scan-back only adds the id to `paidRequestIds`. After the scan-back sees our outgoing ZREQ_FULFILL for the
 * request (a Keystone fulfil, or the recovery after a kill), the bubble keeps showing Pay.
 *
 * Item 8: the scan-back ignores whether `markRequestPaid` reached the disk, and the next pass skips the id because
 * it is already in the in-memory paid set, so a failed write is never retried. (Withholding the in-memory add
 * when the write fails is NOT the fix: an on-chain request bubble reads that set, and Pay would come back over a
 * payment seen on-chain.)
 *
 * Runs the real convertToConversations ([PendingReconcileHarness]). All ids and addresses are synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PaymentRequestBubbleFollowsPaidSetTest {
    private val sendScopes = SendHarnessScopes()

    private companion object {
        const val PEER = "usyntheticpaidbubble00000001"
        const val AMOUNT = 25_000_000L
        const val REASON = "synthetic dinner share"
        const val MINED_AT = 3_000_100L
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
    fun `a free request bubble shows paid once the scan-back sees our payment for it`() =
        runBlocking {
            val requestId = "synthetic-c3-nostr-request-7"
            val prefs = PaymentPrefs(PEER, mode = ConversationMode.TUNNEL)
            val vm = paymentViewModel(prefs, PaymentSeam(PEER))
            PaymentHarness.setField(vm, "pendingMessages", MutableStateFlow(listOf(nostrRequest(requestId))))

            val rows =
                PendingReconcileHarness.reconcile(
                    vm,
                    prefs,
                    listOf(SentTx(1, PEER, "ZREQ_FULFILL|$requestId", MINED_AT)),
                )

            assertTrue("fixture sanity: the scan-back marked the request paid", requestId in prefs.paid)
            val bubble = rows.single { it.id == requestId }
            assertEquals(
                "the request is paid (our ZREQ_FULFILL was seen), but its bubble still shows Pay",
                true,
                bubble.paymentRequest?.isPaid,
            )
        }

    @Test
    fun `a paid marker the scan-back could not save is written again on a later pass`() =
        runBlocking {
            val requestId = "synthetic-c3-onchain-request-8"
            val prefs = PaymentPrefs(PEER, mode = ConversationMode.VAULT)
            val vm = paymentViewModel(prefs, PaymentSeam(PEER))
            val txs = listOf(SentTx(1, PEER, "ZREQ_FULFILL|$requestId", MINED_AT))
            // The first write fails (nothing reaches the disk); later writes work.
            prefs.markRequestPaidFailures.add(Unit)

            PendingReconcileHarness.reconcileConversations(vm, prefs, txs, paidReqIds = paidRequestIds(vm))
            assertTrue(
                "the payment was seen on-chain; the request must be paid in memory at once, even if the disk write failed",
                requestId in paidRequestIds(vm),
            )
            assertTrue("fixture sanity: the first write failed", requestId !in prefs.paid)

            // A later pass, with the paid set the combine now hands it.
            PendingReconcileHarness.reconcileConversations(vm, prefs, txs, paidReqIds = paidRequestIds(vm))

            assertTrue(
                "the scan-back's paid marker never reached the disk and no later pass wrote it again",
                requestId in prefs.paid,
            )
        }

    // --------------------------------------------------------------- harness

    /** An inbound free (NOSTR) payment request row, as loadPendingMessagesFromPrefs builds it: not yet paid. */
    private fun nostrRequest(id: String): ChatMessage =
        ChatMessage(
            id = id,
            txId = null,
            text = REASON,
            timestamp = Instant.now(),
            isOutgoing = false,
            peerAddress = PEER,
            isPending = false,
            status = MessageStatus.SENT,
            paymentRequest = PaymentRequestInfo(amountZatoshi = AMOUNT, reason = REASON, isPaid = false),
            authenticated = true,
        )

    @Suppress("UNCHECKED_CAST")
    private fun paidRequestIds(vm: ChatViewModel): Set<String> =
        (
            ChatViewModel::class.java
                .getDeclaredField("paidRequestIds")
                .apply { isAccessible = true }
                .get(vm) as MutableStateFlow<Set<String>>
        ).value
}
