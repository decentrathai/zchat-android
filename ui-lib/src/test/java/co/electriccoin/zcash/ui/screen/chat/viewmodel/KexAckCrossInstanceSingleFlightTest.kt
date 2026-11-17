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
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * HS-1 — DOUBLE-PAY (automatic): the chat list and the chat detail each pay a KEXACK for one first contact.
 *
 * AndroidChat builds one ChatViewModel for the chat list and another for the chat detail. A peer's
 * first-contact KEX is handled by the list VM, which starts paying the KEXACK. Meanwhile the user taps the
 * new contact, and the detail VM's LaunchedEffect runs retryKexAckIfResponder. At 8134b88d3 the in-flight
 * claim and the "already acked this key" map are per ViewModel, and the durable sent-marker is written only
 * after the send returns, so the detail VM passes every gate and pays a second ~17,000 zatoshi KEXACK.
 *
 * Both tests hold the first KEXACK's broadcast open at the settlement seam (a phone spends seconds
 * proving, or minutes in the block-wait loop) and run the second call site inside that window.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class KexAckCrossInstanceSingleFlightTest {
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
    fun `the list VM acking an inbound KEX and the detail VM retrying on open pay one KEXACK`() =
        runBlocking {
            val peer = "usynthetickexacksingleflight1"
            val peerKeys = E2EEncryption.generateKeyPair()
            val prefs = HandshakePrefs(peer)
            // W-62 (ZC-599): a key exchange at a chat that still waits for the contact's first key (our key pair, no contact key)
            // now waits for Accept. This case is about the process-wide single flight of the answer, so the contact's key is already held (the key exchange repeats it).
            prefs.e2ePeerKey[peer] = peerKeys.publicKey
            val seam = HandshakeSeam(peer, holdFirstSubmit = true)
            val listVm = handshakeViewModel(prefs, seam)
            val detailVm = handshakeViewModel(prefs, seam)

            // The list VM's history scan hands it the peer's first-contact KEX; it starts paying the KEXACK.
            handleKexMemo(listVm, kexMemoFrom(peer, peerKeys), txId = "synthetic-first-contact-kex")
            withTimeout(HandshakeHarness.TIMEOUT_MS) { seam.firstSubmitEntered.await() }

            // The user opens the new chat while that KEXACK is still being proven and broadcast.
            detailVm.retryKexAckIfResponder(peer)
            awaitIdle(detailVm)

            seam.release.complete(Unit)
            awaitIdle(listVm)

            assertEquals("fixture sanity", emptyList<String>(), prefs.unexpectedCalls)
            assertEquals(
                "the chat list and the chat detail each paid a KEXACK for the same first contact. The " +
                    "in-flight claim must be process-wide and the durable marker checked after taking it.",
                1,
                seam.broadcasts.size,
            )
            assertEquals("exactly one KEXACK proposal may be built", 1, seam.proposalRequests.get())
            assertEquals(peerKeys.publicKey, prefs.sentKexAck[peer])
        }

    @Test
    fun `two ViewModels retrying the KEXACK on open at the same time pay one KEXACK`() =
        runBlocking {
            val peer = "usynthetickexacksingleflight2"
            val peerKeys = E2EEncryption.generateKeyPair()
            val prefs = HandshakePrefs(peer)
            // We are the responder: the peer's KEX was received earlier, our KEXACK never landed.
            prefs.e2ePeerKey[peer] = peerKeys.publicKey
            prefs.receivedKex[peer] = peerKeys.publicKey
            val seam = HandshakeSeam(peer, holdFirstSubmit = true)
            val first = handshakeViewModel(prefs, seam)
            val second = handshakeViewModel(prefs, seam)

            first.retryKexAckIfResponder(peer)
            withTimeout(HandshakeHarness.TIMEOUT_MS) { seam.firstSubmitEntered.await() }
            second.retryKexAckIfResponder(peer)
            awaitIdle(second)

            seam.release.complete(Unit)
            awaitIdle(first)

            assertEquals("fixture sanity", emptyList<String>(), prefs.unexpectedCalls)
            assertEquals("two ViewModels each paid the same KEXACK", 1, seam.broadcasts.size)
            assertEquals(1, seam.proposalRequests.get())
            assertEquals(peerKeys.publicKey, prefs.sentKexAck[peer])
        }
}
