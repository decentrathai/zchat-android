package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.nostr.NostrChatBridge
import co.electriccoin.zcash.ui.screen.chat.ChatDetail
import co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferences
import co.electriccoin.zcash.ui.screen.chat.model.ChatSendCopy
import co.electriccoin.zcash.ui.screen.chat.model.ConversationMode
import co.electriccoin.zcash.ui.screen.chat.model.MessageStatus
import co.electriccoin.zcash.ui.screen.chat.model.ZFILEMessage
import co.electriccoin.zcash.ui.screen.chat.model.ZFILEType
import co.electriccoin.zcash.ui.screen.chat.view.ChatResourceText
import co.electriccoin.zcash.ui.screen.chat.viewmodel.HandshakeHarness.awaitIdle
import co.electriccoin.zcash.ui.screen.chat.viewmodel.PaymentHarness.paymentViewModel
import co.electriccoin.zcash.ui.screen.chat.viewmodel.RelayKeyBinding.code
import co.electriccoin.zcash.ui.screen.chat.viewmodel.RelayKeyBinding.composeVm
import co.electriccoin.zcash.ui.screen.chat.viewmodel.RelayKeyBinding.ready
import co.electriccoin.zcash.ui.screen.chat.viewmodel.RelayKeyBinding.relayKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Collections

/**
 * ZC-421 (fix-all 2.13.0, train T6b; ZC-420-SPEC §14.4 Z1-Z11, §14.5, §14.7): a Retry after a restart makes the same
 * relay message again, not a second one.
 *
 * At HEAD a relay row `nmsg-<rumorId>` that only a public relay took is FAILED with Retry (ZC-393). In the same process the
 * Retry re-sends the kept wrap; after a restart nothing is kept and the Retry publishes the text as a NEW rumor (a new id,
 * a new second), so a contact who already has the first copy shows it twice. Wanted: the row keeps the second of its
 * rumor when a relay took it, and a Retry with nothing kept re-makes the SAME rumor (same payload bytes, key and second,
 * hence the same id; NostrChatBridge.publishRumorAt with the expected id) in a new wrap, within 24 hours and outside a
 * chat with a disappearing timer. A row no relay took gets no second, and its Retry is one new message at the current
 * time, as today. With no rumor publisher registered the path is exactly today's.
 *
 * Real ChatViewModel send and Retry paths over [PaymentPrefs] (strict) behind [CheckedStore]; a fake plain publisher, a
 * fake kept-resender and a [RumorHook] registered by name. A "restart" is a new view model over the same store with
 * nothing kept. Nothing touches the chain (0 proposals). Every key, id and address is synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RetryAfterRestartRemakesTheSameRumorTest {
    private val sendScopes = SendHarnessScopes()
    private val plain: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private val resendCalls: MutableList<Pair<String, String>> = Collections.synchronizedList(mutableListOf())
    private val hook = RumorHook()
    private val composeVms = mutableListOf<ZchatComposeVM>()

    @Volatile private var plainAcks = 1

    @Volatile private var plainReadable = 1

    @Volatile private var resendAnswer: NostrChatBridge.PublishResult? = null

    private companion object {
        const val PEER = "usyntheticzc421restart000001"
        const val PEER_KEY = "synthetic-zc421-relay-key-restart"
        const val TEXT = "synthetic zc421 hello after a restart"
        const val AMOUNT = 25_000_000L
        const val REASON = "synthetic zc421 rent"
        const val DAY_SEC = 24L * 60 * 60
        val CVM = "ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/chat/viewmodel/ChatViewModel.kt"
    }

    @Before
    fun setUp() {
        sendScopes.open()
        Dispatchers.setMain(UnconfinedTestDispatcher())
        NostrChatBridge.unregisterPublisher()
        NostrChatBridge.unregisterKeptResender()
        RumorHook.unregister()
        registerPlain()
        NostrChatBridge.registerKeptResender { id, key ->
            resendCalls += id to key
            resendAnswer
        }
    }

    @After
    fun tearDown() {
        NostrChatBridge.unregisterPublisher()
        NostrChatBridge.unregisterKeptResender()
        RumorHook.unregister()
        composeVms.forEach(RelayKeyBinding::close)
        sendScopes.close()
        Dispatchers.resetMain()
    }

    private fun registerPlain() {
        NostrChatBridge.registerPublisher { payload, _ ->
            plain += payload
            NostrChatBridge.PublishResult(plainAcks, "plainid${plain.size}", plainReadable)
        }
    }

    private fun store(): CheckedStore =
        CheckedStore(PaymentPrefs(PEER, mode = ConversationMode.OPEN, peerNostrPubkey = PEER_KEY)).also { s ->
            hook.store = { s.pending.values.toList() }
        }

    private suspend fun screen(
        store: CheckedStore,
        seam: PaymentSeam,
    ): ChatViewModel =
        paymentViewModel(store.inner, seam).also { PaymentHarness.setField(it, "zchatPreferences", store.proxy) }

    /** A restart: a new view model over the same store, which loads the rows as a fresh screen does. */
    private suspend fun restart(
        store: CheckedStore,
        seam: PaymentSeam,
    ): ChatViewModel = screen(store, seam).also { PaymentHarness.loadPendingMessagesFromPrefs(it) }

    /** A first send of [TEXT] that only a public relay took: the stored row and its rumor's first call. */
    private suspend fun firstSendOnlyPublic(
        vm: ChatViewModel,
        store: CheckedStore,
    ): Pair<ZchatPreferences.PendingMessageData, RumorHook.Call> {
        hook.register()
        hook.acks = 0
        hook.readable = 1
        vm.sendMessage(PEER, TEXT)
        awaitIdle(vm)
        val call = hook.calls.singleOrNull() ?: throw AssertionError("the first send made ${hook.calls.size} rumor publishes")
        val row = store.pending.values.single()
        return row to call
    }

    private fun idOf(call: RumorHook.Call): String = hook.idOf(call.payload, call.key, call.second)

    // ── (a), (b): the first send keeps its second; a Retry after a restart re-makes the same rumor ───────────────

    @Test
    fun `a first send only a public relay took keeps its second, and a Retry after a restart re-makes the same rumor`() =
        runBlocking {
            val store = store()
            val seam = PaymentSeam(PEER)
            val vm = screen(store, seam)
            val (row, first) = firstSendOnlyPublic(vm, store)

            val id1 = idOf(first)
            assertEquals(PEER_KEY, first.key)
            assertEquals("the rumor's second is not the row's time", row.timestampMillis / 1000, first.second)
            assertEquals("nmsg-$id1", row.id)
            assertEquals(MessageStatus.FAILED.name, row.status)
            assertEquals(ChatSendCopy.FAILED_ONLY_PUBLIC_RELAY, row.failureReason)
            assertEquals("the row does not keep its rumor's second", first.second, RumorPair.secOf(row))
            assertTrue("the plain publisher was used for a first send", plain.isEmpty())

            hook.acks = 1
            resendAnswer = null
            val after = restart(store, seam)
            after.retryMessage(PEER, row.id)
            awaitIdle(after)

            assertEquals("the kept wrap was not asked first", listOf(id1 to PEER_KEY), resendCalls.toList())
            assertEquals("a Retry made ${hook.calls.size - 1} rumor publishes", 2, hook.calls.size)
            val again = hook.calls[1]
            assertEquals("the re-made rumor's bytes differ", first.payload, again.payload)
            assertEquals(PEER_KEY, again.key)
            assertEquals("the re-made rumor has another second", first.second, again.second)
            assertEquals("the re-make was not checked against the row's id", id1, again.expected)
            assertTrue("the plain publisher made a new message: $plain", plain.isEmpty())
            assertEquals("a second row was written", setOf("nmsg-$id1"), store.pending.keys)
            assertEquals(MessageStatus.SENT.name, store.pending["nmsg-$id1"]?.status)
            assertEquals(0, seam.proposalUris.size)
        }

    // ── (c): the kept wrap still comes first ───────────────────────────────────────────────────────────────────

    @Test
    fun `in the same process a kept wrap is re-sent and nothing is made`() =
        runBlocking {
            val store = store()
            val seam = PaymentSeam(PEER)
            val vm = screen(store, seam)
            val (row, first) = firstSendOnlyPublic(vm, store)

            resendAnswer = NostrChatBridge.PublishResult(1, idOf(first), 1)
            vm.retryMessage(PEER, row.id)
            awaitIdle(vm)

            assertEquals(1, resendCalls.size)
            assertEquals("a rumor was published beside the kept wrap", 1, hook.calls.size)
            assertTrue(plain.isEmpty())
            assertEquals(MessageStatus.SENT.name, store.pending[row.id]?.status)
            assertEquals("the timer was read on the kept-wrap path", 0, store.ttlReads.get())
        }

    // ── (d): a re-make again only public stays FAILED and keeps everything ─────────────────────────────────────

    @Test
    fun `a re-make that again only a public relay took stays FAILED under its id, and the next Retry asks the kept wrap first`() =
        runBlocking {
            val store = store()
            val seam = PaymentSeam(PEER)
            val (row, first) = firstSendOnlyPublic(screen(store, seam), store)

            val after = restart(store, seam)
            after.retryMessage(PEER, row.id)
            awaitIdle(after)

            val kept = store.pending[row.id]
            assertNotNull("the row lost its id (rows: ${store.pending.keys})", kept)
            assertEquals(MessageStatus.FAILED.name, kept!!.status)
            assertEquals(ChatSendCopy.FAILED_ONLY_PUBLIC_RELAY, kept.failureReason)
            assertEquals(first.second, RumorPair.secOf(kept))
            assertEquals(idOf(first), hook.calls[1].expected)

            after.retryMessage(PEER, row.id)
            awaitIdle(after)
            assertEquals("the next Retry did not ask the kept wrap first", 2, resendCalls.size)
        }

    // ── (e), (f), (g), (h): outside the limits a Retry is one new message, as today ────────────────────────────

    @Test
    fun `a rumor id that differs now is one new message, nothing published for the old id`() =
        runBlocking {
            val store = store()
            val seam = PaymentSeam(PEER)
            val (row, first) = firstSendOnlyPublic(screen(store, seam), store)
            hook.acks = 1
            hook.salt = "rotated"

            val after = restart(store, seam)
            after.retryMessage(PEER, row.id)
            awaitIdle(after)

            assertOneNewMessage(store, row, first)
        }

    @Test
    fun `a second older than 24 hours or in the future is one new message`() =
        runBlocking {
            for (shift in listOf(-(DAY_SEC + 3_600), 3_600L)) {
                hook.calls.clear()
                val store = store()
                val seam = PaymentSeam(PEER)
                val (row, first) = firstSendOnlyPublic(screen(store, seam), store)
                hook.acks = 1
                store.pending[row.id] = RumorPair.with(row, first.second + shift, idOf(first))
                delay(RETRY_IN_A_LATER_SECOND_MS)

                val after = restart(store, seam)
                after.retryMessage(PEER, row.id)
                awaitIdle(after)

                val made = hook.calls.drop(1)
                assertEquals("shift $shift: not exactly one new message (${made.map { it.second }})", 1, made.size)
                assertNotEquals("shift $shift: the old second was re-used", first.second + shift, made.single().second)
                assertNull("shift $shift: the old row stayed", store.pending[row.id])
            }
        }

    @Test
    fun `a chat with a disappearing timer gets one new message, and the timer is read only there`() =
        runBlocking {
            val store = store()
            val seam = PaymentSeam(PEER)
            val (row, first) = firstSendOnlyPublic(screen(store, seam), store)
            assertEquals("the first send read the timer", 0, store.ttlReads.get())
            hook.acks = 1
            store.ttl = ZchatPreferences.DisappearingTtl(3_600L, 0L)
            delay(RETRY_IN_A_LATER_SECOND_MS)

            val after = restart(store, seam)
            after.retryMessage(PEER, row.id)
            awaitIdle(after)

            assertTrue("the timer was not read before a re-make", store.ttlReads.get() > 0)
            assertOneNewMessage(store, row, first)
        }

    @Test
    fun `a row with no second gets one new message`() =
        runBlocking {
            val store = store()
            val seam = PaymentSeam(PEER)
            val (row, first) = firstSendOnlyPublic(screen(store, seam), store)
            hook.acks = 1
            store.pending[row.id] = RumorPair.with(row, null, null)
            delay(RETRY_IN_A_LATER_SECOND_MS)

            val after = restart(store, seam)
            after.retryMessage(PEER, row.id)
            awaitIdle(after)

            assertOneNewMessage(store, row, first)
        }

    private fun assertOneNewMessage(
        store: CheckedStore,
        row: ZchatPreferences.PendingMessageData,
        first: RumorHook.Call,
    ) {
        val made = hook.calls.drop(1).filter { it.expected == null || it.expected == idOf(it) }
        assertEquals("not exactly one new message: ${made.map { it.second }}", 1, made.size)
        val newId = idOf(made.single())
        assertNotEquals("the new message has the old id", row.id, "nmsg-$newId")
        assertTrue("the new message is older than the first try", made.single().second >= first.second)
        assertTrue("the plain publisher was used: $plain", plain.isEmpty())
        assertNull("the old row stayed beside the new one", store.pending[row.id])
        assertEquals(MessageStatus.SENT.name, store.pending["nmsg-$newId"]?.status)
    }

    // ── (i): no rumor publisher registered: today's path exactly ──────────────────────────────────────────────

    @Test
    fun `with no rumor publisher the first send and the Retry run today's path`() =
        runBlocking {
            val store = store()
            val seam = PaymentSeam(PEER)
            val vm = screen(store, seam)
            plainAcks = 0
            plainReadable = 1
            vm.sendMessage(PEER, TEXT)
            awaitIdle(vm)
            val row = store.pending.values.single()
            assertNull("a second was kept with no rumor publisher", RumorPair.secOf(row))

            plainAcks = 1
            val after = restart(store, seam)
            after.retryMessage(PEER, row.id)
            awaitIdle(after)

            assertEquals(2, plain.size)
            assertEquals(MessageStatus.SENT.name, store.pending["nmsg-plainid2"]?.status)
            assertNull(RumorPair.secOf(store.pending["nmsg-plainid2"]))
            assertEquals(
                "unexpected store calls: ${store.inner.unexpectedCalls}",
                emptyList<String>(),
                store.inner.unexpectedCalls.toList(),
            )
            assertEquals("the timer was read with no rumor publisher", 0, store.ttlReads.get())
        }

    // ── (j): a reply row and a file-link row re-make the same bytes ───────────────────────────────────────────

    @Test
    fun `a reply row and a file-link row re-make the same bytes`() =
        runBlocking {
            val link =
                ZFILEMessage(
                    hash = "0123456789abcdef0123456789abcdef",
                    type = ZFILEType.JPEG,
                    size = 4_096L,
                    url = "https://nostr.download/synthetic-zc421.jpg",
                    wrappedKey = "c3ludGhldGljLXpjNDIxLWtleQ",
                    blurhash = "",
                ).serialize()
            for (send in listOf<suspend (ChatViewModel) -> Unit>(
                { it.sendReply(PEER, "synthetic zc421 reply", "nmsg-synthetictarget", "synthetic target") },
                { it.sendMessage(PEER, link) },
            )) {
                hook.calls.clear()
                val store = store()
                val seam = PaymentSeam(PEER)
                val vm = screen(store, seam)
                hook.register()
                hook.acks = 0
                hook.readable = 1
                send(vm)
                awaitIdle(vm)
                val row = store.pending.values.single()

                hook.acks = 1
                val after = restart(store, seam)
                after.retryMessage(PEER, row.id)
                awaitIdle(after)

                assertEquals("the Retry made ${hook.calls.size - 1} rumor publishes", 2, hook.calls.size)
                assertEquals("the re-made bytes differ", hook.calls[0].payload, hook.calls[1].payload)
                assertEquals(hook.calls[0].second, hook.calls[1].second)
                assertEquals(setOf(row.id), store.pending.keys)
            }
        }

    // ── (k): a payment request through (a) and (b) ───────────────────────────────────────────────────────────

    @Test
    fun `a payment request only a public relay took is re-made after a restart, never on-chain`() =
        runBlocking {
            val store = store()
            val seam = PaymentSeam(PEER)
            val vm = screen(store, seam)
            hook.register()
            hook.acks = 0
            hook.readable = 1
            vm.sendPaymentRequest(PEER, AMOUNT, REASON)
            awaitIdle(vm)
            val first = hook.calls.single()
            val row = store.pending.values.single()
            assertEquals("nmsg-${idOf(first)}", row.id)

            hook.acks = 1
            val after = restart(store, seam)
            after.retryMessage(PEER, row.id)
            awaitIdle(after)

            assertEquals(2, hook.calls.size)
            assertEquals(first.payload, hook.calls[1].payload)
            assertEquals(first.second, hook.calls[1].second)
            assertEquals(idOf(first), hook.calls[1].expected)
            assertTrue(plain.isEmpty())
            assertEquals(setOf(row.id), store.pending.keys)
            assertEquals(MessageStatus.SENT.name, store.pending[row.id]?.status)
            assertEquals(AMOUNT, store.pending[row.id]?.paymentRequestAmountZatoshi)
            assertEquals("a request in a free chat went on-chain", 0, seam.proposalUris.size)
        }

    // ── (l), (q): a Retry that cannot publish keeps the id and the second ─────────────────────────────────────

    @Test
    fun `a Retry while the publisher is not ready keeps the id and the second, and the next Retry re-makes`() =
        runBlocking {
            val store = store()
            val seam = PaymentSeam(PEER)
            val (row, first) = firstSendOnlyPublic(screen(store, seam), store)
            NostrChatBridge.unregisterPublisher()
            NostrChatBridge.unregisterKeptResender()
            RumorHook.unregister()

            val after = restart(store, seam)
            after.retryMessage(PEER, row.id)
            awaitIdle(after)
            val kept = store.pending[row.id]
            assertNotNull("the row lost its id (rows: ${store.pending.keys})", kept)
            assertEquals(first.second, RumorPair.secOf(kept))

            registerPlain()
            hook.register()
            hook.acks = 1
            after.retryMessage(PEER, row.id)
            awaitIdle(after)
            assertEquals(idOf(first), hook.calls.last().expected)
            assertEquals(first.second, hook.calls.last().second)
            assertTrue(plain.isEmpty())
        }

    @Test
    fun `a Retry with the contact's relay key missing keeps the id and the second, and with the key back it re-makes`() =
        runBlocking {
            val store = store()
            val seam = PaymentSeam(PEER)
            val (row, first) = firstSendOnlyPublic(screen(store, seam), store)
            store.inner.peerNostrPubkey = null

            val after = restart(store, seam)
            after.retryMessage(PEER, row.id)
            awaitIdle(after)
            val kept = store.pending[row.id]
            assertNotNull("the row lost its id (rows: ${store.pending.keys})", kept)
            assertEquals(MessageStatus.FAILED.name, kept!!.status)
            assertEquals(first.second, RumorPair.secOf(kept))
            assertEquals(1, hook.calls.size)

            store.inner.peerNostrPubkey = PEER_KEY
            hook.acks = 1
            after.retryMessage(PEER, row.id)
            awaitIdle(after)
            assertEquals(idOf(first), hook.calls.last().expected)
            assertEquals(MessageStatus.SENT.name, store.pending[row.id]?.status)
        }

    // ── (n): the helper names no paying function ──────────────────────────────────────────────────────────────

    @Test
    fun `publishRelayRow exists and names no paying function`() {
        val source = ChatResourceText.source(CVM)
        val start = source.indexOf("fun publishRelayRow(")
        assertTrue("ChatViewModel has no publishRelayRow (ZC-421)", start >= 0)
        val next = Regex("""\n    (?:(?:private|internal|public|override|suspend)\s+)*fun\s""").find(source, start + 1)
        val body = source.substring(start, next?.range?.first ?: source.length)
        listOf(
            "createChunkedMessageProposal",
            "doSendMessage",
            "askBeforePaying",
            "recordSubmitStarted",
            "submitTransaction",
            "Proposal(",
            "readableAcks",
        ).forEach { assertTrue("publishRelayRow names $it", it !in body) }
    }

    // ── (o), (p): only a row a relay took keeps its second ────────────────────────────────────────────────────

    @Test
    fun `a first send no relay took keeps no second, and its Retry is one new message, in the same process and after a restart`() =
        runBlocking {
            for (restarted in listOf(false, true)) {
                hook.calls.clear()
                val store = store()
                val seam = PaymentSeam(PEER)
                val vm = screen(store, seam)
                hook.register()
                hook.acks = 0
                hook.readable = 0
                vm.sendMessage(PEER, TEXT)
                awaitIdle(vm)
                val first = hook.calls.single()
                val row = store.pending.values.single()
                assertEquals("nmsg-${idOf(first)}", row.id)
                assertEquals(ChatSendCopy.FAILED_NO_RELAY, row.failureReason)
                assertNull("restarted=$restarted: a row no relay took kept a second", RumorPair.secOf(row))

                hook.acks = 1
                hook.readable = 1
                delay(RETRY_IN_A_LATER_SECOND_MS)
                val screen = if (restarted) restart(store, seam) else vm
                screen.retryMessage(PEER, row.id)
                awaitIdle(screen)

                assertOneNewMessage(store, row, first)
                val made = hook.calls.drop(1).single()
                assertTrue("restarted=$restarted: the new message is not at the current second", made.second > first.second)
                assertEquals(made.second, RumorPair.secOf(store.pending["nmsg-${idOf(made)}"]))
            }
        }

    @Test
    fun `a first send a shared relay took keeps its second`() =
        runBlocking {
            val store = store()
            val vm = screen(store, PaymentSeam(PEER))
            hook.register()
            hook.acks = 1
            hook.readable = 1
            vm.sendMessage(PEER, TEXT)
            awaitIdle(vm)
            val row = store.pending.values.single()
            assertEquals(MessageStatus.SENT.name, row.status)
            assertEquals(hook.calls.single().second, RumorPair.secOf(row))
        }

    // ── (r): the hook goes away between the check and the call ────────────────────────────────────────────────

    @Test
    fun `a rumor hook that goes away sends exactly one new message through the plain publisher and keeps no second`() =
        runBlocking {
            val store = store()
            val seam = PaymentSeam(PEER)
            val (row, _) = firstSendOnlyPublic(screen(store, seam), store)
            hook.gone = true

            val after = restart(store, seam)
            after.retryMessage(PEER, row.id)
            awaitIdle(after)

            assertEquals("not exactly one plain publish", 1, plain.size)
            assertNull(store.pending[row.id])
            val sent = store.pending["nmsg-plainid1"]
            assertNotNull("the row was not re-keyed (rows: ${store.pending.keys})", sent)
            assertNull("a second was kept for the plain publisher's message", RumorPair.secOf(sent))
        }

    // ── (m), (s): the compose screen's first Open message ─────────────────────────────────────────────────────

    @Test
    fun `the compose screen's first Open message keeps its second, and retryMessage after a restart re-makes it`() =
        runBlocking {
            val peer = SyntheticUnifiedAddress.mainnet(4_921)
            val key = relayKey(421)
            val composeStore = RelayKeyStore()
            val router = RelayKeyBinding.RecordingRouter()
            hook.register()
            hook.acks = 0
            hook.readable = 1
            hook.store = { composeStore.rows.values.toList() }
            val compose = composeVm(composeStore, navigationRouter = router).also { composeVms += it }
            compose.ready().onRecipientChange(code(peer, key))
            compose.ready().onMessageChange("synthetic zc421 first open hello")
            assertTrue("fixture: the send is the free Open send", compose.ready().isFreeOpenSend)
            compose.ready().onSendClick()
            withTimeoutOrNull(10_000) { while (router.replaced.isEmpty()) delay(10) }
            assertEquals(listOf<Any>(ChatDetail(peer)), router.replaced.toList())

            val first = hook.calls.single()
            val row = composeStore.rows.values.single()
            assertEquals("nmsg-${idOf(first)}", row.id)
            assertEquals("the compose row does not keep its second", first.second, RumorPair.secOf(row))

            // A restart: the chat screen over the same rows, with our address and conversation id as the compose screen's.
            hook.acks = 1
            val store =
                CheckedStore(PaymentPrefs(peer, mode = ConversationMode.OPEN, peerNostrPubkey = key), convId = "SYNTHM1C")
            store.pending[row.id] = row
            hook.store = { store.pending.values.toList() }
            val seam = PaymentSeam(peer)
            val after = screen(store, seam)
            PaymentHarness.setField(after, "_currentUserAddress", MutableStateFlow<String?>(RelayKeyBinding.SELF))
            PaymentHarness.loadPendingMessagesFromPrefs(after)
            after.retryMessage(peer, row.id)
            awaitIdle(after)

            assertEquals("a Retry made ${hook.calls.size - 1} rumor publishes", 2, hook.calls.size)
            assertEquals(first.second, hook.calls[1].second)
            assertEquals(idOf(first), hook.calls[1].expected)
            assertTrue(plain.isEmpty())
            assertEquals(setOf(row.id), store.pending.keys)
            assertEquals(0, seam.proposalUris.size)
        }
}
