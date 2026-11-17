package co.electriccoin.zcash.ui.screen.chat.viewmodel

import androidx.lifecycle.viewModelScope
import co.electriccoin.zcash.ui.nostr.NostrChatBridge
import co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferences
import co.electriccoin.zcash.ui.screen.chat.model.ChatSendCopy
import co.electriccoin.zcash.ui.screen.chat.model.ConversationMode
import co.electriccoin.zcash.ui.screen.chat.model.MessageStatus
import co.electriccoin.zcash.ui.screen.chat.model.SendMessageState
import co.electriccoin.zcash.ui.screen.chat.model.SubmitAttemptProof
import co.electriccoin.zcash.ui.screen.chat.model.ZFILEMessage
import co.electriccoin.zcash.ui.screen.chat.model.ZFILEType
import co.electriccoin.zcash.ui.screen.chat.viewmodel.HandshakeHarness.awaitIdle
import co.electriccoin.zcash.ui.screen.chat.viewmodel.PaymentHarness.paymentViewModel
import co.electriccoin.zcash.ui.screen.chat.viewmodel.RelayKeyBinding.code
import co.electriccoin.zcash.ui.screen.chat.viewmodel.RelayKeyBinding.composeVm
import co.electriccoin.zcash.ui.screen.chat.viewmodel.RelayKeyBinding.ready
import co.electriccoin.zcash.ui.screen.chat.viewmodel.RelayKeyBinding.relayKey
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Collections

/**
 * ZC-421 (fix-all 2.13.0, train T6b; ZC-420-SPEC §14.4 Z13, §14.5 "the two Retry guards", §14.7, design check 6 Oct): a
 * relay row the closed app left in flight is sent again as the same rumor.
 *
 * At HEAD a relay row (`nostr-out-…` or `nmsg-…`) is written, pending, before its publish, with nothing about the rumor it
 * is about to publish. When the app is closed (or the view model cleared) before the answer is written, the row stays
 * pending: the next screen shows it FAILED with "Not sent — ZChat was closed before this message went out. Tap Retry to
 * send it.", and retryMessage refuses it with SubmitAttemptProof.MAY_HAVE_BEEN_SENT, a text about paying twice for a
 * message that costs nothing: the row is stuck. Wanted: before a rumor is handed to the relay layer the stored row holds
 * its second and id (relayRumorAtSec, relayRumorId), confirmed by the checked write; the Retry of a row left pending goes
 * the way of a row a relay took (the kept wrap first, else the same rumor within 24 h and outside a timer chat, else one
 * new message); the may-have-been-sent text is never shown for a relay row. Rows of on-chain sends keep their refusal.
 *
 * "The app closed" = the fake rumor publisher never answers and the view model is left; a new view model over the same
 * store is the next start. Real ChatViewModel over [PaymentPrefs] behind [CheckedStore]; every key, id and address is
 * synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RelayRowLeftInFlightIsSentAgainAsTheSameRumorTest {
    private val sendScopes = SendHarnessScopes()
    private val plain: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private val resendCalls: MutableList<Pair<String, String>> = Collections.synchronizedList(mutableListOf())
    private val hook = RumorHook()
    private val composeVms = mutableListOf<ZchatComposeVM>()

    @Volatile private var resendAnswer: NostrChatBridge.PublishResult? = null

    private companion object {
        const val PEER = "usyntheticzc421leftrow00001"
        const val PEER_KEY = "synthetic-zc421-relay-key-left"
        const val TEXT = "synthetic zc421 left in flight"
        const val AMOUNT = 12_000_000L
        const val REASON = "synthetic zc421 tickets"
        const val DAY_SEC = 24L * 60 * 60
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
        hook.hold = null
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
            NostrChatBridge.PublishResult(1, "plainid${plain.size}", 1)
        }
    }

    private fun store(mode: ConversationMode = ConversationMode.OPEN): CheckedStore =
        CheckedStore(PaymentPrefs(PEER, mode = mode, peerNostrPubkey = PEER_KEY)).also { s ->
            hook.store = { s.pending.values.toList() }
        }

    private suspend fun screen(
        store: CheckedStore,
        seam: PaymentSeam,
    ): ChatViewModel = paymentViewModel(store.inner, seam).also { PaymentHarness.setField(it, "zchatPreferences", store.proxy) }

    private suspend fun restart(
        store: CheckedStore,
        seam: PaymentSeam,
    ): ChatViewModel {
        // ZC-1071: a restart is a new process, whose live-sender registry is empty (the left publish is only held here).
        LiveSenders.resetForTest()
        return screen(store, seam).also { PaymentHarness.loadPendingMessagesFromPrefs(it) }
    }

    private fun idOf(call: RumorHook.Call): String = hook.idOf(call.payload, call.key, call.second)

    /** Sends with [send] and closes the app while the rumor's publish waits; the row the closed app left and its call. */
    private suspend fun leftInFlight(
        store: CheckedStore,
        seam: PaymentSeam,
        send: suspend (ChatViewModel) -> Unit = { it.sendMessage(PEER, TEXT) },
    ): Pair<ZchatPreferences.PendingMessageData, RumorHook.Call> {
        val vm = screen(store, seam)
        hook.register()
        hook.hold = CompletableDeferred()
        send(vm)
        assertTrue("fixture: the publish was not reached", PaymentHarness.eventually { hook.calls.isNotEmpty() })
        val call = hook.calls.single()
        val left = store.pending.values.single()
        hook.hold = null
        hook.acks = 1
        hook.readable = 1
        return left to call
    }

    private fun assertNeverMayHaveBeenSent(vm: ChatViewModel) {
        val state = PaymentHarness.sendMessageState(vm)
        assertFalse(
            "the may-have-been-sent text was shown for a relay row",
            (state as? SendMessageState.Error)?.message == SubmitAttemptProof.MAY_HAVE_BEEN_SENT,
        )
        assertFalse("a price question for a relay row", state is SendMessageState.NeedsPaidActionConfirmation)
    }

    // ── (a), (b) ─────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `a row the closed app left, before or after a relay took it, is sent again as the same rumor`() =
        runBlocking {
            for (taken in listOf(false, true)) {
                hook.calls.clear()
                val store = store()
                val seam = PaymentSeam(PEER)
                hook.acks = if (taken) 1 else 0
                val (left, first) = leftInFlight(store, seam)
                assertTrue("taken=$taken: the left row is not pending", left.isPending)
                assertTrue(left.id.startsWith("nostr-out-"))
                assertEquals("taken=$taken: no second on the left row", first.second, RumorPair.secOf(left))
                assertEquals("taken=$taken: no id on the left row", idOf(first), RumorPair.idOf(left))

                val after = restart(store, seam)
                val shown = PaymentHarness.pendingMessages(after).single { it.id == left.id }
                assertEquals(MessageStatus.FAILED, shown.status)
                assertEquals(ChatSendCopy.FAILED_APP_CLOSED, shown.failureReason)

                after.retryMessage(PEER, left.id)
                awaitIdle(after)

                assertNeverMayHaveBeenSent(after)
                assertEquals("taken=$taken: not exactly one rumor publish", 1, hook.calls.size - 1)
                val again = hook.calls[1]
                assertEquals(first.payload, again.payload)
                assertEquals(PEER_KEY, again.key)
                assertEquals(first.second, again.second)
                assertEquals(idOf(first), again.expected)
                assertTrue(plain.isEmpty())
                assertEquals(0, seam.proposalUris.size)
                assertEquals("taken=$taken", setOf("nmsg-${idOf(first)}"), store.pending.keys)
                assertEquals(MessageStatus.SENT.name, store.pending["nmsg-${idOf(first)}"]?.status)
            }
        }

    // ── (c) ──────────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `the pair is on disk before the publish, for a first send and for a Retry that makes a new message`() =
        runBlocking {
            val store = store()
            val seam = PaymentSeam(PEER)
            val vm = screen(store, seam)
            hook.register()
            hook.acks = 0
            hook.readable = 0
            vm.sendMessage(PEER, TEXT)
            awaitIdle(vm)
            val first = hook.calls.single()
            val atFirst = first.storedAtCall.singleOrNull()
            assertNotNull("no row was stored before the first publish", atFirst)
            assertEquals(first.second, RumorPair.secOf(atFirst))
            assertEquals(idOf(first), RumorPair.idOf(atFirst))
            assertEquals(idOf(first), first.expected)
            val pendingWithout = store.writes.filter { it.id == atFirst!!.id && it.isPending && RumorPair.idOf(it) == null }
            assertTrue("a pending write of the first send without its pair", pendingWithout.isEmpty())

            // No relay took it: its Retry is a new message, whose pair is on the stored row before its publish.
            val row = store.pending.values.single()
            hook.acks = 1
            hook.readable = 1
            delay(RETRY_IN_A_LATER_SECOND_MS)
            vm.retryMessage(PEER, row.id)
            awaitIdle(vm)
            val made = hook.calls.last()
            val atRetry = made.storedAtCall.singleOrNull { it.id == row.id }
            assertNotNull("the retried row was not in the store at its publish", atRetry)
            assertEquals(made.second, RumorPair.secOf(atRetry))
            assertEquals(idOf(made), RumorPair.idOf(atRetry))
            assertEquals(idOf(made), made.expected)
        }

    @Test
    fun `when the checked write is not confirmed nothing is published and the bubble fails with the no-relay text`() =
        runBlocking {
            val store = store()
            val vm = screen(store, PaymentSeam(PEER))
            hook.register()
            store.refuseCommits.set(1)

            vm.sendMessage(PEER, TEXT)
            awaitIdle(vm)

            assertEquals("fixture: the checked write was not asked", 0, store.refuseCommits.get())
            assertTrue("a rumor was published after a refused write", hook.calls.isEmpty())
            assertTrue(plain.isEmpty())
            val shown = PaymentHarness.pendingMessages(vm).single()
            assertEquals(MessageStatus.FAILED, shown.status)
            assertEquals(ChatSendCopy.FAILED_NO_RELAY, shown.failureReason)
        }

    // ── (d) ──────────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `a publish that answered no relay took it leaves no pair, and its Retry is one new message`() =
        runBlocking {
            val store = store()
            val seam = PaymentSeam(PEER)
            val vm = screen(store, seam)
            hook.register()
            hook.acks = 0
            hook.readable = 0
            vm.sendMessage(PEER, TEXT)
            awaitIdle(vm)
            val row = store.pending.values.single()
            assertNull(RumorPair.secOf(row))
            assertNull(RumorPair.idOf(row))

            hook.acks = 1
            hook.readable = 1
            delay(RETRY_IN_A_LATER_SECOND_MS)
            val after = restart(store, seam)
            after.retryMessage(PEER, row.id)
            awaitIdle(after)
            assertEquals(2, hook.calls.size)
            assertNotEquals(hook.calls[0].second, hook.calls[1].second)
        }

    // ── (e) ──────────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `a left row outside the limits is one new message, never the may-have-been-sent text`() =
        runBlocking {
            for (case in listOf("older-24h", "timer", "id-differs")) {
                hook.calls.clear()
                hook.salt = ""
                val store = store()
                val seam = PaymentSeam(PEER)
                val (left, first) = leftInFlight(store, seam)
                when (case) {
                    "older-24h" -> {
                        val old = first.second - DAY_SEC - 60
                        store.pending[left.id] = RumorPair.with(left, old, hook.idOf(first.payload, first.key, old))
                    }
                    "timer" -> store.ttl = ZchatPreferences.DisappearingTtl(3_600L, 0L)
                    else -> hook.salt = "rotated"
                }
                delay(RETRY_IN_A_LATER_SECOND_MS)

                val after = restart(store, seam)
                after.retryMessage(PEER, left.id)
                awaitIdle(after)

                assertNeverMayHaveBeenSent(after)
                val made = hook.calls.drop(1).filter { it.expected == idOf(it) }
                assertEquals("$case: not exactly one new message", 1, made.size)
                assertTrue("$case: the new message is not at the current second", made.single().second > first.second)
                assertTrue(plain.isEmpty())
                assertEquals(0, seam.proposalUris.size)
            }
        }

    // ── (f) ──────────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `a Retry of a left row that cannot publish keeps its pair, and the next Retry sends the same rumor`() =
        runBlocking {
            for (case in listOf("not-ready", "no-key")) {
                hook.calls.clear()
                val store = store()
                val seam = PaymentSeam(PEER)
                val (left, first) = leftInFlight(store, seam)
                if (case == "not-ready") {
                    NostrChatBridge.unregisterPublisher()
                    RumorHook.unregister()
                } else {
                    store.inner.peerNostrPubkey = null
                }

                val after = restart(store, seam)
                after.retryMessage(PEER, left.id)
                awaitIdle(after)
                assertNeverMayHaveBeenSent(after)
                val kept = store.pending[left.id]
                assertNotNull("$case: the row is gone (rows: ${store.pending.keys})", kept)
                assertEquals(MessageStatus.FAILED.name, kept!!.status)
                assertEquals("$case", first.second, RumorPair.secOf(kept))
                assertEquals("$case", idOf(first), RumorPair.idOf(kept))

                registerPlain()
                hook.register()
                store.inner.peerNostrPubkey = PEER_KEY
                after.retryMessage(PEER, left.id)
                awaitIdle(after)
                assertEquals("$case", first.second, hook.calls.last().second)
                assertEquals("$case", idOf(first), hook.calls.last().expected)
                assertTrue(plain.isEmpty())
            }
        }

    // ── (g) ──────────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `a view model dropped while its publish waits leaves a row whose Retry re-sends the kept wrap`() =
        runBlocking {
            val store = store()
            val seam = PaymentSeam(PEER)
            val vm = screen(store, seam)
            hook.register()
            hook.hold = CompletableDeferred()
            vm.sendMessage(PEER, TEXT)
            assertTrue(PaymentHarness.eventually { hook.calls.isNotEmpty() })
            val first = hook.calls.single()
            vm.viewModelScope.cancel()
            delay(100)
            hook.hold = null
            resendAnswer = NostrChatBridge.PublishResult(1, idOf(first), 1)

            val after = restart(store, seam)
            val row = store.pending.values.single()
            after.retryMessage(PEER, row.id)
            awaitIdle(after)

            assertNeverMayHaveBeenSent(after)
            assertEquals(listOf(idOf(first) to PEER_KEY), resendCalls.toList())
            assertEquals("something new was published", 1, hook.calls.size)
            assertTrue(plain.isEmpty())
        }

    // ── (h) ──────────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `a reply row and a file-link row left the same way are the same rumor and keep their reply and file`() =
        runBlocking {
            val link =
                ZFILEMessage(
                    hash = "fedcba9876543210fedcba9876543210",
                    type = ZFILEType.PNG,
                    size = 2_048L,
                    url = "https://nostr.download/synthetic-zc421-left.png",
                    wrappedKey = "c3ludGhldGljLWxlZnQta2V5",
                    blurhash = "",
                ).serialize()
            for (send in listOf<suspend (ChatViewModel) -> Unit>(
                { it.sendReply(PEER, "synthetic zc421 left reply", "nmsg-syntheticlefttarget", "synthetic left target") },
                { it.sendMessage(PEER, link) },
            )) {
                hook.calls.clear()
                val store = store()
                val seam = PaymentSeam(PEER)
                val (left, first) = leftInFlight(store, seam, send)

                val after = restart(store, seam)
                after.retryMessage(PEER, left.id)
                awaitIdle(after)

                assertEquals(2, hook.calls.size)
                assertEquals(first.payload, hook.calls[1].payload)
                assertEquals(first.second, hook.calls[1].second)
                assertEquals(idOf(first), hook.calls[1].expected)
                val sent = store.pending["nmsg-${idOf(first)}"]
                assertNotNull("rows: ${store.pending.keys}", sent)
                assertEquals(left.replyToId, sent!!.replyToId)
                assertEquals(left.fileZfileContent, sent.fileZfileContent)
            }
        }

    // ── (i) ──────────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `the compose screen's first Open message left the same way holds its pair, and retryMessage sends the same rumor`() =
        runBlocking {
            val peer = SyntheticUnifiedAddress.mainnet(4_922)
            val key = relayKey(422)
            val composeStore = RelayKeyStore()
            hook.register()
            hook.hold = CompletableDeferred()
            hook.store = { composeStore.rows.values.toList() }
            val compose = composeVm(composeStore).also { composeVms += it }
            compose.ready().onRecipientChange(code(peer, key))
            compose.ready().onMessageChange("synthetic zc421 left first open")
            compose.ready().onSendClick()
            assertTrue("fixture: the compose publish was not reached", PaymentHarness.eventually(10_000) { hook.calls.isNotEmpty() })
            val first = hook.calls.single()
            val atCall = first.storedAtCall.singleOrNull()
            assertNotNull("the compose row was not stored before its publish", atCall)
            assertEquals(first.second, RumorPair.secOf(atCall))
            assertEquals(idOf(first), RumorPair.idOf(atCall))
            val left = composeStore.rows.values.single()
            assertTrue(left.isPending)
            hook.hold = null
            hook.acks = 1

            val store = CheckedStore(PaymentPrefs(peer, mode = ConversationMode.OPEN, peerNostrPubkey = key), convId = "SYNTHM1C")
            store.pending[left.id] = left
            hook.store = { store.pending.values.toList() }
            val seam = PaymentSeam(peer)
            val after = screen(store, seam)
            PaymentHarness.setField(after, "_currentUserAddress", MutableStateFlow<String?>(RelayKeyBinding.SELF))
            PaymentHarness.loadPendingMessagesFromPrefs(after)
            after.retryMessage(peer, left.id)
            awaitIdle(after)

            assertNeverMayHaveBeenSent(after)
            assertEquals(2, hook.calls.size)
            assertEquals(first.second, hook.calls[1].second)
            assertEquals(idOf(first), hook.calls[1].expected)
            assertTrue(plain.isEmpty())
        }

    // ── (k) ──────────────────────────────────────────────────────────────────────────────────────────────────

    /**
     * The row 2.12.4 wrote before its publish (v2.12.4 ui-lib/.../ChatViewModel.kt lines 2589-2602, publishNostrAndRenderLocal)
     * through its addPendingMessage (v2.12.4 ui-lib/.../ZchatPreferences.kt lines 2566-2581): org.json drops the keys whose
     * value is null, so a plain text row is exactly these keys. It has no second and no id.
     */
    @Test
    fun `a relay row 2_12_4 left pending is one new message at the current time, never the may-have-been-sent text`() =
        runBlocking {
            val store = store()
            val seam = PaymentSeam(PEER)
            val writtenAt = System.currentTimeMillis() - 120_000L
            val json =
                """{"id":"nostr-out-2124","text":"synthetic 2.12.4 left","timestampMillis":$writtenAt,""" +
                    """"peerAddress":"$PEER","isOutgoing":true,"isPending":true,"status":"SENDING"}"""
            store.pending["nostr-out-2124"] = ZchatPreferences.PendingMessageData.fromJson(json)
            hook.register()
            val startSec = System.currentTimeMillis() / 1000

            val after = restart(store, seam)
            after.retryMessage(PEER, "nostr-out-2124")
            awaitIdle(after)

            assertNeverMayHaveBeenSent(after)
            val made = hook.calls.filter { it.expected == idOf(it) }
            assertEquals("not exactly one new message", 1, made.size)
            assertTrue("the new message is not at the current time", made.single().second >= startSec)
            assertEquals(0, seam.proposalUris.size)
        }

    // ── (l) ──────────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `in a chat switched to Shielded a left text or reply row answers the mode text and keeps its pair`() =
        runBlocking {
            for (send in listOf<suspend (ChatViewModel) -> Unit>(
                { it.sendMessage(PEER, TEXT) },
                { it.sendReply(PEER, "synthetic zc421 shielded reply", "nmsg-syntheticshieldtarget", "synthetic target") },
            )) {
                hook.calls.clear()
                val store = store()
                val seam = PaymentSeam(PEER)
                val (left, first) = leftInFlight(store, seam, send)
                store.inner.mode = ConversationMode.VAULT

                val after = restart(store, seam)
                after.retryMessage(PEER, left.id)
                awaitIdle(after)

                val state = PaymentHarness.sendMessageState(after)
                assertEquals(ChatSendCopy.RETRY_MODE_CHANGED, (state as? SendMessageState.Error)?.message)
                assertEquals(1, hook.calls.size)
                assertTrue(plain.isEmpty())
                assertEquals(0, seam.proposalUris.size)
                val kept = store.pending[left.id]
                assertEquals(first.second, RumorPair.secOf(kept))
                assertEquals(idOf(first), RumorPair.idOf(kept))
            }
        }

    // ── (m), (n), (o): controls, before and after ────────────────────────────────────────────────────────────

    @Test
    fun `control - an on-chain row the closed app left with a recorded submit is refused with the may-have-been-sent text`() =
        runBlocking {
            val store = store(ConversationMode.VAULT)
            store.pending["pending_4211"] =
                ZchatPreferences.PendingMessageData(
                    id = "pending_4211",
                    text = "synthetic on-chain left",
                    timestampMillis = System.currentTimeMillis() - 60_000L,
                    peerAddress = PEER,
                    submitTracked = true,
                    submitStartedAtMillis = System.currentTimeMillis() - 59_000L,
                )
            val vm = restart(store, PaymentSeam(PEER))

            vm.retryMessage(PEER, "pending_4211")

            assertEquals(SubmitAttemptProof.MAY_HAVE_BEEN_SENT, (PaymentHarness.sendMessageState(vm) as? SendMessageState.Error)?.message)
        }

    @Test
    fun `control - an on-chain request row the closed app left with no pair is refused the same way`() =
        runBlocking {
            val store = store(ConversationMode.VAULT)
            store.pending["pending_4212"] =
                ZchatPreferences.PendingMessageData(
                    id = "pending_4212",
                    text = REASON,
                    timestampMillis = System.currentTimeMillis() - 60_000L,
                    peerAddress = PEER,
                    paymentRequestAmountZatoshi = AMOUNT,
                    paymentRequestReason = REASON,
                    submitTracked = true,
                )
            val vm = restart(store, PaymentSeam(PEER))

            vm.retryMessage(PEER, "pending_4212", costConfirmed = true)

            assertEquals(SubmitAttemptProof.MAY_HAVE_BEEN_SENT, (PaymentHarness.sendMessageState(vm) as? SendMessageState.Error)?.message)
        }

    @Test
    fun `control - a tunnel-wait row is not touched`() =
        runBlocking {
            val store = CheckedStore(PaymentPrefs(PEER, mode = ConversationMode.TUNNEL, peerNostrPubkey = null))
            val row =
                ZchatPreferences.PendingMessageData(
                    id = "tunnel-wait-4213",
                    text = "synthetic tunnel wait",
                    timestampMillis = System.currentTimeMillis() - 60_000L,
                    peerAddress = PEER,
                    status = MessageStatus.SENDING.name,
                    submitTracked = true,
                )
            store.pending[row.id] = row
            val vm = restart(store, PaymentSeam(PEER))

            assertFalse(vm.retryMessage(PEER, row.id))
            assertEquals(row, store.pending[row.id])
            assertTrue(hook.calls.isEmpty())
            assertTrue(plain.isEmpty())
        }
}
