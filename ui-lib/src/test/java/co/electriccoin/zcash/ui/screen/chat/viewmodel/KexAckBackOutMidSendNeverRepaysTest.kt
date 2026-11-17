package co.electriccoin.zcash.ui.screen.chat.viewmodel

import androidx.lifecycle.viewModelScope
import co.electriccoin.zcash.ui.common.model.SubmitResult
import co.electriccoin.zcash.ui.screen.chat.crypto.E2EEncryption
import co.electriccoin.zcash.ui.screen.chat.viewmodel.HandshakeHarness.awaitIdle
import co.electriccoin.zcash.ui.screen.chat.viewmodel.HandshakeHarness.handshakeViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * HS-2 — DOUBLE-PAY (automatic): backing out of a chat while its KEXACK is being proven pays it again on
 * reopen.
 *
 * retryKexAckIfResponder pays the KEXACK on viewModelScope, but the repository proves and broadcasts in its
 * own detached scope, so backing out cancels only the await: the KEXACK still reaches the network. At
 * 8134b88d3 sendKEXAckMessage catches that CancellationException and returns false, no durable marker is
 * written, and the next chat open pays again. For a Vault contact (whose NOSTR key never arrives) every
 * back-out-and-reopen repeats it. The KEX sender was fixed for exactly this in aea64f013; the KEXACK was
 * missed.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class KexAckBackOutMidSendNeverRepaysTest {
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
    fun `backing out while the KEXACK broadcast is in flight never pays it again on reopen`() =
        runBlocking {
            val peer = "usynthetickexackbackout00001"
            val peerKeys = E2EEncryption.generateKeyPair()
            val prefs = responderPrefs(peer, peerKeys.publicKey)
            val seam = HandshakeSeam(peer, holdFirstSubmit = true)
            val firstOpen = handshakeViewModel(prefs, seam)

            firstOpen.retryKexAckIfResponder(peer)
            withTimeout(HandshakeHarness.TIMEOUT_MS) { seam.firstSubmitEntered.await() }
            // The user backs out: the ViewModel is cleared and its scope cancelled mid-broadcast.
            firstOpen.viewModelScope.coroutineContext.job
                .cancelAndJoin()
            // The detached repository scope carries on and the KEXACK reaches the network anyway.
            seam.release.complete(Unit)
            withTimeout(HandshakeHarness.TIMEOUT_MS) { seam.firstSubmitDone.await() }

            // The user reopens the chat before the peer's ZBOOT arrives.
            val reopen = handshakeViewModel(prefs, seam)
            reopen.retryKexAckIfResponder(peer)
            awaitIdle(reopen)

            assertEquals("fixture sanity", emptyList<String>(), prefs.unexpectedCalls)
            assertEquals(
                "the KEXACK was broadcast and paid for, but backing out left no durable marker, so reopening " +
                    "the chat paid a second one. Cancellation is not failure (mayReArmHandshakeMarker).",
                1,
                seam.broadcasts.size,
            )
            assertEquals("the durable sent-marker must name the acked key", peerKeys.publicKey, prefs.sentKexAck[peer])
        }

    /**
     * Control, GREEN before and after the fix: a KEXACK the network rejected outright (nothing landed)
     * must still be retried on the next open, or the responder side of the handshake stalls forever.
     */
    @Test
    fun `control - a KEXACK the network rejected is retried on the next open`() =
        runBlocking {
            val peer = "usynthetickexackbackout00002"
            val peerKeys = E2EEncryption.generateKeyPair()
            val prefs = responderPrefs(peer, peerKeys.publicKey)
            val seam =
                HandshakeSeam(
                    peer,
                    firstSubmitOutcome = SubmitResult.Failure(txIds = listOf("rejected"), code = 18, description = "rejected"),
                )
            val firstOpen = handshakeViewModel(prefs, seam)
            firstOpen.retryKexAckIfResponder(peer)
            awaitIdle(firstOpen)
            assertEquals("a rejected KEXACK must not leave the marker set", null, prefs.sentKexAck[peer])

            val reopen = handshakeViewModel(prefs, seam)
            reopen.retryKexAckIfResponder(peer)
            awaitIdle(reopen)

            assertEquals("fixture sanity", emptyList<String>(), prefs.unexpectedCalls)
            assertEquals(2, seam.proposalRequests.get())
            assertEquals(1, seam.broadcasts.size)
            assertEquals(peerKeys.publicKey, prefs.sentKexAck[peer])
        }

    private fun responderPrefs(peer: String, peerKey: String): HandshakePrefs =
        HandshakePrefs(peer).apply {
            e2ePeerKey[peer] = peerKey
            receivedKex[peer] = peerKey
        }
}
