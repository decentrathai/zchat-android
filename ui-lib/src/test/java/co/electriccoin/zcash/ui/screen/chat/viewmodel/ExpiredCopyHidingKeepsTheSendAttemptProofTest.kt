package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.common.util.FaultSeam
import co.electriccoin.zcash.ui.common.util.FaultSeams
import co.electriccoin.zcash.ui.screen.chat.model.ChatMessage
import co.electriccoin.zcash.ui.screen.chat.model.MessageStatus
import co.electriccoin.zcash.ui.screen.chat.model.SendMessageState
import co.electriccoin.zcash.ui.screen.chat.viewmodel.HandshakeHarness.awaitIdle
import co.electriccoin.zcash.ui.screen.chat.viewmodel.SendAttemptHarness.AMOUNT
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
import java.time.Instant

/**
 * ZC-310 (KI-127), the money-path guard: hiding the chain copy of an expired, never-mined own send changes nothing of
 * F4's proof that Retry pays once.
 *
 * F4's expiry scenarios (ExpiredAttemptOffersOneRetryTest, WaitingRowRefusesRetryTest,
 * LegacyRow2124NotSentAfterCutoverTest) run here on the listing the repository really gives the chat, which no F4 test
 * used: an expired unmined transaction is SendTransaction.Failed ([FulfilStoredIdsHarness.passAt]) and carries a
 * readable memo, so the pass builds a chain row for it and the resolver reads its text.
 *
 * In every test the Retry and proposal assertions come first and hold at HEAD and after the fix (the guard); the last
 * assertion is that no state shows the expired transaction as a delivered copy, which fails at HEAD (ZC-310). The
 * mined control has no such last assertion: its copy is delivered.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ExpiredCopyHidingKeepsTheSendAttemptProofTest {
    private val sendScopes = SendHarnessScopes()

    private companion object {
        const val PEER = "usyntheticzc310proofpeer0001"
        const val TEXT = "synthetic zc310 proof hello"
        const val EXPIRY = H + 40
        const val MAY_HAVE_BEEN_SENT = "may already have been sent"
    }

    @Before
    fun setUp() {
        sendScopes.open()
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        FaultSeams.disarmAll()
        sendScopes.close()
        Dispatchers.resetMain()
    }

    @Test
    fun `two rising passes past the margin allow exactly one Retry at the chosen amount`() =
        runBlocking {
            val fx = Fixture()
            fx.passAt(EXPIRY + 10)
            val proven = fx.passAt(EXPIRY + 11)

            assertTrue("Retry was refused although the only transaction expired unmined", fx.vm.retryMessage(PEER, fx.rowId))
            awaitIdle(fx.vm)
            assertEquals("the new attempt was not proposed once", 1, fx.seam.proposalUris.size)
            assertEquals(
                "the new attempt is not at the chosen amount",
                PaymentHarness.zec(AMOUNT),
                PaymentHarness.firstOutputAmount(fx.seam.proposalUris[0]),
            )

            assertFalse("a second Retry of the same message was accepted", fx.vm.retryMessage(PEER, fx.rowId))
            awaitIdle(fx.vm)
            assertEquals("a second Retry proposed another payment", 1, fx.seam.proposalUris.size)

            val row = fx.prefs.pending.getValue(fx.rowId)
            assertNotEquals("the row still carries the expired attempt's marker", fx.marker, row.submitStartedAtMillis)
            assertFalse("the expired transaction is still the row's live attempt", txId(1) in row.submittedTxIds)
            assertEquals(
                "the expired transaction must be kept as superseded (a late landing is then noticed)",
                listOf(txId(1)),
                row.supersededTxIds,
            )
            assertEquals("fixture sanity", emptyList<String>(), fx.prefs.unexpectedCalls)

            fx.assertRowShownAndNoDeliveredCopy(proven)
        }

    @Test
    fun `control - one pass past the margin proves nothing`() =
        runBlocking {
            val fx = Fixture()
            val rendered = fx.passAt(EXPIRY + 10)
            fx.assertRefused()
            fx.assertRowShownAndNoDeliveredCopy(rendered)
        }

    @Test
    fun `control - two passes at the same tip prove nothing`() =
        runBlocking {
            val fx = Fixture()
            fx.passAt(EXPIRY + 10)
            val rendered = fx.passAt(EXPIRY + 10)
            fx.assertRefused()
            fx.assertRowShownAndNoDeliveredCopy(rendered)
        }

    @Test
    fun `control - a rewind between the passes proves nothing`() =
        runBlocking {
            val fx = Fixture()
            fx.passAt(EXPIRY + 10)
            val rendered = fx.passAt(EXPIRY + 5)
            fx.assertRefused()
            fx.assertRowShownAndNoDeliveredCopy(rendered)
        }

    @Test
    fun `control - one block short of the reorg margin proves nothing`() =
        runBlocking {
            val fx = Fixture()
            fx.passAt(EXPIRY + 9)
            val rendered = fx.passAt(EXPIRY + 9)
            fx.assertRefused()
            fx.assertRowShownAndNoDeliveredCopy(rendered)
        }

    @Test
    fun `control - a mined transaction is never sent again`() =
        runBlocking {
            val fx = Fixture(minedAt = H + 3)
            fx.passAt(EXPIRY + 10)
            val rendered = fx.passAt(EXPIRY + 11)

            assertFalse("Retry was accepted for a mined send", fx.vm.retryMessage(PEER, fx.rowId))
            awaitIdle(fx.vm)
            assertEquals("a payment was proposed for a mined send", 0, fx.seam.proposalUris.size)
            val copies = rendered.filter { it.txId?.txIdString() == txId(1) }
            assertEquals("a mined send must keep its one delivered chain row", 1, copies.size)
            assertEquals(MessageStatus.CONFIRMED, copies.single().effectiveStatus)
        }

    @Test
    fun `control - with the scanned tip unknown nothing is proven and the row stays visible`() =
        runBlocking {
            val fx = Fixture()
            // What BLOCK_GET_STATUS does on a phone: the wallet still lists the transaction as expired, but the
            // send-attempt reconcile cannot read a scanned tip, so it decides nothing.
            FaultSeams.arm(FaultSeam.BLOCK_GET_STATUS, 1)
            fx.passAt(EXPIRY + 10)
            val rendered = fx.passAt(EXPIRY + 11)
            fx.assertRefused()
            fx.assertRowShownAndNoDeliveredCopy(rendered)
        }

    @Test
    fun `a 2124 row with its expired readable copy is shown and gets one Retry only after the cutover window`() =
        runBlocking {
            val cutover = H
            val prefs = PaymentPrefs(PEER)
            val seam = PaymentSeam(PEER)
            val rowId = SendAttemptHarness.newRowId()
            prefs.pending[rowId] = SendAttemptHarness.legacyRow(rowId, PEER, TEXT, Instant.now().minusSeconds(7_200))
            // The transaction the 2.12.4 build made 30 blocks before the upgrade: unmined, expiring at cutover + 10.
            val copy =
                ChainTx(1, PEER, memo = SendAttemptHarness.messageMemo(TEXT), minedHeight = null, expiryHeight = cutover + 10)
            val wallet = Wallet(scanned = cutover, txs = listOf(copy))
            val vm = SendAttemptHarness.openChat(prefs, seam, wallet)
            val listing = FulfilStoredIdsHarness(PEER)

            listing.passAt(vm, wallet, cutover)
            val whileExpired = mutableListOf<List<ChatMessage>>()
            for (tip in listOf(cutover + 13, cutover + 30, cutover + 51, cutover + 51)) {
                whileExpired += listing.passAt(vm, wallet, tip)
                assertFalse("the legacy row was sent again before cutover + 52 (tip $tip)", vm.retryMessage(PEER, rowId))
                awaitIdle(vm)
            }
            assertEquals("a payment was proposed before the cutover window passed", 0, seam.proposalUris.size)

            listing.passAt(vm, wallet, cutover + 52)
            whileExpired += listing.passAt(vm, wallet, cutover + 53)
            assertTrue("the legacy row stayed blocked with no live candidate after cutover + 52", vm.retryMessage(PEER, rowId))
            awaitIdle(vm)
            assertEquals("exactly one new attempt", 1, seam.proposalUris.size)
            assertFalse("a second Retry of the legacy row was accepted", vm.retryMessage(PEER, rowId))
            awaitIdle(vm)
            assertEquals("a second Retry proposed another payment", 1, seam.proposalUris.size)
            assertEquals("fixture sanity", emptyList<String>(), prefs.unexpectedCalls)

            whileExpired.forEach { assertRowShownAndNoDeliveredCopy(it, rowId) }
        }

    /** The last assertion of every expiry scenario (RED at HEAD): the row is on screen, no copy says delivered. */
    private fun assertRowShownAndNoDeliveredCopy(
        rendered: List<ChatMessage>,
        rowId: String,
    ) {
        val copies = rendered.filter { it.txId?.txIdString() == txId(1) }
        assertEquals(
            "the expired, never-mined transaction is shown as a chat row (${copies.map { it.effectiveStatus }})",
            0,
            copies.size,
        )
        assertEquals(
            "a row with the message's text says delivered",
            0,
            rendered.count { it.text == TEXT && it.effectiveStatus in setOf(MessageStatus.CONFIRMED, MessageStatus.READ) },
        )
        assertEquals("the message's own row is not on screen exactly once", 1, rendered.count { it.id == rowId })
    }

    /** A FAILED "may already have been sent" row with one transaction id whose copy carries a readable memo. */
    private inner class Fixture(
        minedAt: Long? = null,
    ) {
        val prefs = PaymentPrefs(PEER)
        val seam = PaymentSeam(PEER)
        val rowId = SendAttemptHarness.newRowId()
        val marker = System.currentTimeMillis() - 3_600_000L
        val wallet =
            Wallet(
                scanned = H,
                txs =
                    listOf(
                        ChainTx(1, PEER, memo = SendAttemptHarness.messageMemo(TEXT), minedHeight = minedAt, expiryHeight = EXPIRY),
                    ),
            )
        private val listing = FulfilStoredIdsHarness(PEER)
        val vm: ChatViewModel =
            run {
                prefs.pending[rowId] =
                    SendAttemptHarness.possiblySentRow(rowId, PEER, TEXT, listOf(txId(1)), attemptHeight = H, marker = marker)
                runBlocking { SendAttemptHarness.openChat(prefs, seam, wallet) }
            }

        suspend fun passAt(tip: Long): List<ChatMessage> = listing.passAt(vm, wallet, tip)

        suspend fun assertRefused() {
            assertFalse("Retry was accepted without proof that nothing was sent", vm.retryMessage(PEER, rowId))
            awaitIdle(vm)
            assertEquals("a payment was proposed without proof", 0, seam.proposalUris.size)
            val state = PaymentHarness.sendMessageState(vm)
            assertTrue(
                "the refusal did not say the message may already have been sent: $state",
                state is SendMessageState.Error && state.message.contains(MAY_HAVE_BEEN_SENT, ignoreCase = true),
            )
            assertTrue("the row left the disk", prefs.pending.containsKey(rowId))
        }

        fun assertRowShownAndNoDeliveredCopy(rendered: List<ChatMessage>) = assertRowShownAndNoDeliveredCopy(rendered, rowId)
    }
}
