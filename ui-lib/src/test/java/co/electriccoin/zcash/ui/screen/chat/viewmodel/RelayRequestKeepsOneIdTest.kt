package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.nostr.NostrChatBridge
import co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferences
import co.electriccoin.zcash.ui.screen.chat.model.ChatSendCopy
import co.electriccoin.zcash.ui.screen.chat.model.ConversationMode
import co.electriccoin.zcash.ui.screen.chat.model.MessageStatus
import co.electriccoin.zcash.ui.screen.chat.model.SendMessageState
import co.electriccoin.zcash.ui.screen.chat.model.SubmitAttemptProof
import co.electriccoin.zcash.ui.screen.chat.viewmodel.HandshakeHarness.awaitIdle
import co.electriccoin.zcash.ui.screen.chat.viewmodel.PaymentHarness.paymentViewModel
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Collections

/**
 * ZC-421 (fix-all 2.13.0, train T6b; ZC-420-SPEC §14.4 Z12 and Z13, §14.5, §14.7, design check 6 Oct): a payment request
 * sent over the relays has ONE rumor id for as long as its row exists, or it is not sent again.
 *
 * At HEAD the relay branch of sendPaymentRequest publishes a new rumor at every Retry once nothing is kept (a restart):
 * the contact gets a second payable card for one request. A request the closed app left in flight is refused with the
 * may-have-been-sent text, and in a chat switched to Shielded a Retry asks a price and sends a second, on-chain request.
 * Wanted: before the first publish the row holds the request's second and its rumor id (NostrChatBridge.rumorIdAt),
 * confirmed by the checked write; every Retry publishes that rumor (the kept wrap first, else publishRumorAt with the
 * stored second and the expected id) while the second is less than 29 days old, whatever the timer or the day; when that
 * is not possible (the id differs, 29 days, the chat is Shielded now, a test-build `nmsg-` row with no second and nothing
 * kept) nothing is sent, quoted or paid, and the row and the send state say ChatSendCopy.PAYMENT_REQUEST_RESEND_REFUSED.
 * With no rumor publisher registered the path is exactly today's.
 *
 * Real ChatViewModel over [PaymentPrefs] behind [CheckedStore]; fake plain publisher and kept-resender; a [RumorHook]
 * registered by name. Every key, id and address is synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RelayRequestKeepsOneIdTest {
    private val sendScopes = SendHarnessScopes()
    private val plain: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private val resendCalls: MutableList<Pair<String, String>> = Collections.synchronizedList(mutableListOf())
    private val hook = RumorHook()

    @Volatile private var resendAnswer: NostrChatBridge.PublishResult? = null

    private companion object {
        const val PEER = "usyntheticzc421request00001"
        const val PEER_KEY = "synthetic-zc421-relay-key-request"
        const val AMOUNT = 31_000_000L
        const val REASON = "synthetic zc421 dinner"
        const val HOUR_SEC = 60L * 60
        const val DAY_SEC = 24 * HOUR_SEC
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
        sendScopes.close()
        Dispatchers.resetMain()
    }

    private fun registerPlain() {
        NostrChatBridge.registerPublisher { payload, _ ->
            plain += payload
            NostrChatBridge.PublishResult(1, "plainid${plain.size}", 1)
        }
    }

    private fun store(): CheckedStore =
        CheckedStore(PaymentPrefs(PEER, mode = ConversationMode.OPEN, peerNostrPubkey = PEER_KEY)).also { s ->
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

    /** A first request that [acks]/[readable] relays took; the stored row and its rumor's call. */
    private suspend fun firstRequest(
        store: CheckedStore,
        seam: PaymentSeam,
        acks: Int = 0,
        readable: Int = 1,
    ): Pair<ZchatPreferences.PendingMessageData, RumorHook.Call> {
        val vm = screen(store, seam)
        hook.register()
        hook.acks = acks
        hook.readable = readable
        vm.sendPaymentRequest(PEER, AMOUNT, REASON)
        awaitIdle(vm)
        val call = hook.calls.singleOrNull() ?: throw AssertionError("the request made ${hook.calls.size} rumor publishes")
        return store.pending.values.single() to call
    }

    private fun answeredRetries(): List<RumorHook.Call> = hook.calls.drop(1).filter { it.expected == null || it.expected == idOf(it) }

    // ── (a), (b) ─────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `the row holds its second and id before the first publish, and a Retry after a restart publishes the same rumor`() =
        runBlocking {
            val store = store()
            val seam = PaymentSeam(PEER)
            val (row, first) = firstRequest(store, seam)
            val id = idOf(first)

            val atCall = first.storedAtCall.singleOrNull { it.paymentRequestAmountZatoshi == AMOUNT }
            assertNotNull("the request row was not stored before its publish", atCall)
            assertEquals("the stored row had no second before the publish", first.second, RumorPair.secOf(atCall))
            assertEquals("the stored row had no rumor id before the publish", id, RumorPair.idOf(atCall))
            assertEquals("the first publish was not checked against the stored id", id, first.expected)
            assertEquals(atCall!!.timestampMillis / 1000, first.second)
            assertEquals("nmsg-$id", row.id)
            assertEquals(id, RumorPair.idOf(row))

            hook.acks = 1
            val after = restart(store, seam)
            after.retryMessage(PEER, row.id)
            awaitIdle(after)

            assertEquals(listOf(id to PEER_KEY), resendCalls.toList())
            assertEquals("not exactly one rumor publish", 1, hook.calls.size - 1)
            assertEquals(first.second, hook.calls[1].second)
            assertEquals(id, hook.calls[1].expected)
            assertTrue(plain.isEmpty())
            assertEquals(setOf(row.id), store.pending.keys)
            assertEquals(MessageStatus.SENT.name, store.pending[row.id]?.status)
            assertEquals(0, seam.proposalUris.size)
        }

    // ── (c) ──────────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `a request 25 hours or 20 days old, or in a chat with a timer, is still the same rumor and the timer is not read`() =
        runBlocking {
            for (age in listOf(25 * HOUR_SEC, 20 * DAY_SEC, 0L)) {
                hook.calls.clear()
                val store = store()
                val seam = PaymentSeam(PEER)
                val (row, first) = firstRequest(store, seam)
                val id = idOf(first)
                if (age == 0L) store.ttl = ZchatPreferences.DisappearingTtl(3_600L, 0L)
                val older = first.second - age
                store.pending[row.id] = RumorPair.with(row, older, hook.idOf(first.payload, first.key, older))

                hook.acks = 1
                val after = restart(store, seam)
                after.retryMessage(PEER, row.id)
                awaitIdle(after)

                val retried = hook.calls.drop(1)
                assertEquals("age $age: not exactly one rumor publish", 1, retried.size)
                assertEquals("age $age", older, retried.single().second)
                assertEquals("age $age", hook.idOf(first.payload, first.key, older), retried.single().expected)
                assertTrue(plain.isEmpty())
                assertEquals("age $age: the timer was read for a request", 0, store.ttlReads.get())
                assertTrue("age $age: fixture", id.isNotEmpty())
            }
        }

    // ── (d) ──────────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `a request no relay took keeps its pending id and its pair, and its Retry publishes the same rumor`() =
        runBlocking {
            val store = store()
            val seam = PaymentSeam(PEER)
            val (row, first) = firstRequest(store, seam, acks = 0, readable = 0)
            assertTrue("a request no relay took keeps its pending id", row.id.startsWith("pending_"))
            assertEquals(first.second, RumorPair.secOf(row))
            assertEquals(idOf(first), RumorPair.idOf(row))

            hook.acks = 1
            hook.readable = 1
            val after = restart(store, seam)
            after.retryMessage(PEER, row.id)
            awaitIdle(after)

            assertEquals(1, answeredRetries().size)
            assertEquals(first.second, hook.calls[1].second)
            assertEquals(idOf(first), hook.calls[1].expected)
            assertTrue(plain.isEmpty())
            assertEquals(0, seam.proposalUris.size)
        }

    // ── (e) ──────────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `a request the closed app left is sent again as the same rumor, never refused as may-have-been-sent`() =
        runBlocking {
            for (takenBeforeClose in listOf(false, true)) {
                hook.calls.clear()
                val store = store()
                val seam = PaymentSeam(PEER)
                val vm = screen(store, seam)
                hook.register()
                hook.acks = if (takenBeforeClose) 1 else 0
                hook.readable = if (takenBeforeClose) 1 else 0
                hook.hold = CompletableDeferred()
                vm.sendPaymentRequest(PEER, AMOUNT, REASON)
                assertTrue("fixture: the publish was not reached", PaymentHarness.eventually { hook.calls.size == 1 })
                // The app is closed here: the publish never answers, and nothing after it is written.
                val first = hook.calls.single()
                val left = store.pending.values.single()
                assertTrue("taken=$takenBeforeClose: the left row is not pending", left.isPending)
                assertTrue(left.id.startsWith("pending_"))
                assertEquals(first.second, RumorPair.secOf(left))
                assertEquals(idOf(first), RumorPair.idOf(left))
                val withoutPair = store.writes.filter { it.id == left.id && RumorPair.idOf(it) == null }
                assertTrue("taken=$takenBeforeClose: the store got a write of this row without its pair", withoutPair.isEmpty())

                hook.hold = null
                hook.acks = 1
                hook.readable = 1
                resendAnswer = null
                val after = restart(store, seam)
                after.retryMessage(PEER, left.id)
                awaitIdle(after)

                val state = PaymentHarness.sendMessageState(after)
                assertFalse(
                    "taken=$takenBeforeClose: a price question for a relay request",
                    state is SendMessageState.NeedsPaidActionConfirmation,
                )
                assertFalse(
                    "taken=$takenBeforeClose: the may-have-been-sent text for a relay request",
                    (state as? SendMessageState.Error)?.message == SubmitAttemptProof.MAY_HAVE_BEEN_SENT,
                )
                assertEquals("taken=$takenBeforeClose: not exactly one rumor publish", 1, hook.calls.size - 1)
                assertEquals(first.second, hook.calls[1].second)
                assertEquals(idOf(first), hook.calls[1].expected)
                assertTrue(plain.isEmpty())
                assertEquals(0, seam.proposalUris.size)
            }
        }

    // ── (f), (g) ─────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `a rotated relay key or a second 29 days old sends nothing and says the request cannot be sent again`() =
        runBlocking {
            for (case in listOf("rotated", "29 days")) {
                hook.calls.clear()
                hook.salt = ""
                val store = store()
                val seam = PaymentSeam(PEER)
                val (row, first) = firstRequest(store, seam)
                if (case == "rotated") {
                    hook.salt = "rotated"
                } else {
                    val old = first.second - 29 * DAY_SEC
                    store.pending[row.id] = RumorPair.with(row, old, hook.idOf(first.payload, first.key, old))
                }
                val pairBefore = RumorPair.secOf(store.pending[row.id]) to RumorPair.idOf(store.pending[row.id])

                hook.acks = 1
                val after = restart(store, seam)
                repeat(2) { tap ->
                    after.retryMessage(PEER, row.id)
                    awaitIdle(after)
                    assertEquals("$case tap ${tap + 1}: something was published", 0, answeredRetries().size)
                    assertTrue(plain.isEmpty())
                    val kept = store.pending[row.id]
                    assertNotNull("$case: the row is gone (rows: ${store.pending.keys})", kept)
                    assertEquals(MessageStatus.FAILED.name, kept!!.status)
                    assertEquals("$case tap ${tap + 1}", requestResendRefusedText(), kept.failureReason)
                    assertEquals(pairBefore, RumorPair.secOf(kept) to RumorPair.idOf(kept))
                    assertEquals(requestResendRefusedText(), (PaymentHarness.sendMessageState(after) as? SendMessageState.Error)?.message)
                }
                assertEquals(0, seam.proposalUris.size)
            }
        }

    // ── (h) ──────────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `a relay request in a chat switched to Shielded asks no price and sends nothing`() =
        runBlocking {
            val store = store()
            val seam = PaymentSeam(PEER)
            val (row, _) = firstRequest(store, seam)
            store.inner.mode = ConversationMode.VAULT

            val after = restart(store, seam)
            after.retryMessage(PEER, row.id)
            awaitIdle(after)

            val state = PaymentHarness.sendMessageState(after)
            assertFalse("a price question for a relay request", state is SendMessageState.NeedsPaidActionConfirmation)
            assertEquals(requestResendRefusedText(), (state as? SendMessageState.Error)?.message)
            assertEquals(requestResendRefusedText(), store.pending[row.id]?.failureReason)
            assertEquals(1, hook.calls.size)
            assertTrue(plain.isEmpty())
            assertEquals(0, seam.proposalUris.size)
        }

    @Test
    fun `control - a pending request row with no fields in a Shielded chat still asks today's price question`() =
        runBlocking {
            val store = store()
            store.inner.mode = ConversationMode.VAULT
            store.pending["pending_421"] =
                ZchatPreferences.PendingMessageData(
                    id = "pending_421",
                    text = REASON,
                    timestampMillis = System.currentTimeMillis() - 60_000L,
                    peerAddress = PEER,
                    isOutgoing = true,
                    isPending = false,
                    status = MessageStatus.FAILED.name,
                    paymentRequestAmountZatoshi = AMOUNT,
                    paymentRequestReason = REASON,
                    submitTracked = true,
                    failureReason = ChatSendCopy.PAYMENT_REQUEST_NOT_CONNECTED,
                )
            val vm = restart(store, PaymentSeam(PEER))
            PaymentHarness.setField(vm, "accountDataSource", fundedAccounts()) // ZC-1822: a wallet that can pay

            vm.retryMessage(PEER, "pending_421")

            assertTrue(PaymentHarness.sendMessageState(vm) is SendMessageState.NeedsPaidActionConfirmation)
        }

    // ── (i) ──────────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `an nmsg request row with no second re-sends the kept wrap, and is refused when nothing is kept`() =
        runBlocking {
            for (kept in listOf(true, false)) {
                hook.calls.clear()
                resendCalls.clear()
                val store = store()
                val seam = PaymentSeam(PEER)
                val id = "nmsg-ridtestbuild${if (kept) 1 else 2}"
                store.pending[id] =
                    ZchatPreferences.PendingMessageData(
                        id = id,
                        text = REASON,
                        timestampMillis = System.currentTimeMillis() - 60_000L,
                        peerAddress = PEER,
                        isOutgoing = true,
                        isPending = false,
                        status = MessageStatus.FAILED.name,
                        paymentRequestAmountZatoshi = AMOUNT,
                        paymentRequestReason = REASON,
                        submitTracked = true,
                        failureReason = ChatSendCopy.FAILED_ONLY_PUBLIC_RELAY,
                    )
                hook.register()
                resendAnswer = if (kept) NostrChatBridge.PublishResult(1, id.removePrefix("nmsg-"), 1) else null
                val vm = restart(store, seam)

                vm.retryMessage(PEER, id)
                awaitIdle(vm)

                assertEquals(listOf(id.removePrefix("nmsg-") to PEER_KEY), resendCalls.toList())
                assertTrue("kept=$kept: a rumor was published", hook.calls.none { it.expected == null || it.expected == idOf(it) })
                assertTrue(plain.isEmpty())
                if (kept) {
                    assertEquals(MessageStatus.SENT.name, store.pending[id]?.status)
                } else {
                    assertEquals(requestResendRefusedText(), store.pending[id]?.failureReason)
                }
                resendAnswer = null
            }
        }

    // ── (j) ──────────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `a Retry while the publisher is not ready keeps the fields, and the next Retry publishes the same rumor`() =
        runBlocking {
            val store = store()
            val seam = PaymentSeam(PEER)
            val (row, first) = firstRequest(store, seam)
            NostrChatBridge.unregisterPublisher()

            val after = restart(store, seam)
            after.retryMessage(PEER, row.id)
            awaitIdle(after)
            assertEquals(
                ChatSendCopy.PAYMENT_REQUEST_NOT_CONNECTED,
                (PaymentHarness.sendMessageState(after) as? SendMessageState.Error)?.message,
            )
            val kept = store.pending[row.id]
            assertEquals(first.second, RumorPair.secOf(kept))
            assertEquals(idOf(first), RumorPair.idOf(kept))

            registerPlain()
            hook.acks = 1
            PaymentHarness.setSendMessageState(after, SendMessageState.Idle)
            after.retryMessage(PEER, row.id)
            awaitIdle(after)
            assertEquals(first.second, hook.calls.last().second)
            assertEquals(idOf(first), hook.calls.last().expected)
            assertTrue(plain.isEmpty())
        }

    // ── (k) ──────────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `when the write of the two fields is not confirmed nothing is published`() =
        runBlocking {
            val store = store()
            val vm = screen(store, PaymentSeam(PEER))
            hook.register()
            store.refuseCommits.set(1)

            vm.sendPaymentRequest(PEER, AMOUNT, REASON)
            awaitIdle(vm)

            assertTrue("fixture: the checked write was not asked", store.refuseCommits.get() == 0)
            assertTrue("a rumor was published after a refused write", hook.calls.isEmpty())
            assertTrue(plain.isEmpty())
        }

    // ── (l) ──────────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `with no rumor publisher a request takes today's path and gets no fields`() =
        runBlocking {
            val store = store()
            val seam = PaymentSeam(PEER)
            val vm = screen(store, seam)
            vm.sendPaymentRequest(PEER, AMOUNT, REASON)
            awaitIdle(vm)

            val row = store.pending["nmsg-plainid1"]
            assertNotNull("rows: ${store.pending.keys}", row)
            assertEquals(null, RumorPair.secOf(row))
            assertEquals(null, RumorPair.idOf(row))
            assertEquals(emptyList<String>(), store.inner.unexpectedCalls.toList())
        }
}
