package co.electriccoin.zcash.ui.screen.chat.viewmodel

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

/**
 * F4 (fix-all 2.13.0, train T4; maybe-sent design A, review points 1, 4 and 8; SPECS §1.3 R1): a chat message whose
 * transaction provably expired unmined gets exactly one new attempt.
 *
 * At HEAD a FAILED row whose submit started stays "may already have been sent" for good: nothing ever settles it, so
 * Retry is refused even after its only transaction is dead on the chain (unmined, past its expiry height + 10 blocks,
 * seen on two passes at a rising scanned height). The user can never send that message again; they can only delete it.
 *
 * Wanted: after two complete passes at a strictly rising scanned tip that both read the transaction as expired
 * unmined (expiry + 10 <= tip), the row becomes the existing "proven nothing sent" FAILED row, so Retry makes exactly
 * one new attempt at the same amount; a second Retry makes none. Nothing else unblocks it (the controls): one pass
 * only, two passes at the same tip (the list and the detail screen run one each), a rewind, a tip one block short of
 * the margin, and a mined transaction.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ExpiredAttemptOffersOneRetryTest {
    private val sendScopes = SendHarnessScopes()

    private companion object {
        const val PEER = "usyntheticf4expiredpeer00001"
        const val TEXT = "synthetic expired hello"
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
        sendScopes.close()
        Dispatchers.resetMain()
    }

    @Test
    fun `an attempt proven expired on two rising passes gets exactly one new attempt`() =
        runBlocking {
            val fx = Fixture()
            fx.passAt(EXPIRY + 10)
            fx.passAt(EXPIRY + 11)

            assertTrue("Retry was refused although the only transaction expired unmined", fx.vm.retryMessage(PEER, fx.rowId))
            awaitIdle(fx.vm)
            assertEquals("the new attempt was not proposed once", 1, fx.seam.proposalUris.size)
            assertEquals(
                "the new attempt is not at the chosen amount",
                PaymentHarness.zec(AMOUNT),
                PaymentHarness.firstOutputAmount(fx.seam.proposalUris[0])
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
                SendAttemptHarness.storedField(row, "supersededTxIds"),
            )
            assertEquals("fixture sanity", emptyList<String>(), fx.prefs.unexpectedCalls)
        }

    @Test
    fun `control - one pass past the margin proves nothing`() =
        runBlocking {
            val fx = Fixture()
            fx.passAt(EXPIRY + 10)
            fx.assertRefused()
        }

    @Test
    fun `control - two passes at the same tip prove nothing`() =
        runBlocking {
            val fx = Fixture()
            fx.passAt(EXPIRY + 10)
            fx.passAt(EXPIRY + 10)
            fx.assertRefused()
        }

    @Test
    fun `control - a rewind between the passes proves nothing`() =
        runBlocking {
            val fx = Fixture()
            fx.passAt(EXPIRY + 10)
            fx.passAt(EXPIRY + 5)
            fx.assertRefused()
        }

    @Test
    fun `control - one block short of the reorg margin proves nothing`() =
        runBlocking {
            val fx = Fixture()
            fx.passAt(EXPIRY + 9)
            fx.passAt(EXPIRY + 9)
            fx.assertRefused()
        }

    @Test
    fun `control - a mined transaction is never sent again`() =
        runBlocking {
            val fx = Fixture(minedAt = H + 3)
            fx.passAt(EXPIRY + 10)
            fx.passAt(EXPIRY + 11)
            // A mined send may show as sent (it has no Retry then), so the refusal need not name the reason.
            fx.assertRefused(requireNotice = false)
        }

    /** A FAILED "may already have been sent" row with one transaction id, open in a chat screen. */
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
                txs = listOf(ChainTx(1, PEER, memo = null, minedHeight = minedAt, expiryHeight = EXPIRY)),
            )
        val vm: ChatViewModel =
            run {
                prefs.pending[rowId] =
                    SendAttemptHarness.possiblySentRow(rowId, PEER, TEXT, listOf(txId(1)), attemptHeight = H, marker = marker)
                runBlocking { SendAttemptHarness.openChat(prefs, seam, wallet) }
            }

        suspend fun passAt(tip: Long) {
            wallet.scanned = tip
            wallet.network = tip
            SendAttemptHarness.runPass(vm, wallet)
        }

        suspend fun assertRefused(requireNotice: Boolean = true) {
            assertFalse("Retry was accepted without proof that nothing was sent", vm.retryMessage(PEER, rowId))
            awaitIdle(vm)
            assertEquals("a payment was proposed without proof", 0, seam.proposalUris.size)
            val state = PaymentHarness.sendMessageState(vm)
            assertTrue(
                "the refusal did not say the message may already have been sent: $state",
                !requireNotice || (state is SendMessageState.Error && state.message.contains(MAY_HAVE_BEEN_SENT, ignoreCase = true)),
            )
            assertTrue("the row left the disk", prefs.pending.containsKey(rowId))
        }
    }
}
