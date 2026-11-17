package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.screen.chat.viewmodel.HandshakeHarness.awaitIdle
import co.electriccoin.zcash.ui.screen.chat.viewmodel.SendAttemptHarness.AMOUNT
import co.electriccoin.zcash.ui.screen.chat.viewmodel.SendAttemptHarness.ChainTx
import co.electriccoin.zcash.ui.screen.chat.viewmodel.SendAttemptHarness.H
import co.electriccoin.zcash.ui.screen.chat.viewmodel.SendAttemptHarness.Wallet
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
import java.io.File

/**
 * F4 (fix-all 2.13.0, train T4; maybe-sent review points 1 and 10; SPECS §1.3 R4): a send killed after the SDK stored
 * its transaction and before the ids reached the row (the fault seam KILL_AFTER_CREATE_BEFORE_ON_STORED) waits until
 * that transaction's expiry, then gets one new attempt.
 *
 * The row has a marker and no ids. The wallet holds an unmined send of ours to this contact, of the row's amount, with
 * expiry E: it may be this message (the SDK stored it; it was never submitted, or it was). While it can be mined the
 * row must wait; once it is dead (E + 10 over two rising passes) and the row's release anchor + 52 blocks has passed,
 * nothing of this attempt can reach the chain.
 *
 * At HEAD the row is refused for good. Wanted: refused below E + 10, one Retry after, and the waiting text never
 * promises the SDK "keeps trying" (the SDK does not rebroadcast a transaction that never reached its first submit).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CreatedNeverSubmittedWaitsUntilExpiryTest {
    private val sendScopes = SendHarnessScopes()

    private companion object {
        const val PEER = "usyntheticf4createdpeer00001"
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
    fun `a stored transaction with no id on the row keeps Retry off until it expired, then allows one`() =
        runBlocking {
            val prefs = PaymentPrefs(PEER)
            val seam = PaymentSeam(PEER)
            val rowId = SendAttemptHarness.newRowId()
            prefs.pending[rowId] = SendAttemptHarness.possiblySentRow(rowId, PEER, "synthetic created hello", emptyList(), H)
            val stored = ChainTx(7, PEER, memo = null, minedHeight = null, expiryHeight = EXPIRY, amount = AMOUNT)
            // The first pass runs at the height the send was made at: the release anchor.
            val wallet = Wallet(scanned = H, txs = listOf(stored))
            val vm = SendAttemptHarness.openChat(prefs, seam, wallet)

            for (tip in listOf(H, EXPIRY - 1, EXPIRY + 5, EXPIRY + 9)) {
                pass(vm, wallet, tip)
                assertFalse("Retry was accepted while the stored transaction can still be mined (tip $tip)", vm.retryMessage(PEER, rowId))
                awaitIdle(vm)
            }
            assertEquals(0, seam.proposalUris.size)

            pass(vm, wallet, EXPIRY + 12)
            pass(vm, wallet, EXPIRY + 13)
            assertTrue("Retry stayed refused after the stored transaction expired unmined", vm.retryMessage(PEER, rowId))
            awaitIdle(vm)
            assertEquals("exactly one new attempt", 1, seam.proposalUris.size)
            assertEquals("fixture sanity", emptyList<String>(), prefs.unexpectedCalls)
        }

    @Test
    fun `the waiting text never promises that the send keeps trying`() {
        val english = chatSendStrings()
        val waiting = english["chat_send_row_waiting_for_network"]
        assertTrue("no waiting text for a send that can still be mined", waiting != null)
        val lower = waiting!!.lowercase()
        for (promise in listOf("keeps trying", "keep trying", "will retry", "retries")) {
            assertFalse("the waiting text promises \"$promise\": $waiting", promise in lower)
        }
    }

    private suspend fun pass(
        vm: ChatViewModel,
        wallet: Wallet,
        tip: Long,
    ) {
        wallet.scanned = tip
        wallet.network = tip
        SendAttemptHarness.runPass(vm, wallet)
    }

    private fun chatSendStrings(): Map<String, String> {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "ui-lib/src/main/res/ui/common/values/chat_send_strings.xml").isFile) dir = dir.parentFile
        val xml = File(dir, "ui-lib/src/main/res/ui/common/values/chat_send_strings.xml").readText()
        return Regex("""<string name="([^"]+)">(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
            .findAll(xml)
            .associate { it.groupValues[1] to it.groupValues[2] }
    }
}
