package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.common.model.SubmitResult
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
 * P2a follow-up (review finding 5) — a rejection the SDK reports after it STORED the transaction.
 *
 * A SubmitResult.Failure that carries transaction IDs means the SDK stored the transaction and may broadcast
 * it again, so the row is rightly kept as "may already have been sent". At 1a650c932 the use case throws
 * SubmitRejectedException without those IDs, and the caller's catch never saves them. If the SDK's
 * re-broadcast is later mined, the row can never be matched to it and stays a FAILED duplicate for ever.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StoredRejectionKeepsTransactionIdsTest {
    private val sendScopes = SendHarnessScopes()

    private companion object {
        const val PEER = "usyntheticstoredreject000001"
        const val CHOSEN = 50_000L
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
    fun `a stored rejection keeps its transaction ID and is retired once that transaction is mined`() =
        runBlocking {
            val prefs = PaymentPrefs(PEER)
            val stored = SubmitResult.Failure(txIds = listOf(txId(1)), code = -26, description = "synthetic stored rejection")
            val seam = PaymentSeam(PEER, firstSubmitOutcome = stored)
            val text = "synthetic stored then rejected"
            val sender = paymentViewModel(prefs, seam)

            assertTrue(sender.sendMessage(PEER, text, CHOSEN))
            awaitIdle(sender)
            val row = prefs.pending.values.single()
            assertEquals("a stored rejection must stay held", MessageStatus.FAILED.name, row.status)
            assertEquals(
                "the stored transaction's ID was thrown away with the rejection",
                listOf(txId(1)),
                row.submittedTxIds,
            )

            // The SDK re-broadcast it and it was mined.
            val reopened = paymentViewModel(prefs, seam)
            PaymentHarness.loadPendingMessagesFromPrefs(reopened)
            val rows =
                PendingReconcileHarness.reconcile(
                    reopened,
                    prefs,
                    listOf(SentTx(1, PEER, ZMSGProtocol.createLegacyMessage(PaymentHarness.SELF, text), MINED_AT)),
                )

            assertEquals("fixture sanity: the mined message was parsed", 1, rows.count { it.id == txId(1) })
            assertFalse("the mined message keeps a permanent FAILED duplicate", prefs.pending.containsKey(row.id))
            assertEquals("a rejection must never cause a second payment", 1, seam.submits.get())
            assertEquals("fixture sanity", emptyList<String>(), prefs.unexpectedCalls)
        }
}
