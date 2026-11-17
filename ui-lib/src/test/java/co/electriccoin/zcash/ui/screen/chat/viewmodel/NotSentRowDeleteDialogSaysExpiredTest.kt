package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.screen.chat.model.ChatSendCopy
import co.electriccoin.zcash.ui.screen.chat.model.MessageStatus
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
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * F4 (fix-all 2.13.0, train T4; SPECS §1.3 R10, §1.9 "C4 wording"): the delete dialog of a message proven not sent
 * tells the truth.
 *
 * Once a pass proves the row's transaction expired unmined, the row is FAILED with no submit record and no id, which is
 * exactly the shape C4 reads as "never handed to the SDK": its dialog says "It was not sent, so no Zcash transaction
 * was made for it". That is false here: a transaction was made, and it expired. Wanted: its own text,
 * chat_delete_message_body_expired ("Its transaction expired without being mined, so it is not on the Zcash
 * blockchain"), in English and Spanish. At HEAD the row is never converted, and the text does not exist.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NotSentRowDeleteDialogSaysExpiredTest {
    private val sendScopes = SendHarnessScopes()

    private companion object {
        const val PEER = "usyntheticf4deleteexpired001"
        const val EXPIRY = H + 40
        const val KEY = "chat_delete_message_body_expired"
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
    fun `a row proven expired is not told that no transaction was made`() =
        runBlocking {
            val prefs = PaymentPrefs(PEER)
            val seam = PaymentSeam(PEER)
            val rowId = SendAttemptHarness.newRowId()
            prefs.pending[rowId] = SendAttemptHarness.possiblySentRow(rowId, PEER, "synthetic delete me", listOf(txId(1)), H)
            val wallet = Wallet(scanned = H, txs = listOf(ChainTx(1, PEER, null, minedHeight = null, expiryHeight = EXPIRY)))
            val vm = SendAttemptHarness.openChat(prefs, seam, wallet)
            for (tip in listOf(EXPIRY + 10, EXPIRY + 11)) {
                wallet.scanned = tip
                wallet.network = tip
                SendAttemptHarness.runPass(vm, wallet)
            }
            val row = PaymentHarness.pendingMessages(vm).single { it.id == rowId }
            assertEquals("fixture: the row is FAILED", MessageStatus.FAILED, row.status)
            assertTrue("the proven expiry was not applied: the row still may have been sent", !row.sendMayHaveGoneOut)

            val body = ChatSendCopy.deleteConfirmBodyFor(row)
            assertNotEquals("a proven-expired row is told no transaction was made", R.string.chat_delete_message_body_not_sent, body)
            val expired = R.string::class.java.fields.firstOrNull { it.name == KEY }
            assertNotNull("no $KEY resource", expired)
            assertEquals("the dialog must say the transaction expired", expired!!.getInt(null), body)
        }

    @Test
    fun `the expired delete text exists in English and Spanish and names the expiry`() {
        val english = strings("values")[KEY]
        val spanish = strings("values-es")[KEY]
        assertNotNull("no English $KEY", english)
        assertNotNull("no Spanish $KEY", spanish)
        assertNotEquals("the Spanish text is still English", english, spanish)
        assertTrue("the English text must say the transaction expired: $english", "expired" in english!!.lowercase())
        assertTrue(
            "the English text must not say no transaction was made: $english",
            "no zcash transaction was made" !in english.lowercase()
        )
    }

    private fun strings(dir: String): Map<String, String> {
        var root: File? = File("").absoluteFile
        val rel = "ui-lib/src/main/res/ui/common/$dir/chat_send_strings.xml"
        while (root != null && !File(root, rel).isFile) root = root.parentFile
        val xml = File(root, rel).readText()
        return Regex("""<string name="([^"]+)">(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
            .findAll(xml)
            .associate { it.groupValues[1] to it.groupValues[2] }
    }
}
