package co.electriccoin.zcash.ui.screen.chat.viewmodel

import androidx.lifecycle.ViewModelStore
import co.electriccoin.zcash.ui.nostr.NostrChatBridge
import co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferences
import co.electriccoin.zcash.ui.screen.chat.model.ChatMessage
import co.electriccoin.zcash.ui.screen.chat.model.ChatSendCopy
import co.electriccoin.zcash.ui.screen.chat.model.ConversationMode
import co.electriccoin.zcash.ui.screen.chat.model.MessageStatus
import co.electriccoin.zcash.ui.screen.chat.model.SendMessageState
import kotlinx.coroutines.CompletableDeferred
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.util.concurrent.atomic.AtomicBoolean

/**
 * ZC-1114 (GPT second opinion on the ZC-1071 fix, 64554e785): three ways a screen still shows a row wrongly once no screen
 * of this process sends it any more. Display only: nothing here builds or pays a second time.
 *
 * (1) A relay send finishes under a new id: the sending screen A stores the row as `nmsg-<rumor id>`, removes the
 * `nostr-out-…` row it sent under, and releases the old id. A screen B that loaded the row while A was live is told of the
 * release, but remapReleasedRow finds no stored row under the old id and leaves B's bubble "sending" for good (until B is
 * recreated). Wanted: B no longer shows the old id in flight (it shows the row under its new id as sent, or drops it).
 * (2) A newly opened screen loads a stored row still in flight whose submit returned its transactions, with no live
 * sender: the loader maps it FAILED with "ZChat was closed" (the release path leaves such a row as shown: it went out).
 * Wanted: the loader shows it as the release path does (in flight, no "not sent" text), and Retry stays refused with
 * nothing built or paid.
 * (3) A release that lands between the loader's store read (and its live check) and its list assignment: the release
 * listener finds nothing on screen yet, then the loader writes the row "sending". Wanted: that screen ends with the
 * released row as not sent ("ZChat was closed", Retry), as a screen loading after the release shows it. Interleaved
 * deterministically: the release runs inside the loader's per-row read of the row's reactions, after its live check.
 *
 * Real ChatViewModel send / loader / Retry over the faked store and seams of PaymentSendHarness and RelayRumorFixture;
 * every key, id, text and address is synthetic, no amount leaves the fakes.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReleasedRowNeverStaysSendingTest {
    private val sendScopes = SendHarnessScopes()
    private val hook = RumorHook()

    private companion object {
        const val PEER = "usyntheticzc1114released0001"
        const val PEER_KEY = "synthetic-zc1114-relay-key"
        const val CHOSEN = 50_000L
        const val SETTLE_MS = 2_000L
    }

    @Before
    fun setUp() {
        LiveSenders.resetForTest()
        sendScopes.open()
        Dispatchers.setMain(UnconfinedTestDispatcher())
        NostrChatBridge.unregisterPublisher()
        RumorHook.unregister()
        NostrChatBridge.registerPublisher { _, _ -> NostrChatBridge.PublishResult(1, "plainid1114", 1) }
    }

    @After
    fun tearDown() {
        hook.hold?.complete(Unit)
        hook.hold = null
        NostrChatBridge.unregisterPublisher()
        RumorHook.unregister()
        sendScopes.close()
        Dispatchers.resetMain()
        LiveSenders.resetForTest()
    }

    // ── (1) a relay send that finishes under a new id ─────────────────────────────────────────────────────

    @Test
    fun `1 - a screen that loaded a live relay send does not keep its old id sending once the sender finishes under a new id`() =
        runBlocking {
            val store = CheckedStore(PaymentPrefs(PEER, mode = ConversationMode.OPEN, peerNostrPubkey = PEER_KEY))
            hook.store = { store.pending.values.toList() }
            val seam = PaymentSeam(PEER)
            val chat = relayScreen(store, seam)
            hook.register()
            val held = CompletableDeferred<Unit>()
            hook.hold = held
            chat.sendMessage(PEER, "synthetic zc1114 relay send")
            assertTrue("fixture: the publish was not reached", PaymentHarness.eventually(SETTLE_MS) { hook.calls.isNotEmpty() })
            val localId =
                store.pending.values
                    .single()
                    .id
            assertTrue("fixture: the relay row is not a nostr-out row", localId.startsWith("nostr-out-"))
            assertTrue("fixture: the relay send is not live", LiveSenders.isLive(localId))

            val list = relayScreen(store, seam)
            PaymentHarness.loadPendingMessagesFromPrefs(list)
            val before = shown(list, localId)
            assertTrue("fixture: the list does not show the live relay send in flight", before != null && before.isPending)

            held.complete(Unit)
            val call = hook.calls.single()
            val finalId = "nmsg-${hook.idOf(call.payload, call.key, call.second)}"
            assertTrue(
                "fixture: the sender did not store the row under its new id",
                PaymentHarness.eventually(SETTLE_MS) { store.pending.keys == setOf(finalId) },
            )
            assertTrue(
                "fixture: the sender did not release the old id",
                PaymentHarness.eventually(SETTLE_MS) { !LiveSenders.isLive(localId) },
            )
            assertEquals("fixture: the sender's own bubble", MessageStatus.SENT, shown(chat, finalId)?.status)

            PaymentHarness.eventually(SETTLE_MS) { shown(list, localId)?.isPending != true }
            val stale = shown(list, localId)
            assertFalse(
                "the list keeps the relay send under its old id as sending after the sender finished it under $finalId " +
                    "(status ${stale?.status}, pending ${stale?.isPending})",
                stale != null && stale.isPending,
            )
            assertFalse("the list says a relay send that went out was not sent", stale?.status == MessageStatus.FAILED)
            shown(list, finalId)?.let { assertEquals("the list shows the finished row under its new id", MessageStatus.SENT, it.status) }
            assertEquals("a relay send paid", 0, seam.submits.get())
        }

    // ── (2) a new screen loads a row whose submit returned its transactions ──────────────────────────────

    @Test
    fun `2 - a new screen shows a stored in-flight row with its returned transactions as sent, not closed, and refuses Retry`() =
        runBlocking {
            val prefs = PaymentPrefs(PEER)
            val seam = PaymentSeam(PEER)
            val chat = PaymentHarness.paymentViewModel(prefs, seam)
            assertTrue(chat.sendMessage(PEER, "synthetic zc1114 sent and confirming", CHOSEN))
            HandshakeHarness.awaitIdle(chat)
            val stored = prefs.pending.values.single()
            assertEquals("fixture: one payment", 1, seam.submits.get())
            assertTrue("fixture: the stored row is not in flight", stored.isPending)
            assertTrue("fixture: the stored row has no returned transactions", stored.submittedTxIds.isNotEmpty())
            assertFalse("fixture: the finished send is still live", LiveSenders.isLive(stored.id))

            val opened = PaymentHarness.paymentViewModel(prefs, seam)
            PaymentHarness.loadPendingMessagesFromPrefs(opened)
            val row = shown(opened, stored.id)!!
            assertNotEquals(
                "a new screen shows a send that went out (its transactions stored) as not sent (reason ${row.failureReason})",
                MessageStatus.FAILED,
                row.status,
            )
            assertTrue("a new screen drops the in-flight mark of a send that went out", row.isPending)
            assertNull("a new screen says a send that went out was closed before it went out", row.failureReason)

            assertFalse("Retry was accepted for a send that went out", opened.retryMessage(PEER, stored.id))
            HandshakeHarness.awaitIdle(opened)
            assertEquals("Retry built a transaction for a send that went out", 1, seam.proposalUris.size)
            assertEquals("Retry paid a send that went out again", 1, seam.submits.get())
            assertEquals("the stored row was changed", stored, prefs.pending.getValue(stored.id))
        }

    // ── (3) a release between the loader's read and its list assignment ──────────────────────────────────

    @Test
    fun `3 - a release that lands while the loader maps the row leaves that screen showing it not sent, with Retry`() =
        runBlocking {
            val prefs = PaymentPrefs(PEER)
            val seam = PaymentSeam(PEER)
            val chat = PaymentHarness.paymentViewModel(prefs, seam)
            // The chat is busy with an earlier send: this message waits in the chat's queue, stored as sending, live.
            PaymentHarness.setSendMessageState(chat, SendMessageState.Sending)
            assertTrue(chat.sendMessage(PEER, "synthetic zc1114 queued send", CHOSEN))
            val id =
                prefs.pending.values
                    .single()
                    .id
            assertTrue("fixture: the queued row is not live", LiveSenders.isLive(id))

            // The list's loader reads the store, checks the row live, then (still mapping it) reads its reactions: there the
            // chat is closed, which releases the row (onCleared) and tells the list, before the loader assigns its list.
            val released = AtomicBoolean(false)
            val list = PaymentHarness.paymentViewModel(prefs, seam)
            PaymentHarness.setField(
                list,
                "zchatPreferences",
                interleaved(prefs) { rowId ->
                    if (rowId == id && released.compareAndSet(false, true)) {
                        ViewModelStore().apply { put("chat", chat) }.clear()
                    }
                },
            )
            PaymentHarness.loadPendingMessagesFromPrefs(list)
            assertTrue("fixture: the release did not run inside the loader", released.get())
            assertFalse("fixture: the closed chat still holds the row live", LiveSenders.isLive(id))

            PaymentHarness.eventually(SETTLE_MS) { shown(list, id)?.status == MessageStatus.FAILED }
            val row = shown(list, id)!!
            assertEquals(
                "a release during the load leaves the row sending for good (status ${row.status}, pending ${row.isPending})",
                MessageStatus.FAILED,
                row.status,
            )
            assertFalse("a released row is still shown in flight", row.isPending)
            assertEquals("a released row does not say why it was not sent", ChatSendCopy.FAILED_APP_CLOSED, row.failureReason)
            assertEquals("fixture: nothing paid", 0, seam.submits.get())
        }

    // ── helpers ──────────────────────────────────────────────────────────────────────────────────────────

    private suspend fun relayScreen(
        store: CheckedStore,
        seam: PaymentSeam,
    ): ChatViewModel =
        PaymentHarness.paymentViewModel(store.inner, seam).also {
            PaymentHarness.setField(it, "zchatPreferences", store.proxy)
        }

    private fun shown(
        screen: ChatViewModel,
        id: String,
    ): ChatMessage? = PaymentHarness.pendingMessages(screen).firstOrNull { it.id == id }

    /** [prefs]' store, with [onReactionsRead] run (on the loader's thread) each time a row's reactions are read. */
    private fun interleaved(
        prefs: PaymentPrefs,
        onReactionsRead: (rowId: String) -> Unit,
    ): ZchatPreferences =
        Proxy.newProxyInstance(
            ZchatPreferences::class.java.classLoader,
            arrayOf(ZchatPreferences::class.java),
        ) { _, method, args ->
            if (method.name == "getNostrReactions") onReactionsRead(args!![0] as String)
            try {
                method.invoke(prefs.proxy, *(args ?: emptyArray()))
            } catch (e: InvocationTargetException) {
                throw e.targetException
            }
        } as ZchatPreferences
}
