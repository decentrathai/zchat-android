package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.screen.chat.viewmodel.HandshakeHarness.awaitIdle
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
import java.time.Instant

/**
 * F4 (fix-all 2.13.0, train T4; maybe-sent design, review point 5; SPECS §1.3 R6): a 2.12.4 row left pending by the
 * old build becomes sendable again once nothing of it can still reach the chain.
 *
 * A 2.12.4 build wrote the row before its broadcast and recorded nothing else (no tracking flag, no marker, no id). At
 * HEAD it reloads as FAILED "may already have been sent" and stays so for good unless a mined copy with the same text
 * retires it. The cutover is the first network height a 2.13.0 pass reads (an upper bound for anything the old build
 * made). With no candidate of ours (a live send to this contact at any height, or a mined one written after the row
 * and at most 42 blocks past the cutover, that is not readably a different message), the row is "not sent" once the
 * scanned tip is at least cutover + 52 on two rising passes; then Retry makes one new attempt. A later mined send to the
 * same contact whose text is readably a different message does not block it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LegacyRow2124NotSentAfterCutoverTest {
    private val sendScopes = SendHarnessScopes()

    private companion object {
        const val PEER = "usyntheticf4legacypeer000001"
        const val CUTOVER = H
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
    fun `a legacy row with no candidate is not sent after cutover plus 52 on two passes`() =
        runBlocking {
            val prefs = PaymentPrefs(PEER)
            val seam = PaymentSeam(PEER)
            val rowId = SendAttemptHarness.newRowId()
            prefs.pending[rowId] =
                SendAttemptHarness.legacyRow(rowId, PEER, "synthetic legacy lost hello", Instant.now().minusSeconds(7_200))
            // Another message to the same contact, sent and mined after the upgrade, readably not this one.
            val other =
                ChainTx(
                    3,
                    PEER,
                    memo = SendAttemptHarness.messageMemo("synthetic other message"),
                    minedHeight = CUTOVER + 20,
                    expiryHeight = CUTOVER + 60,
                )
            val wallet = Wallet(scanned = CUTOVER, txs = emptyList())
            val vm = SendAttemptHarness.openChat(prefs, seam, wallet)

            pass(vm, wallet, CUTOVER)
            wallet.txs = listOf(other)
            for (tip in listOf(CUTOVER + 30, CUTOVER + 51, CUTOVER + 51)) {
                pass(vm, wallet, tip)
                assertFalse("the legacy row was sent again before cutover + 52 (tip $tip)", vm.retryMessage(PEER, rowId))
                awaitIdle(vm)
            }
            assertEquals(0, seam.proposalUris.size)

            pass(vm, wallet, CUTOVER + 52)
            pass(vm, wallet, CUTOVER + 53)
            assertTrue("the legacy row stayed blocked with no candidate after cutover + 52", vm.retryMessage(PEER, rowId))
            awaitIdle(vm)
            assertEquals("exactly one new attempt", 1, seam.proposalUris.size)
            assertEquals("fixture sanity", emptyList<String>(), prefs.unexpectedCalls)
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
}
