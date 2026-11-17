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
 * F4 (fix-all 2.13.0, train T4; maybe-sent review point 5; SPECS §1.3 R6c), a guard: a 2.12.4 row is never sent again
 * while a mined send of ours to the same contact could be it. The candidate is mined 10 blocks after the cutover, after
 * the row was written, and its memo cannot be read, so nothing says it is another message. However far the tip runs,
 * Retry stays refused and the row stays. Green at HEAD and after F4.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LegacyRow2124MinedCandidateBlocksTest {
    private val sendScopes = SendHarnessScopes()

    private companion object {
        const val PEER = "usyntheticf4legacymined00001"
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
    fun `a mined unreadable send to the contact after the row keeps the legacy row blocked`() =
        runBlocking {
            val prefs = PaymentPrefs(PEER)
            val seam = PaymentSeam(PEER)
            val rowId = SendAttemptHarness.newRowId()
            val writtenAt = Instant.now().minusSeconds(3_600)
            prefs.pending[rowId] = SendAttemptHarness.legacyRow(rowId, PEER, "synthetic legacy maybe hello", writtenAt)
            val mined =
                ChainTx(
                    4,
                    PEER,
                    memo = null,
                    minedHeight = CUTOVER + 10,
                    expiryHeight = CUTOVER + 40,
                    blockTime = writtenAt.plusSeconds(120)
                )
            val wallet = Wallet(scanned = CUTOVER, txs = listOf(mined))
            val vm = SendAttemptHarness.openChat(prefs, seam, wallet)

            for (tip in listOf(CUTOVER, CUTOVER + 52, CUTOVER + 53, CUTOVER + 500, CUTOVER + 501)) {
                wallet.scanned = tip
                wallet.network = if (tip == CUTOVER) CUTOVER else tip
                SendAttemptHarness.runPass(vm, wallet)
                assertFalse("a legacy row was sent again although a mined send may be it (tip $tip)", vm.retryMessage(PEER, rowId))
                awaitIdle(vm)
            }
            assertEquals("a legacy row was paid a second time", 0, seam.proposalUris.size)
            assertTrue("the legacy row was dropped", prefs.pending.containsKey(rowId))
        }
}
