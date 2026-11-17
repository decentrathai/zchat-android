package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.screen.chat.crypto.E2EEncryption
import co.electriccoin.zcash.ui.screen.chat.viewmodel.HandshakeHarness.awaitIdle
import co.electriccoin.zcash.ui.screen.chat.viewmodel.HandshakeHarness.handleKexMemo
import co.electriccoin.zcash.ui.screen.chat.viewmodel.HandshakeHarness.handshakeViewModel
import co.electriccoin.zcash.ui.screen.chat.viewmodel.HandshakeHarness.kexMemoFrom
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * HS-3 — DOUBLE-PAY (automatic): a peer re-sending the SAME key under a new txid makes us pay another
 * KEXACK.
 *
 * A 2.12.4 peer that backs out mid-KEX and reopens pays a fresh KEX carrying the same key. The txid dedup
 * cannot catch it (new txid), and at 8134b88d3 the received-KEX branch checks only the per-ViewModel
 * in-memory map before paying; the durable "KEXACK already sent for this key" marker is never read there.
 * A restarted app, or any other ChatViewModel instance, therefore pays a second ~17,000 zatoshi KEXACK and
 * also spends one unit of the wallet-wide KEXACK budget.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class KexAckReceivedSameKeyNewTxidTest {
    private val sendScopes = SendHarnessScopes()

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
    fun `a same-key KEX under a new txid after our KEXACK was paid builds no proposal and uses no budget`() =
        runBlocking {
            val peer = "usynthetickexacksamekey00001"
            val peerKeys = E2EEncryption.generateKeyPair()
            val prefs =
                HandshakePrefs(peer).apply {
                    e2ePeerKey[peer] = peerKeys.publicKey
                    receivedKex[peer] = peerKeys.publicKey
                    sentKexAck[peer] = peerKeys.publicKey // our KEXACK for this key was already paid
                }
            val seam = HandshakeSeam(peer)
            val freshVm = handshakeViewModel(prefs, seam)

            handleKexMemo(freshVm, kexMemoFrom(peer, peerKeys), txId = "synthetic-rekex-new-txid")
            awaitIdle(freshVm)

            assertEquals("fixture sanity", emptyList<String>(), prefs.unexpectedCalls)
            assertEquals(
                "a peer re-sending the same key under a new txid made us pay another KEXACK. The durable " +
                    "sent-marker must be checked in the received-KEX branch before the budget and the send.",
                0,
                seam.proposalRequests.get(),
            )
            assertEquals("the KEXACK budget must not be consumed for an ack already paid", 0, prefs.budgetConsumed.get())
            assertEquals(peerKeys.publicKey, prefs.sentKexAck[peer])
        }

    /**
     * Control, GREEN before and after the fix: the same fixture without the durable marker reaches the ACK
     * and pays exactly one — so the zero above is the marker check, not a fixture that never gets there.
     */
    @Test
    fun `control - the same KEX with no KEXACK paid yet is acked once`() =
        runBlocking {
            val peer = "usynthetickexacksamekey00002"
            val peerKeys = E2EEncryption.generateKeyPair()
            val prefs =
                HandshakePrefs(peer).apply {
                    e2ePeerKey[peer] = peerKeys.publicKey
                    receivedKex[peer] = peerKeys.publicKey
                }
            val seam = HandshakeSeam(peer)
            val freshVm = handshakeViewModel(prefs, seam)

            handleKexMemo(freshVm, kexMemoFrom(peer, peerKeys), txId = "synthetic-kex-txid")
            awaitIdle(freshVm)

            assertEquals("fixture sanity", emptyList<String>(), prefs.unexpectedCalls)
            assertEquals(1, seam.proposalRequests.get())
            assertEquals(1, seam.broadcasts.size)
            assertEquals(1, prefs.budgetConsumed.get())
            assertEquals(peerKeys.publicKey, prefs.sentKexAck[peer])
        }
}
