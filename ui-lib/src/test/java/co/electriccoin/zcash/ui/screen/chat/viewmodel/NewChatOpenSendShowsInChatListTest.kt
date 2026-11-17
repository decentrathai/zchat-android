package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.nostr.NostrChatBridge
import co.electriccoin.zcash.ui.screen.chat.ChatDetail
import co.electriccoin.zcash.ui.screen.chat.model.MessageStatus
import co.electriccoin.zcash.ui.screen.chat.viewmodel.RelayKeyBinding.code
import co.electriccoin.zcash.ui.screen.chat.viewmodel.RelayKeyBinding.composeVm
import co.electriccoin.zcash.ui.screen.chat.viewmodel.RelayKeyBinding.ready
import co.electriccoin.zcash.ui.screen.chat.viewmodel.RelayKeyBinding.relayKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * M1 (fix-all 2.13.0, train T4b; money SPEC §1.1 item 6, §1.3 step 7, §5 R1-7): a free first message sent from New Chat
 * shows in the chat list at once.
 *
 * At HEAD, ZchatComposeVM.sendFirstOpenMessageOverNostr writes the outgoing row to the store only. Every other writer
 * also hands the row to the ChatViewModels already alive through NostrChatBridge.emitLocalMessage; the chat list's
 * ChatViewModel read the store once, at its init, so it never learns of the row, and the new chat stays out of the list
 * until a restart (on the Fold, until a reply came in).
 *
 * Wanted: the final row (re-keyed to nmsg-<rumor id> and SENT when a relay acknowledged it, FAILED under its local id
 * when none did) reaches a live list ChatViewModel, once, and no SENDING row is left behind. Real ChatViewModel
 * (observeLocalMessages, as its init starts it) and real ZchatComposeVM over one [RelayKeyStore]; the relay publisher is
 * a fake with 1 or 0 acknowledgements. Nothing touches the chain. All values are synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NewChatOpenSendShowsInChatListTest {
    private val sendScopes = SendHarnessScopes()
    private val vms = mutableListOf<ZchatComposeVM>()
    private val peer = SyntheticUnifiedAddress.mainnet(4_701)
    private val key = relayKey(71)

    @Before
    fun setUp() {
        sendScopes.open()
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        NostrChatBridge.unregisterPublisher()
        vms.forEach(RelayKeyBinding::close)
        vms.clear()
        sendScopes.close()
        Dispatchers.resetMain()
    }

    private suspend fun sendFirstOpenMessage(
        store: RelayKeyStore,
        router: RelayKeyBinding.RecordingRouter,
    ) {
        val compose = composeVm(store, navigationRouter = router).also { vms += it }
        compose.ready().onRecipientChange(code(peer, key))
        compose.ready().onMessageChange("synthetic free hello")
        assertTrue(compose.ready().isFreeOpenSend, "fixture: the send is the free Open send")
        compose.ready().onSendClick()
        withTimeoutOrNull(10_000) { while (router.replaced.isEmpty()) delay(10) }
        assertEquals(listOf<Any>(ChatDetail(peer)), router.replaced.toList(), "fixture: the send did not finish")
    }

    @Test
    fun `an acknowledged first free message reaches the live chat list as one SENT row`() =
        runBlocking {
            NostrChatBridge.registerPublisher { _, _ -> NostrChatBridge.PublishResult(1, "synthetic-m1-rumor") }
            val store = RelayKeyStore()
            val list = RelayKeyChatScreen.chatViewModel(store)
            RelayKeyChatScreen.start(list, "observeLocalMessages")

            sendFirstOpenMessage(store, RelayKeyBinding.RecordingRouter())
            withTimeoutOrNull(2_000) { while (RelayKeyChatScreen.rows(list).isEmpty()) delay(10) }

            val shown = RelayKeyChatScreen.rows(list).filter { it.peerAddress == peer }
            assertEquals(1, shown.size, "the chat list shows ${shown.size} rows for the new chat: $shown")
            assertEquals("nmsg-synthetic-m1-rumor", shown.single().id)
            assertEquals(MessageStatus.SENT, shown.single().status)
            assertTrue(shown.single().isOutgoing)
        }

    @Test
    fun `an unacknowledged first free message reaches the live chat list as one FAILED row`() =
        runBlocking {
            NostrChatBridge.registerPublisher { _, _ -> NostrChatBridge.PublishResult(0, null) }
            val store = RelayKeyStore()
            val list = RelayKeyChatScreen.chatViewModel(store)
            RelayKeyChatScreen.start(list, "observeLocalMessages")

            sendFirstOpenMessage(store, RelayKeyBinding.RecordingRouter())
            withTimeoutOrNull(2_000) { while (RelayKeyChatScreen.rows(list).isEmpty()) delay(10) }

            val shown = RelayKeyChatScreen.rows(list).filter { it.peerAddress == peer }
            assertEquals(1, shown.size, "the chat list shows ${shown.size} rows for the new chat: $shown")
            assertEquals(MessageStatus.FAILED, shown.single().status)
            assertTrue(shown.single().id.startsWith("nostr-out-"), "the FAILED row is not the stored row: ${shown.single().id}")
            assertTrue(store.rows.containsKey(shown.single().id), "the shown row is not the stored row")
        }
}
