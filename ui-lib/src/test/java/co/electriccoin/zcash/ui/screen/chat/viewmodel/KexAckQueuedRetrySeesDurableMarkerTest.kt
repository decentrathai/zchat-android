package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.screen.chat.crypto.E2EEncryption
import co.electriccoin.zcash.ui.screen.chat.viewmodel.HandshakeHarness.awaitIdle
import co.electriccoin.zcash.ui.screen.chat.viewmodel.HandshakeHarness.awaitTrue
import co.electriccoin.zcash.ui.screen.chat.viewmodel.HandshakeHarness.handshakeViewModel
import co.electriccoin.zcash.ui.screen.chat.viewmodel.HandshakeHarness.stillConfirming
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
 * The retry-loop marker recheck (found while verifying HS-1): a KEXACK queued in the block-wait loop pays
 * even after another attempt has already paid for the same key.
 *
 * With a single note, a KEXACK frequently hits "funds still confirming" and waits up to 4 x 180 s for the
 * next block. At 8134b88d3 the loop reads the durable "KEXACK already sent for this key" marker only once,
 * before it starts; when the block lands it builds and pays a second proposal no matter what was recorded in
 * the meantime. The durable marker is the one thing every ChatViewModel instance, every restart and every
 * entry point share, so the loop must look at it again before each attempt.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class KexAckQueuedRetrySeesDurableMarkerTest {
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
    fun `a KEXACK waiting for a block does not pay once the durable marker says the key was acked`() =
        runBlocking {
            val peer = "usynthetickexackqueuedretry1"
            val peerKeys = E2EEncryption.generateKeyPair()
            val prefs =
                HandshakePrefs(peer).apply {
                    e2ePeerKey[peer] = peerKeys.publicKey
                    receivedKex[peer] = peerKeys.publicKey
                }
            val seam = HandshakeSeam(peer, failFirstProposalWith = stillConfirming())
            val heights = HeightFeed()
            val vm = handshakeViewModel(prefs, seam, heights, ironwoodPending = 200_000L)

            vm.retryKexAckIfResponder(peer)
            awaitTrue { heights.isWaiting() }
            assertEquals("fixture sanity: the first attempt hit the shortfall", 1, seam.proposalRequests.get())

            // The other attempt's KEXACK for this key lands and records the durable marker.
            prefs.proxy.setSentKexAckPubkey(peer, peerKeys.publicKey)
            heights.advance()
            awaitIdle(vm)

            assertEquals("fixture sanity", emptyList<String>(), prefs.unexpectedCalls)
            assertEquals(
                "the queued KEXACK paid after the durable marker already recorded an ack of this key. The " +
                    "retry loop must re-check the marker before every attempt.",
                1,
                seam.proposalRequests.get(),
            )
            assertEquals(0, seam.broadcasts.size)
            assertEquals("the other attempt's marker must be left in place", peerKeys.publicKey, prefs.sentKexAck[peer])
        }

    /**
     * Control, GREEN before and after the fix: with no other ack recorded, the queued KEXACK retries after
     * the block and pays exactly once, and the marker names the key.
     */
    @Test
    fun `control - with no other ack recorded the queued KEXACK retries and pays once`() =
        runBlocking {
            val peer = "usynthetickexackqueuedretry2"
            val peerKeys = E2EEncryption.generateKeyPair()
            val prefs =
                HandshakePrefs(peer).apply {
                    e2ePeerKey[peer] = peerKeys.publicKey
                    receivedKex[peer] = peerKeys.publicKey
                }
            val seam = HandshakeSeam(peer, failFirstProposalWith = stillConfirming())
            val heights = HeightFeed()
            val vm = handshakeViewModel(prefs, seam, heights, ironwoodPending = 200_000L)

            vm.retryKexAckIfResponder(peer)
            awaitTrue { heights.isWaiting() }
            heights.advance()
            awaitIdle(vm)

            assertEquals("fixture sanity", emptyList<String>(), prefs.unexpectedCalls)
            assertEquals(2, seam.proposalRequests.get())
            assertEquals(1, seam.broadcasts.size)
            assertEquals(peerKeys.publicKey, prefs.sentKexAck[peer])
        }
}
