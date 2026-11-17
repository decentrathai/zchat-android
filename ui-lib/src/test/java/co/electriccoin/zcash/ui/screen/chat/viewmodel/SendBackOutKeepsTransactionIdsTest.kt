package co.electriccoin.zcash.ui.screen.chat.viewmodel

import androidx.lifecycle.viewModelScope
import co.electriccoin.zcash.ui.common.model.SubmitResult
import co.electriccoin.zcash.ui.screen.chat.model.MessageStatus
import co.electriccoin.zcash.ui.screen.chat.model.ZMSGProtocol
import co.electriccoin.zcash.ui.screen.chat.viewmodel.HandshakeHarness.awaitIdle
import co.electriccoin.zcash.ui.screen.chat.viewmodel.PaymentHarness.eventually
import co.electriccoin.zcash.ui.screen.chat.viewmodel.PaymentHarness.paymentViewModel
import co.electriccoin.zcash.ui.screen.chat.viewmodel.PendingReconcileHarness.SentTx
import co.electriccoin.zcash.ui.screen.chat.viewmodel.PendingReconcileHarness.txId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * P2a follow-up (review finding 2) — pressing Back while a paid message is being built or broadcast.
 *
 * Every chat screen has its own ChatViewModel, cleared on Back. The repository broadcasts in its own detached
 * scope, so Back cancels only the await: the message still goes out. At 1a650c932 the cancellation reaches
 * doSendMessage's / sendReply's catch after the submit marker was written, the row is kept as FAILED "may
 * already have been sent", and the transaction IDs the SDK returns a moment later are never saved. The row
 * can then never be matched to its mined transaction, so the reopened chat shows the delivered message plus
 * a permanent FAILED duplicate. Before P2a that row was deleted, so the user saw one copy.
 *
 * Drives the real send paths through the real use case and repository (its detached scope) with the
 * settlement seam holding the broadcast until after the screen's scope is cancelled.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SendBackOutKeepsTransactionIdsTest {
    private val sendScopes = SendHarnessScopes()

    private companion object {
        const val PEER = "usyntheticbackoutkeepsids001"
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
    fun `a message sent while the user backs out is matched to its mined transaction on reopen`() =
        runBlocking {
            val prefs = PaymentPrefs(PEER)
            val seam = PaymentSeam(PEER, holdFirstSubmit = true, firstSubmitOutcome = SubmitResult.Success(listOf(txId(1))))
            val text = "synthetic back-out hello"
            val screen = paymentViewModel(prefs, seam)

            assertTrue(screen.sendMessage(PEER, text, CHOSEN))
            withTimeout(PaymentHarness.TIMEOUT_MS) { seam.firstSubmitEntered.await() }
            val pendingId =
                prefs.pending.values
                    .single()
                    .id
            backOutWhileBroadcasting(screen, seam)
            // Give a (correct) run-to-completion time to save the returned IDs; no assertion on the wait itself.
            eventually { prefs.pending[pendingId]?.submittedTxIds?.isNotEmpty() == true }

            val reopened = paymentViewModel(prefs, seam)
            PaymentHarness.loadPendingMessagesFromPrefs(reopened)
            val rows =
                PendingReconcileHarness.reconcile(
                    reopened,
                    prefs,
                    listOf(SentTx(1, PEER, ZMSGProtocol.createLegacyMessage(PaymentHarness.SELF, text), MINED_AT)),
                )

            assertEquals("fixture sanity: the mined message was parsed", 1, rows.count { it.id == txId(1) })
            assertFalse(
                "backing out lost the transaction ID: the mined message keeps a permanent FAILED duplicate",
                prefs.pending.containsKey(pendingId),
            )
            assertTrue(rows.none { it.status == MessageStatus.FAILED })
            assertEquals("backing out must never cause a second payment", 1, seam.submits.get())
            assertEquals("fixture sanity", emptyList<String>(), prefs.unexpectedCalls)
        }

    @Test
    fun `a reply sent while the user backs out keeps the transaction ID it was given`() =
        runBlocking {
            val prefs = PaymentPrefs(PEER)
            val seam = PaymentSeam(PEER, holdFirstSubmit = true, firstSubmitOutcome = SubmitResult.Success(listOf(txId(2))))
            val screen = paymentViewModel(prefs, seam)

            assertTrue(screen.sendReply(PEER, "synthetic back-out reply", "synthetic-quoted-id", "synthetic quote", CHOSEN))
            withTimeout(PaymentHarness.TIMEOUT_MS) { seam.firstSubmitEntered.await() }
            val pendingId =
                prefs.pending.values
                    .single()
                    .id
            backOutWhileBroadcasting(screen, seam)
            eventually { prefs.pending[pendingId]?.submittedTxIds?.isNotEmpty() == true }

            val row = prefs.pending[pendingId]
            assertTrue("the reply row itself must be kept until its transaction is mined", row != null)
            assertEquals(
                "backing out lost the reply's transaction ID, so it can never be matched to its mined copy",
                listOf(txId(2)),
                row!!.submittedTxIds,
            )
            assertEquals("synthetic-quoted-id", row.replyToId)
            assertEquals(1, seam.submits.get())
            assertEquals("fixture sanity", emptyList<String>(), prefs.unexpectedCalls)
        }

    /** Back: the screen's scope is cancelled mid-broadcast; the detached repository finishes the send. */
    private suspend fun backOutWhileBroadcasting(
        screen: ChatViewModel,
        seam: PaymentSeam,
    ) {
        screen.viewModelScope.coroutineContext.job
            .cancel()
        seam.release.complete(Unit)
        withTimeout(PaymentHarness.TIMEOUT_MS) { seam.firstSubmitDone.await() }
        awaitIdle(screen)
    }
}
