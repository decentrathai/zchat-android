package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.common.sendattempt.SendAttemptVerdict
import co.electriccoin.zcash.ui.screen.chat.model.ChatMessage
import co.electriccoin.zcash.ui.screen.chat.model.ChatSendCopy
import co.electriccoin.zcash.ui.screen.chat.model.MessageStatus
import co.electriccoin.zcash.ui.screen.chat.viewmodel.SendAttemptHarness.ChainTx
import co.electriccoin.zcash.ui.screen.chat.viewmodel.SendAttemptHarness.H
import co.electriccoin.zcash.ui.screen.chat.viewmodel.SendAttemptHarness.Wallet
import co.electriccoin.zcash.ui.screen.chat.viewmodel.SendAttemptHarness.txId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
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
 * ZC-310 (KI-127; T4c phone runs 1 and 2, 30 Sep): an own send whose transaction expired and was never mined is not
 * shown as delivered.
 *
 * The wallet lists such a transaction as SendTransaction.Failed (the SDK's "Expired"). At HEAD the chat's history pass
 * builds a chain row for it like for any own send, and ChatMessage.effectiveStatus calls every non-pending row with a
 * transaction id CONFIRMED: a double tick "On the blockchain" for a message that never left the phone. While the send
 * attempt is still being judged that false copy hides the true pending row; once the row is proven "Not sent" both
 * show; after the row is deleted only the false copy is left.
 *
 * Wanted: no chain row for an own send the wallet lists as expired and unmined. The pending row is then the only row
 * of the message (waiting, or "Not sent" with its Retry), deleting it leaves nothing, and a transaction that is mined
 * after all shows again as soon as the wallet lists it mined.
 *
 * No F4 test saw this: SendAttemptHarness.runPass lists every transaction as SendTransaction.Success and the F4
 * expiry fixtures give the transaction no memo, so no chain row is built. These cases list each transaction as the
 * repository does ([FulfilStoredIdsHarness.passAt]) and give it a readable memo. Cases 1 to 5 fail at HEAD; the three
 * controls pass before and after.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ExpiredNeverMinedSendIsNotShownAsDeliveredTest {
    private val sendScopes = SendHarnessScopes()

    private companion object {
        const val PEER = "usyntheticzc310expiredpeer01"
        const val TEXT = "synthetic zc310 never left the phone"
        const val EXPIRY = H + 40
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
    fun `1 - an expired never-mined send with no pending row shows no row`() =
        runBlocking {
            val fx = Fixture(row = Row.NONE)
            val rendered = fx.passAt(EXPIRY + 3)

            assertEquals(
                "an own send that expired unmined is shown in the chat: ${describe(fx.rowsOf(rendered))}",
                0,
                fx.rowsOf(rendered).size,
            )
        }

    @Test
    fun `2 - while the proof is not complete only the pending row shows and it waits`() =
        runBlocking {
            val fx = Fixture()
            val rows = fx.rowsOf(fx.passAt(EXPIRY + 3))

            fx.assertNothingDelivered(rows)
            assertEquals("the message must show exactly its pending row: ${describe(rows)}", listOf(fx.rowId), rows.map { it.id })
            assertEquals(MessageStatus.FAILED, rows.single().effectiveStatus)
            assertTrue(
                "the pending row does not carry a waiting verdict: ${rows.single().attemptVerdict}",
                rows.single().attemptVerdict is SendAttemptVerdict.Waiting,
            )
            assertTrue("the row left the disk", fx.prefs.pending.containsKey(fx.rowId))
        }

    @Test
    fun `3 - after the proof only the Not sent row shows`() =
        runBlocking {
            val fx = Fixture()
            fx.passAt(EXPIRY + 10)
            val rows = fx.rowsOf(fx.passAt(EXPIRY + 11))

            fx.assertNothingDelivered(rows)
            assertEquals("the message must show exactly its Not sent row: ${describe(rows)}", listOf(fx.rowId), rows.map { it.id })
            val row = rows.single()
            assertEquals(MessageStatus.FAILED, row.effectiveStatus)
            assertEquals(ChatSendCopy.FAILED_EXPIRED_NOT_SENT, row.failureReason)
            assertEquals("the expired transaction must be kept as superseded", listOf(txId(1)), row.supersededTxIds)
        }

    @Test
    fun `4 - after the Not sent row is deleted no row of the message remains`() =
        runBlocking {
            val fx = Fixture()
            fx.passAt(EXPIRY + 10)
            fx.passAt(EXPIRY + 11)
            val stored = fx.prefs.pending.getValue(fx.rowId)
            assertEquals(
                "fixture: the row must be the proven Not sent row before it is deleted",
                ChatSendCopy.FAILED_EXPIRED_NOT_SENT,
                stored.failureReason,
            )

            fx.deleteRow()
            val rows = fx.rowsOf(fx.passAt(EXPIRY + 12))

            assertEquals("a row of the deleted, never-sent message is still shown: ${describe(rows)}", 0, rows.size)
        }

    @Test
    fun `5 - a row with no id shows alone beside its readable expired copy`() =
        runBlocking {
            val fx = Fixture(row = Row.NO_IDS)
            val rows = fx.rowsOf(fx.passAt(EXPIRY + 3))

            fx.assertNothingDelivered(rows)
            assertEquals("the message must show exactly its pending row: ${describe(rows)}", listOf(fx.rowId), rows.map { it.id })
            assertEquals(MessageStatus.FAILED, rows.single().effectiveStatus)
        }

    @Test
    fun `6 - control - a mined send shows one delivered chain row and its pending row leaves the disk`() =
        runBlocking {
            val fx = Fixture(minedAt = H + 3)
            val rows = fx.rowsOf(fx.passAt(H + 10))

            assertEquals("a mined send must show exactly one row: ${describe(rows)}", 1, rows.size)
            val row = rows.single()
            assertEquals(txId(1), row.txId?.txIdString())
            assertEquals(MessageStatus.CONFIRMED, row.effectiveStatus)
            assertEquals(H + 3, row.minedHeight)
            assertFalse("the pending row of a mined send stayed on disk", fx.prefs.pending.containsKey(fx.rowId))
        }

    @Test
    fun `7 - control - an unexpired unmined send shows one sending chain row and hides its pending row`() =
        runBlocking {
            val fx = Fixture()
            val rows = fx.rowsOf(fx.passAt(H + 10))

            assertEquals("a send still in the mempool must show exactly one row: ${describe(rows)}", 1, rows.size)
            val row = rows.single()
            assertEquals(txId(1), row.txId?.txIdString())
            assertTrue(row.isPending)
            assertEquals(MessageStatus.SENDING, row.effectiveStatus)
            assertTrue("the hidden pending row must stay on disk until its transaction is mined", fx.prefs.pending.containsKey(fx.rowId))
        }

    @Test
    fun `8 - control - a send listed expired and then mined late shows as delivered again`() =
        runBlocking {
            val fx = Fixture()
            fx.passAt(EXPIRY + 3)

            fx.wallet.txs = listOf(fx.chainTx(minedAt = H + 39))
            val rows = fx.rowsOf(fx.passAt(EXPIRY + 5))

            assertEquals("a send mined late must show exactly one row: ${describe(rows)}", 1, rows.size)
            val row = rows.single()
            assertEquals(txId(1), row.txId?.txIdString())
            assertEquals(MessageStatus.CONFIRMED, row.effectiveStatus)
            assertEquals(H + 39, row.minedHeight)
            assertFalse("the pending row of a mined send stayed on disk", fx.prefs.pending.containsKey(fx.rowId))
        }

    private fun describe(rows: List<ChatMessage>): String =
        rows.joinToString(prefix = "[", postfix = "]") {
            (if (it.txId != null) "chain" else "pending") + ":" + it.effectiveStatus
        }

    private enum class Row { NONE, WITH_ID, NO_IDS }

    /** One message to [PEER]: its transaction number 1 with a readable memo, and (unless NONE) its stored row. */
    private inner class Fixture(
        row: Row = Row.WITH_ID,
        minedAt: Long? = null,
    ) {
        val prefs = PaymentPrefs(PEER)
        val seam = PaymentSeam(PEER)
        val rowId = SendAttemptHarness.newRowId()
        val wallet = Wallet(scanned = H, txs = listOf(chainTx(minedAt)))

        /** Lists each transaction as the repository does: mined Success, expired unmined Failed, else Pending. */
        private val listing = FulfilStoredIdsHarness(PEER)
        val vm: ChatViewModel =
            run {
                if (row != Row.NONE) {
                    val ids = if (row == Row.WITH_ID) listOf(txId(1)) else emptyList()
                    prefs.pending[rowId] = SendAttemptHarness.possiblySentRow(rowId, PEER, TEXT, ids, attemptHeight = H)
                }
                runBlocking { SendAttemptHarness.openChat(prefs, seam, wallet) }
            }

        fun chainTx(minedAt: Long?): ChainTx =
            ChainTx(1, PEER, memo = SendAttemptHarness.messageMemo(TEXT), minedHeight = minedAt, expiryHeight = EXPIRY)

        suspend fun passAt(tip: Long): List<ChatMessage> = listing.passAt(vm, wallet, tip)

        /** Every rendered row of the message: its pending row, its chain row, or any row with its text. */
        fun rowsOf(rendered: List<ChatMessage>): List<ChatMessage> =
            rendered.filter { it.id == rowId || it.txId?.txIdString() == txId(1) || it.text == TEXT }

        fun assertNothingDelivered(rows: List<ChatMessage>) {
            assertEquals(
                "a send that expired unmined is shown as delivered (double tick): ${describe(rows)}",
                0,
                rows.count { it.effectiveStatus == MessageStatus.CONFIRMED || it.effectiveStatus == MessageStatus.READ },
            )
            assertEquals("the expired transaction has a chain row: ${describe(rows)}", 0, rows.count { it.txId != null })
        }

        /** Delete as the chat screen ends it: the row leaves the store and the screen's pending list. */
        fun deleteRow() {
            prefs.pending.remove(rowId)
            val field = ChatViewModel::class.java.getDeclaredField("pendingMessages").apply { isAccessible = true }

            @Suppress("UNCHECKED_CAST")
            val flow = field.get(vm) as MutableStateFlow<List<ChatMessage>>
            flow.value = flow.value.filterNot { it.id == rowId }
        }
    }
}
