package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.screen.chat.crypto.E2EEncryption
import co.electriccoin.zcash.ui.screen.chat.model.SendMessageState
import co.electriccoin.zcash.ui.screen.chat.view.ChatResourceText
import co.electriccoin.zcash.ui.screen.chat.view.SourceScan
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * ZC-693 (T7B-HANDSHAKE-SPEC rev 2, section 2.20) — the chat-request banner ("tap Accept") is cleared at the Accept tap:
 * `acceptInboundKexRequest` removes the hold before it pays the answer, and the chat screen hides the banner by itself
 * (`chatRequestAccepted`). When the answer then pays nothing (the user answers "Not now" to "Allow handshake
 * transactions?"), the banner is gone although nothing was sent, and the contact's request is forgotten after a restart.
 *
 * Must hold: the hold is cleared in ONE place, after the answer's submit returned sent or may-have-been-sent.
 * - (a) Accept, the consent question, "Not now": the hold stays (on disk and as the view model reads it); a new view
 *   model: still held, its chat open raises no question and builds nothing; a second Accept asks again; Allow pays
 *   exactly one answer and the hold goes.
 * - (e) the chat screen no longer keeps its own "accepted" state (source pin): the banner reads the view model only.
 *
 * [KexFromStrangerWaitsForAcceptTest]'s harness (HandshakePrefs, HandshakeSeam); its file is not edited. Every
 * address and key is synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AcceptBannerStaysUntilTheKeyExchangeIsAnsweredTest {
    private val sendScopes = SendHarnessScopes()

    @Before
    fun setUp() {
        sendScopes.open()
        Dispatchers.setMain(UnconfinedTestDispatcher())
        AutoSpendVm.resetProcessState()
    }

    @After
    fun tearDown() {
        sendScopes.close()
        Dispatchers.resetMain()
        AutoSpendVm.resetProcessState()
    }

    private fun accept(
        vm: ChatViewModel,
        peer: String,
    ) {
        vm.acceptInboundKexRequest(peer)
    }

    private fun asksConsent(vm: ChatViewModel): Boolean {
        val shown = HandshakeHarness.sendMessageState(vm)
        return shown is SendMessageState.NeedsHandshakeSpendConsent
    }

    @Test
    fun `(a) Not now after Accept keeps the request until a later Accept and Allow pay exactly one answer`() =
        runBlocking {
            val stranger = SyntheticUnifiedAddress.mainnet(seed = 693)
            val keys = E2EEncryption.generateKeyPair()
            val prefs = HandshakePrefs(stranger, consent = false, firstContact = true)
            val seam = HandshakeSeam(stranger)
            val vm = handshakeViewModel(prefs, seam)
            handleKexMemo(vm, kexMemoFrom(stranger, keys), txId = "synthetic-zc693-kex")
            awaitIdle(vm)
            assertTrue("fixture: the request waits for Accept", vm.isInboundKexAwaitingAccept(stranger))

            accept(vm, stranger)
            awaitIdle(vm)
            assertTrue("fixture: Accept did not ask about handshake payments", asksConsent(vm))
            vm.dismissOrchardShieldingWarning() // "Not now"
            awaitIdle(vm)

            assertEquals("nothing may be paid after Not now", 0, seam.proposalRequests.get())
            assertTrue(
                "Not now dropped the request's hold (the banner went although nothing was sent)",
                prefs.awaitingAccept[stranger] == keys.publicKey,
            )
            assertTrue("the view model no longer offers the request", vm.isInboundKexAwaitingAccept(stranger))

            // A new screen (or process): still offered; opening the chat asks nothing and builds nothing.
            val later = handshakeViewModel(prefs, seam)
            assertTrue("a new view model no longer offers the request", later.isInboundKexAwaitingAccept(stranger))
            later.retryKexAckIfResponder(stranger)
            awaitIdle(later)
            assertFalse("a chat open asked about money for a request the user has not accepted", asksConsent(later))
            assertEquals("a chat open paid an answer for a request the user has not accepted", 0, seam.proposalRequests.get())

            accept(later, stranger)
            awaitIdle(later)
            assertTrue("a second Accept did not ask again", asksConsent(later))
            later.grantHandshakeSpendConsent()
            awaitIdle(later)

            assertEquals("Allow did not pay exactly one answer", 1, seam.proposalRequests.get())
            assertEquals("the hold stayed after the answer was sent", null, prefs.awaitingAccept[stranger])
            assertEquals("fixture sanity", emptyList<String>(), prefs.unexpectedCalls)
        }

    @Test
    fun `(e) the chat screen keeps no accepted state of its own`() {
        val raw = ChatResourceText.source("ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/chat/AndroidChat.kt")
        val source = SourceScan.stripComments(raw)
        assertFalse("AndroidChat still hides the request banner by itself (chatRequestAccepted)", "chatRequestAccepted" in source)
    }
}
