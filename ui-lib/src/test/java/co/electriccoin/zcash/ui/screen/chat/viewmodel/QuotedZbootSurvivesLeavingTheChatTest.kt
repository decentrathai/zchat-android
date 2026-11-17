package co.electriccoin.zcash.ui.screen.chat.viewmodel

import androidx.lifecycle.viewModelScope
import co.electriccoin.zcash.ui.nostr.NOSTRIdentity
import co.electriccoin.zcash.ui.nostr.NostrChatBridge
import co.electriccoin.zcash.ui.screen.chat.crypto.E2EEncryption
import co.electriccoin.zcash.ui.screen.chat.crypto.E2EKeyPair
import co.electriccoin.zcash.ui.screen.chat.viewmodel.HandshakeHarness.awaitIdle
import co.electriccoin.zcash.ui.screen.chat.viewmodel.HandshakeHarness.awaitTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
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
 * ZC-534 (fix-all 2.13.0, train T6a; ZC-534-535-536-SPEC §2, part 1) — the ZBOOT a confirmed Reset or key-change
 * reconnect quoted is lost when the user leaves the chat while it waits for a block.
 *
 * The ZBOOT (our relay identity) is paid after the contact's KEXACK. With one spendable note it meets the note the KEX
 * just locked and waits for the next block. ChatViewModel.sendNostrBootHandshake launched it on the chat screen's
 * viewModelScope, so leaving the chat cancelled it there with its delivered-identity marker already set: it was recorded
 * as delivered and never sent, and calls and relay delivery stayed one-way until Reconnect or a Call tap.
 *
 * Must hold: the ZBOOT runs on the process-wide send scope; leaving the chat while it waits does not lose it, and it is
 * paid exactly once on the allowance its question wrote, with no further question. Controls (green before and after):
 * with the chat kept open it is paid once; a ZBOOT already submitted when the chat is left is never paid again.
 *
 * A confirmed first message's quoted ZBOOT runs through the same function (the KEXACK trigger); the Reset and reconnect
 * cases stand for that shared path. Drives the real ViewModel through the real use case and repository
 * ([ResetOwedChat], [KeyChangeReconnectChat]). Every address and key is synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class QuotedZbootSurvivesLeavingTheChatTest {
    private val sendScopes = SendHarnessScopes()

    private companion object {
        const val PEER = "usyntheticzc534zbootleave01"
    }

    @Before
    fun setUp() {
        sendScopes.open()
        Dispatchers.setMain(UnconfinedTestDispatcher())
        AutoSpendVm.resetProcessState()
        AutoSpendVm.setHistoryRead(true)
        FirstMessagePlanChat.resetOwedKexState()
        NostrChatBridge.unregisterPublisher()
    }

    @After
    fun tearDown() {
        sendScopes.close()
        Dispatchers.resetMain()
        AutoSpendVm.resetProcessState()
        FirstMessagePlanChat.resetOwedKexState()
    }

    @Test
    fun `a confirmed Reset's ZBOOT waiting for a block is still paid once after the chat is left`() =
        runBlocking {
            val chat = ResetOwedChat(PEER, consent = false, failFirstZbootWithStillConfirming = true)
            val vm = chat.viewModel()
            val recorder = SendStateRecorder(vm)
            confirmResetUntilItsZbootWaits(chat, vm)

            // The user leaves the chat: its screen's ViewModel is cleared while the ZBOOT waits for a block.
            vm.viewModelScope.coroutineContext.job
                .cancelAndJoin()
            chat.heights.advance()
            awaitIdle(vm)

            assertEquals(
                "the ZBOOT a confirmed Reset quoted was lost when the chat screen closed: recorded as delivered, never sent",
                1,
                chat.seam.submittedOf("ZBOOT"),
            )
            assertTrue("the ZBOOT did not use up the Reset's allowance", chat.zbootQuoted[PEER] != true)
            assertEquals("the Reset quoted 2 on-chain transactions", 2, chat.seam.submitted.size)
            recorder.assertNoHandshakeConsentQuestion("the ZBOOT the Reset quoted was asked about again")
            assertEquals("the one-off Reset was turned into the standing handshake consent", 0, chat.consentWrites.get())
            recorder.stop()
        }

    @Test
    fun `a confirmed reconnect's ZBOOT waiting for a block is still paid once after the chat is left`() =
        runBlocking {
            val chat = KeyChangeReconnectChat(PEER, consent = false, failFirstZbootWithStillConfirming = true)
            val vm = chat.viewModel()
            val recorder = SendStateRecorder(vm)
            confirmReconnectUntilItsZbootWaits(chat, vm)

            vm.viewModelScope.coroutineContext.job
                .cancelAndJoin()
            chat.owed.heights.advance()
            awaitIdle(vm)

            assertEquals(
                "the ZBOOT a confirmed reconnect quoted was lost when the chat screen closed: recorded as delivered, never sent",
                1,
                chat.seam.submittedOf("ZBOOT"),
            )
            assertTrue("the ZBOOT did not use up the reconnect's allowance", chat.owed.zbootQuoted[PEER] != true)
            assertEquals("the reconnect quoted 2 on-chain transactions", 2, chat.seam.submitted.size)
            recorder.assertNoHandshakeConsentQuestion("the ZBOOT the reconnect quoted was asked about again")
            assertEquals("the one-off reconnect was turned into the standing consent", 0, chat.owed.consentWrites.get())
            recorder.stop()
        }

    /** Control, green before and after: nothing is left, the wait ends with the next block and the ZBOOT is paid once. */
    @Test
    fun `control - with the chat kept open the same ZBOOT is paid once`() =
        runBlocking {
            val chat = ResetOwedChat(PEER, consent = false, failFirstZbootWithStillConfirming = true)
            val vm = chat.viewModel()
            confirmResetUntilItsZbootWaits(chat, vm)

            chat.heights.advance()
            awaitIdle(vm)
            chat.tick(vm)
            awaitIdle(vm)

            assertEquals("the waiting ZBOOT was not paid on the next block", 1, chat.seam.submittedOf("ZBOOT"))
            assertEquals("the Reset quoted 2 on-chain transactions", 2, chat.seam.submitted.size)
            assertTrue("the ZBOOT did not use up the Reset's allowance", chat.zbootQuoted[PEER] != true)
        }

    /** Control, green before and after: leaving after the submit pays nothing more, whatever arrives later. */
    @Test
    fun `control - a ZBOOT already submitted when the chat is left is never paid again`() =
        runBlocking {
            val chat = ResetOwedChat(PEER, consent = false)
            val vm = chat.viewModel()
            vm.resetSecureSession(PEER)
            awaitIdle(vm)
            vm.confirmPaidAction()
            awaitIdle(vm)
            val contactKeys = E2EEncryption.generateKeyPair()
            chat.kexAckFromContact(vm, contactKeys, txId = "synthetic-zc534-kexack-03")
            awaitIdle(vm)
            assertEquals("fixture sanity: the quoted ZBOOT went", 1, chat.seam.submittedOf("ZBOOT"))

            vm.viewModelScope.coroutineContext.job
                .cancelAndJoin()
            // Another screen's ViewModel (the chat list) sees the later blocks and the contact's own ZBOOT.
            val list = chat.viewModel()
            chat.tick(list)
            awaitIdle(list)
            assertTrue("fixture sanity: the contact's ZBOOT verifies", chat.zbootFromContact(list, contactKeys, relayKeyOfContact()))
            awaitIdle(list)
            chat.tick(list)
            awaitIdle(list)

            assertEquals("a ZBOOT submitted before the chat was left was paid again", 1, chat.seam.submittedOf("ZBOOT"))
            assertEquals("the Reset quoted 2 on-chain transactions", 2, chat.seam.submitted.size)
            assertFalse("an owed ZBOOT was left behind a submitted one", PEER in chat.zbootOwed)
        }

    private suspend fun confirmResetUntilItsZbootWaits(
        chat: ResetOwedChat,
        vm: ChatViewModel,
    ) {
        vm.resetSecureSession(PEER)
        awaitIdle(vm)
        chat.reset.assertAskedFirst(vm)
        vm.confirmPaidAction()
        awaitIdle(vm)
        assertEquals("fixture sanity: the Reset's KEX went", 1, chat.seam.submittedOf("KEX"))
        kexAckUntilTheZbootWaits(chat, vm, E2EEncryption.generateKeyPair())
    }

    private suspend fun confirmReconnectUntilItsZbootWaits(
        chat: KeyChangeReconnectChat,
        vm: ChatViewModel,
    ) {
        vm.dismissE2EKeyChanged(PEER)
        awaitIdle(vm)
        chat.assertAskedFirst(vm)
        vm.confirmPaidAction()
        awaitIdle(vm)
        assertEquals("fixture sanity: the reconnect's KEX went", 1, chat.seam.submittedOf("KEX"))
        kexAckUntilTheZbootWaits(chat.owed, vm, E2EEncryption.generateKeyPair())
    }

    /** The contact's KEXACK fires our ZBOOT; its first proposal meets the note the KEX locked and waits for a block. */
    private suspend fun kexAckUntilTheZbootWaits(
        chat: ResetOwedChat,
        vm: ChatViewModel,
        contactKeys: E2EKeyPair,
    ) {
        chat.kexAckFromContact(vm, contactKeys, txId = "synthetic-zc534-kexack-01")
        awaitTrue { chat.heights.isWaiting() }
        assertEquals("fixture sanity: the ZBOOT met the still-confirming wait", 1, chat.seam.zbootProposals.get())
        assertEquals("fixture sanity: no ZBOOT went yet", 0, chat.seam.submittedOf("ZBOOT"))
        assertEquals("fixture sanity: the allowance stands while the ZBOOT waits", true, chat.zbootQuoted[PEER])
    }

    private fun relayKeyOfContact(): String =
        NOSTRIdentity
            .fromSeed(ByteArray(64) { (it + 7).toByte() })
            .publicKey
            .joinToString("") { "%02x".format(it) }
}
