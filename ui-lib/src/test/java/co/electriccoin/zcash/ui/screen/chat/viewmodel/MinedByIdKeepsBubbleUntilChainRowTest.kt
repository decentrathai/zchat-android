package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.screen.chat.model.MessageStatus
import co.electriccoin.zcash.ui.screen.chat.viewmodel.HandshakeHarness.awaitIdle
import co.electriccoin.zcash.ui.screen.chat.viewmodel.SendAttemptHarness.ChainTx
import co.electriccoin.zcash.ui.screen.chat.viewmodel.SendAttemptHarness.H
import co.electriccoin.zcash.ui.screen.chat.viewmodel.SendAttemptHarness.Wallet
import co.electriccoin.zcash.ui.screen.chat.viewmodel.SendAttemptHarness.txId
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
 * F4 (fix-all 2.13.0, train T4; maybe-sent review point 6; SPECS §1.3 R2): a send whose own transaction is mined is
 * shown as sent, and its row is kept until the chat shows that transaction.
 *
 * At HEAD a tracked row leaves only when a chain row with the SAME text and one of its transaction ids is mined. When
 * the mined transaction's memo has not been read yet (the SDK returns none), no chain row exists, so the bubble stays
 * FAILED with "may already have been sent" although the wallet shows its own transaction mined.
 *
 * Wanted: the wallet's own transaction, mined, makes the bubble SENT (no notice, no Retry) while the row stays on disk
 * (removing it by id alone would lose the bubble, review point 6). Once the chain row for that transaction is shown,
 * the row goes and the chat still shows the message once.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MinedByIdKeepsBubbleUntilChainRowTest {
    private val sendScopes = SendHarnessScopes()

    private companion object {
        const val PEER = "usyntheticf4minedpeer0000001"
        const val TEXT = "synthetic mined hello"
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
    fun `a mined own transaction shows the message as sent and keeps its row until the chain row is shown`() =
        runBlocking {
            val prefs = PaymentPrefs(PEER)
            val seam = PaymentSeam(PEER)
            val rowId = SendAttemptHarness.newRowId()
            prefs.pending[rowId] = SendAttemptHarness.possiblySentRow(rowId, PEER, TEXT, listOf(txId(1)), attemptHeight = H)
            val unread = ChainTx(1, PEER, memo = null, minedHeight = H + 3, expiryHeight = H + 40)
            val wallet = Wallet(scanned = H + 20, txs = listOf(unread))
            val vm = SendAttemptHarness.openChat(prefs, seam, wallet)

            val first = SendAttemptHarness.bubblesOf(SendAttemptHarness.runPass(vm, wallet), rowId, listOf(txId(1)))

            assertEquals("the message must show exactly once", 1, first.size)
            val bubble = first.single()
            assertNotEquals("a mined send still reads as failed", MessageStatus.FAILED, bubble.effectiveStatus)
            assertFalse("a mined send still says it may already have been sent", bubble.sendMayHaveGoneOut)
            assertTrue("the row left the disk before the chain row is shown (review point 6)", prefs.pending.containsKey(rowId))

            wallet.txs = listOf(ChainTx(1, PEER, memo = SendAttemptHarness.messageMemo(TEXT), minedHeight = H + 3, expiryHeight = H + 40))
            wallet.scanned = H + 21
            wallet.network = H + 21
            val second = SendAttemptHarness.bubblesOf(SendAttemptHarness.runPass(vm, wallet), rowId, listOf(txId(1)))

            assertFalse("the row stays on disk after its chain row is shown", prefs.pending.containsKey(rowId))
            assertEquals("the message must show exactly once", 1, second.size)
            assertEquals("the chain row is the one left", txId(1), second.single().txId?.txIdString())

            vm.retryMessage(PEER, rowId)
            awaitIdle(vm)
            assertEquals("a mined message was paid again", 0, seam.proposalUris.size)
            assertEquals("fixture sanity", emptyList<String>(), prefs.unexpectedCalls)
        }
}
