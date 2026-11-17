package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.nostr.NostrChatBridge
import co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferences
import co.electriccoin.zcash.ui.screen.chat.model.ChatMessage
import co.electriccoin.zcash.ui.screen.chat.model.ConversationMode
import co.electriccoin.zcash.ui.screen.chat.model.MessageStatus
import co.electriccoin.zcash.ui.screen.chat.model.ZMSGProtocol
import co.electriccoin.zcash.ui.screen.chat.viewmodel.RelayKeyBinding.relayKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.util.Collections
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * QA2 Q2-21 (ZC-1545, W-137 B-2): "Not delivered: chat request declined" stays on the sender's first Open message after
 * the contact changes their mind.
 *
 * At 33bb92cdf a ZRCPT|DECLINED from the bound relay key (ZC-1464) maps the earlier relay rows of that chat that only a
 * relay took (SENT, never read) to DECLINED, in the screen's pending list and in the stored rows
 * (ChatViewModel.onRequestDecision). Nothing maps them back: a ZRCPT|RESTORED only clears the contact's "declined" flag
 * (the composer reopens) and returns, and a plain rumor from the bound key (the contact accepted without a token, ZC-1464)
 * clears the flag likewise. So after the receiver restores and accepts, and even after replies arrive, the first message
 * keeps its X.
 *
 * Wanted (FABLE_BEHAVIOUR_DESIGN B-2): the inverse of the decline mapping, DECLINED to SENT ("a relay took it", what the
 * rows showed before the decline), in both places (the screen's list and the stored rows), on
 * (a) a ZRCPT|RESTORED from the bound key and (b) a non-token rumor from the bound key. Only the sealed sender's chat is
 * touched. Nothing is sent and no string changes.
 *
 * The same real ChatViewModel relay reader through NostrChatBridge.dispatch as [RequestDeclinedOnlyFromTheBoundRelayKeyTest],
 * over a store that remembers the declined flag. Every address, key and id is synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DeclinedMarkClearsWhenTheContactComesBackTest {
    private val sendScopes = SendHarnessScopes()
    private val scheduler = TestCoroutineScheduler()
    private val tag = "synthetic-zc1545-${UUID.randomUUID()}"
    private val peer = SyntheticUnifiedAddress.mainnet(15_451)
    private val other = SyntheticUnifiedAddress.mainnet(15_452)
    private val key = relayKey(1_545)
    private val published: MutableList<Pair<String, String>> = Collections.synchronizedList(mutableListOf())

    /** The contacts whose chat the store holds as declined (setPeerRequestDeclined / isPeerRequestDeclined). */
    private val declinedFlag: MutableSet<String> = Collections.synchronizedSet(LinkedHashSet())

    @Before
    fun setUp() {
        sendScopes.open()
        Dispatchers.setMain(UnconfinedTestDispatcher(scheduler))
        AutoSpendVm.resetProcessState()
        AutoSpendVm.setHistoryRead(true)
        NostrChatBridge.unregisterChatNotifier()
        NostrChatBridge.registerPublisher { text, to ->
            published += text to to
            NostrChatBridge.PublishResult(1, "synthetic-zc1545-${published.size}")
        }
    }

    @After
    fun tearDown() {
        NostrChatBridge.unregisterPublisher()
        sendScopes.close()
        Dispatchers.resetMain()
        AutoSpendVm.resetProcessState()
    }

    private fun declined(by: String) = "ZRCPT|DECLINED|${ZMSGProtocol.generateAddressHash(by)}"

    private fun restored(by: String) = "ZRCPT|RESTORED|${ZMSGProtocol.generateAddressHash(by)}"

    /** A bound Open chat whose store keeps the declined flag the way ZchatPreferencesImpl does. */
    private fun store(): Pair<RelayKeyStore, CallLogPrefs> {
        val store =
            RelayKeyStore().apply {
                pubkeys[peer] = key
                modes[peer] = ConversationMode.OPEN
            }
        val prefs =
            CallLogPrefs(store.proxy) { name, args ->
                when (name) {
                    "setPeerRequestDeclined" -> {
                        val who = args[0] as String
                        if (args[1] == true) declinedFlag += who else declinedFlag -= who
                        Unit
                    }
                    "isPeerRequestDeclined" -> (args[0] as String) in declinedFlag
                    else -> CallLogPrefs.PASS
                }
            }
        return store to prefs
    }

    private fun relayRow(
        id: String,
        to: String,
        status: MessageStatus = MessageStatus.SENT,
    ) = ChatMessage(
        id = id,
        txId = null,
        text = "synthetic first words $id",
        timestamp = Instant.now().minusSeconds(600),
        isOutgoing = true,
        peerAddress = to,
        isPending = false,
        status = status,
    )

    private fun seed(
        store: RelayKeyStore,
        vm: ChatViewModel,
        rows: List<ChatMessage>,
    ) {
        rows.forEach { row ->
            store.rows[row.id] =
                ZchatPreferences.PendingMessageData(
                    id = row.id,
                    text = row.text,
                    timestampMillis = row.timestamp.toEpochMilli(),
                    peerAddress = row.peerAddress,
                    isOutgoing = true,
                    isPending = false,
                    status = row.status.name,
                )
        }
        B1bRelay.pending(vm).value = rows
    }

    private fun shown(
        vm: ChatViewModel,
        id: String,
    ): MessageStatus = RelayKeyChatScreen.rows(vm).firstOrNull { it.id == id }?.status ?: fail("row $id is gone from the screen")

    private fun stored(
        store: RelayKeyStore,
        id: String,
    ): String? = store.rows[id]?.status

    /** Dispatches the decline and waits until the screen and the store show the rows as declined (passes today). */
    private suspend fun declineFirst(
        vm: ChatViewModel,
        store: RelayKeyStore,
        prefs: CallLogPrefs,
        id: String,
    ) {
        RelayKeyChatScreen.start(vm, "observeNostrInbound")
        NostrChatBridge.dispatch(B1bRelay.dm(key, declined(peer), "$tag-d-$id"), prefs.proxy)
        B1bRelay.awaitTrue { shown(vm, id) == MessageStatus.DECLINED && stored(store, id) == MessageStatus.DECLINED.name }
        assertEquals(MessageStatus.DECLINED, shown(vm, id), "fixture: the decline did not mark the screen's row")
        assertEquals(MessageStatus.DECLINED.name, stored(store, id), "fixture: the decline did not mark the stored row")
        assertTrue(peer in declinedFlag, "fixture: the decline did not set the declined flag")
    }

    @Test
    fun `a RESTORED from the bound key puts the declined first message back to sent, on screen and in the store`() =
        runBlocking {
            val (store, prefs) = store()
            val vm = B1bRelay.chatViewModel(prefs.proxy)
            val first = relayRow("nmsg-$tag-first", peer)
            // Guards: another contact's declined row and a row that was already read are not this chat's to change.
            val otherDeclined = relayRow("nmsg-$tag-other", other, MessageStatus.DECLINED)
            seed(store, vm, listOf(first, otherDeclined))
            declineFirst(vm, store, prefs, first.id)

            NostrChatBridge.dispatch(B1bRelay.dm(key, restored(peer), "$tag-r1"), prefs.proxy)
            B1bRelay.awaitTrue { "$tag-r1" in store.seen }
            B1bRelay.awaitTrue { shown(vm, first.id) != MessageStatus.DECLINED }

            assertFalse(peer in declinedFlag, "the RESTORED did not clear the declined flag")
            assertEquals(
                MessageStatus.SENT,
                shown(vm, first.id),
                "after the contact restored the request the first message still shows \"Not delivered: chat request declined\"",
            )
            assertEquals(
                MessageStatus.SENT.name,
                stored(store, first.id),
                "the stored row of the first message stays DECLINED, so it comes back after a restart",
            )
            assertEquals(
                MessageStatus.DECLINED,
                shown(vm, otherDeclined.id),
                "a RESTORED from one contact changed another contact's declined row",
            )
            assertEquals(MessageStatus.DECLINED.name, stored(store, otherDeclined.id))
            assertEquals(emptyList(), published.toList(), "undoing the mark must send nothing")
        }

    @Test
    fun `a plain rumor from the bound key puts the declined first message back to sent, on screen and in the store`() =
        runBlocking {
            val (store, prefs) = store()
            val vm = B1bRelay.chatViewModel(prefs.proxy)
            val first = relayRow("nmsg-$tag-first-b", peer)
            seed(store, vm, listOf(first))
            declineFirst(vm, store, prefs, first.id)

            NostrChatBridge.dispatch(B1bRelay.dm(key, "synthetic reply after the accept", "$tag-t1"), prefs.proxy)
            B1bRelay.awaitTrue { "$tag-t1" in store.seen }
            B1bRelay.awaitTrue { shown(vm, first.id) != MessageStatus.DECLINED }

            assertFalse(peer in declinedFlag, "a reply from the bound key did not clear the declined flag")
            assertEquals(
                MessageStatus.SENT,
                shown(vm, first.id),
                "a reply arrived from the contact but the first message still shows \"Not delivered: chat request declined\"",
            )
            assertEquals(
                MessageStatus.SENT.name,
                stored(store, first.id),
                "the stored row of the first message stays DECLINED after the contact's reply",
            )
        }

    @Test
    fun `a DECLINED alone still marks the row declined (guard)`() =
        runBlocking {
            val (store, prefs) = store()
            val vm = B1bRelay.chatViewModel(prefs.proxy)
            val first = relayRow("nmsg-$tag-first-c", peer)
            seed(store, vm, listOf(first))

            declineFirst(vm, store, prefs, first.id)

            assertEquals(MessageStatus.DECLINED, shown(vm, first.id))
        }
}
