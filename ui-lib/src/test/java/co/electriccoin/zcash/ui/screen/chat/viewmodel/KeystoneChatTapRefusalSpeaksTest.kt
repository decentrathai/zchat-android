package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.nostr.NostrChatBridge
import co.electriccoin.zcash.ui.screen.chat.model.SendMessageState
import co.electriccoin.zcash.ui.screen.chat.usecase.CreateChunkedMessageProposalUseCase
import co.electriccoin.zcash.ui.screen.chat.util.toZchatUserMessage
import co.electriccoin.zcash.ui.screen.chat.viewmodel.HandshakeHarness.awaitIdle
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
 * K0-2 (fix-all 2.13.0, train T1; K0 design B10) — a message, a reply or a reaction tapped in a chat while a Keystone
 * account is selected.
 *
 * The send chokepoint already refuses these at HEAD (ZC-102: directSubmit + skipNavigation on Keystone), but the chat
 * error mapper has no case for that refusal, so the user reads the caller's generic fallback ("Failed to send
 * message") and learns nothing about why or what to do.
 *
 * Wanted: every chat send refused for Keystone shows ChatSendCopy.KEYSTONE_CHATS_CLOSED (it says nothing was sent or
 * paid, and to pick the ZChat account from the account name on the Wallet tab). Nothing is built or sent. The
 * resource and its Spanish text are pinned in KeystoneClosedStringsParityTest.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class KeystoneChatTapRefusalSpeaksTest {
    private val sendScopes = SendHarnessScopes()

    @Before
    fun setUp() {
        sendScopes.open()
        Dispatchers.setMain(UnconfinedTestDispatcher())
        NostrChatBridge.unregisterPublisher()
    }

    @After
    fun tearDown() {
        sendScopes.close()
        NostrChatBridge.unregisterPublisher()
        Dispatchers.resetMain()
    }

    @Test
    fun `a message on a Keystone account says Keystone can't sign chats`() =
        runBlocking { assertRefusedWithClosedText { sendMessage(PEER, "synthetic k0 hello") } }

    @Test
    fun `a reply on a Keystone account says Keystone can't sign chats`() =
        runBlocking { assertRefusedWithClosedText { sendReply(PEER, "synthetic k0 reply", QUOTED_ID, "synthetic quote") } }

    @Test
    fun `a reaction on a Keystone account says Keystone can't sign chats`() =
        runBlocking {
            assertRefusedWithClosedText {
                sendReaction(PEER, QUOTED_ID, "👍")
                confirmPaidAction()
            }
        }

    @Test
    fun `the chat error mapper names the Keystone refusal anywhere in the cause chain`() {
        val refusal = UnsupportedOperationException(CreateChunkedMessageProposalUseCase.KEYSTONE_AUTO_SEND_REFUSED_MESSAGE)
        assertEquals(KeystoneChatScreen.closedText(), refusal.toZchatUserMessage("Failed to send message"))
        assertEquals(
            KeystoneChatScreen.closedText(),
            IllegalStateException("wrapped", refusal).toZchatUserMessage("Failed to send message"),
        )
    }

    /** Control, green before and after: an unrelated UnsupportedOperationException still reads as the fallback. */
    @Test
    fun `control - another unsupported operation still reads as the caller's fallback`() {
        assertEquals("Failed to send message", UnsupportedOperationException("synthetic").toZchatUserMessage("Failed to send message"))
    }

    private suspend fun assertRefusedWithClosedText(send: ChatViewModel.() -> Unit) {
        val screen = KeystoneChatScreen.open(PEER)

        screen.vm.send()
        awaitIdle(screen.vm)

        assertEquals("a Keystone proposal was built for a chat send", emptyList<String>(), screen.keystone.calls)
        assertEquals("nothing may reach the SDK", 0, screen.seam.submits.get())
        assertEquals(
            "the chat must say that Keystone can't sign chats and where to switch",
            SendMessageState.Error(KeystoneChatScreen.closedText()),
            PaymentHarness.sendMessageState(screen.vm),
        )
    }

    private companion object {
        const val PEER = "usynthetick0tappeer000000001"
        const val QUOTED_ID = "synthetic-k0-quoted-message"
    }
}
