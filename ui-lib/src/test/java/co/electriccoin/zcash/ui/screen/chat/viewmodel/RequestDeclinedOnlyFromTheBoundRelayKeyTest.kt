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
import java.lang.reflect.InvocationTargetException
import java.time.Instant
import java.util.Collections
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * ZC-1464 (OT213n B1b; FIX_PLAN REVISION 1, Fable M4 and S3; W-122 answer 4): a declined chat request tells the sender.
 *
 * At 669cf19e1 rejectMessageRequest only blocks the sender's relay key and drops the request: nothing goes to the
 * sender, whose bubbles keep showing as sent and whose composer stays open. An inbound `ZRCPT|…` over the relays goes to
 * recordQsPeerClaim and is dropped; on chain it is skipped as a read receipt.
 *
 * Wanted:
 * - Receiver: Reject publishes one `ZRCPT|DECLINED|<hash>` to the request's sender key over the relays (always notify,
 *   W-122), with no address and no message text in it, and the declined request is kept by the store (the Declined
 *   list); Restore publishes `ZRCPT|RESTORED|<hash>`.
 * - Sender: a DECLINED from the seal-authenticated bound relay key of a contact marks that contact's chat as declined
 *   (a per-contact store mark whose method name contains "Declined", set true; RESTORED sets it false), before the
 *   read-receipt and Quantum Shield claim sinks: no row, nothing marked read; the earlier relay bubbles of that chat no
 *   longer show as delivered (status not SENT, CONFIRMED or READ).
 * - M4: the token is accepted only from the bound relay key. An on-chain `ZRCPT|DECLINED` (plaintext memo anyone who
 *   knows the address can send) changes nothing, and a relay DECLINED naming another contact's hash marks only the
 *   sealed sender (guards: these pass before the fix and must keep passing).
 * - compat-2124: a 2.12.4 phone drops both tokens silently. [Frozen2124] copies v2.12.4 ChatViewModel.kt:692-694 (the
 *   relay reader's read-receipt branch) with ZMSGSpecialMessages.kt:121 and ZMSGConstants.kt:55.
 *
 * Real ChatViewModel relay reader (through NostrChatBridge.dispatch) and history pass over fakes; the store methods the
 * fix adds are found by name. Every address, key and id is synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RequestDeclinedOnlyFromTheBoundRelayKeyTest {
    private val sendScopes = SendHarnessScopes()
    private val scheduler = TestCoroutineScheduler()
    private val tag = "synthetic-zc1464-${UUID.randomUUID()}"
    private val peer = SyntheticUnifiedAddress.mainnet(14_641)
    private val other = SyntheticUnifiedAddress.mainnet(14_642)
    private val key = relayKey(1_464)
    private val publishes: MutableList<Pair<String, String>> = Collections.synchronizedList(mutableListOf())
    private val declinedMark = Regex("Declined")

    /** VERBATIM logic of v2.12.4 (a1fb4136f); only the handler's two outcomes are reduced to a returned word. */
    private object Frozen2124 {
        // ZMSGConstants.kt:55
        const val RECEIPT = "ZRCPT|" // Read receipts

        // ZMSGSpecialMessages.kt:27 and :121
        private const val RECEIPT_PREFIX = RECEIPT

        fun isReadReceipt(memo: String): Boolean = memo.startsWith(RECEIPT_PREFIX)

        // ChatViewModel.kt:692-694, observeNostrInbound: `return@collect` = dropped; falling through renders a row.
        fun relayReader(plaintext: String): String {
            if (isReadReceipt(plaintext)) {
                return "dropped"
            }
            return "rendered"
        }
    }

    @Before
    fun setUp() {
        sendScopes.open()
        Dispatchers.setMain(UnconfinedTestDispatcher(scheduler))
        AutoSpendVm.resetProcessState()
        AutoSpendVm.setHistoryRead(true)
        NostrChatBridge.unregisterChatNotifier()
        NostrChatBridge.registerPublisher { text, to ->
            publishes += text to to
            NostrChatBridge.PublishResult(1, "synthetic-zc1464-${publishes.size}")
        }
    }

    @After
    fun tearDown() {
        NostrChatBridge.unregisterPublisher()
        sendScopes.close()
        Dispatchers.resetMain()
        AutoSpendVm.resetProcessState()
    }

    private fun boundOpenChat(): RelayKeyStore =
        RelayKeyStore().apply {
            pubkeys[peer] = key
            modes[peer] = ConversationMode.OPEN
        }

    private fun declined(by: String) = "ZRCPT|DECLINED|${ZMSGProtocol.generateAddressHash(by)}"

    private fun restored(by: String) = "ZRCPT|RESTORED|${ZMSGProtocol.generateAddressHash(by)}"

    private fun request() =
        ZchatPreferences.MessageRequest(
            senderNostrPubkeyHex = key,
            senderAddress = peer,
            relayUrl = RelayKeyBinding.RELAY,
            firstMessage = "synthetic first words of a request",
            timestampMillis = 1_700_000_000_000L,
            eventId = "$tag-req",
            rumorId = "rumor-$tag-req",
        )

    // ── sender side ────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `a DECLINED from the bound relay key marks the chat declined, makes no row and marks nothing read`() =
        runBlocking {
            val store = boundOpenChat()
            val prefs = CallLogPrefs(store.proxy)
            val vm = B1bRelay.chatViewModel(prefs.proxy)
            RelayKeyChatScreen.start(vm, "observeNostrInbound")

            NostrChatBridge.dispatch(B1bRelay.dm(key, declined(peer), "$tag-d1"), prefs.proxy)
            B1bRelay.awaitTrue { "$tag-d1" in store.seen }
            B1bRelay.awaitTrue { prefs.named(declinedMark).isNotEmpty() }

            val marks = prefs.named(declinedMark).filter { it.second.firstOrNull() == peer && true in it.second }
            assertTrue(
                marks.isNotEmpty(),
                "a DECLINED from the bound relay key left no declined mark for the chat (store calls: ${prefs.names()})",
            )
            assertEquals(emptyList(), store.rows.values.filter { it.peerAddress == peer }, "the DECLINED token became a row")
            assertEquals(emptyList(), RelayKeyChatScreen.rows(vm), "the DECLINED token is on screen")
        }

    @Test
    fun `a RESTORED after a DECLINED from the bound relay key clears the mark`() =
        runBlocking {
            val store = boundOpenChat()
            val prefs = CallLogPrefs(store.proxy)
            val vm = B1bRelay.chatViewModel(prefs.proxy)
            RelayKeyChatScreen.start(vm, "observeNostrInbound")

            NostrChatBridge.dispatch(B1bRelay.dm(key, declined(peer), "$tag-d2"), prefs.proxy)
            B1bRelay.awaitTrue { "$tag-d2" in store.seen }
            NostrChatBridge.dispatch(B1bRelay.dm(key, restored(peer), "$tag-r2"), prefs.proxy)
            B1bRelay.awaitTrue { "$tag-r2" in store.seen }
            B1bRelay.awaitTrue { prefs.named(declinedMark).any { false in it.second } }

            val marks = prefs.named(declinedMark).filter { it.second.firstOrNull() == peer && it.second.size >= 2 }
            assertTrue(marks.any { true in it.second }, "the DECLINED set no mark (calls: ${prefs.names()})")
            assertEquals(false, marks.last().second[1], "the RESTORED did not clear the declined mark")
            assertEquals(emptyList(), store.rows.values.filter { it.peerAddress == peer }, "a token became a row")
        }

    @Test
    fun `after a DECLINED the earlier relay bubbles of that chat no longer show as delivered`() =
        runBlocking {
            val store = boundOpenChat()
            val prefs = CallLogPrefs(store.proxy)
            val vm = B1bRelay.chatViewModel(prefs.proxy)
            val earlier =
                ChatMessage(
                    id = "nmsg-$tag-out-1",
                    txId = null,
                    text = "synthetic words the receiver never saw",
                    timestamp = Instant.now().minusSeconds(600),
                    isOutgoing = true,
                    peerAddress = peer,
                    isPending = false,
                    status = MessageStatus.SENT,
                )
            store.rows[earlier.id] =
                ZchatPreferences.PendingMessageData(
                    id = earlier.id,
                    text = earlier.text,
                    timestampMillis = earlier.timestamp.toEpochMilli(),
                    peerAddress = peer,
                    isOutgoing = true,
                    isPending = false,
                    status = MessageStatus.SENT.name,
                )
            B1bRelay.pending(vm).value = listOf(earlier)
            RelayKeyChatScreen.start(vm, "observeNostrInbound")

            NostrChatBridge.dispatch(B1bRelay.dm(key, declined(peer), "$tag-d3"), prefs.proxy)
            B1bRelay.awaitTrue { "$tag-d3" in store.seen }
            B1bRelay.awaitTrue {
                RelayKeyChatScreen.rows(vm).firstOrNull { it.id == earlier.id }?.status != MessageStatus.SENT
            }

            val shown =
                RelayKeyChatScreen.rows(vm).firstOrNull { row ->
                    row.id == earlier.id
                } ?: fail("the earlier bubble was removed")
            assertFalse(
                shown.status in setOf(MessageStatus.SENT, MessageStatus.CONFIRMED, MessageStatus.READ),
                "the earlier relay bubble still shows as delivered (${shown.status}) after the request was declined",
            )
        }

    @Test
    fun `a relay DECLINED naming another contact's hash never marks that contact (guard)`() =
        runBlocking {
            val store = boundOpenChat().apply { pubkeys[other] = relayKey(1_465) }
            val prefs = CallLogPrefs(store.proxy)
            val vm = B1bRelay.chatViewModel(prefs.proxy)
            RelayKeyChatScreen.start(vm, "observeNostrInbound")

            NostrChatBridge.dispatch(B1bRelay.dm(key, declined(other), "$tag-d4"), prefs.proxy)
            B1bRelay.awaitTrue { "$tag-d4" in store.seen }

            assertEquals(
                emptyList(),
                prefs.named(declinedMark).filter { it.second.firstOrNull() == other },
                "a DECLINED sealed by one contact's key marked another contact's chat",
            )
        }

    @Test
    fun `an on-chain ZRCPT DECLINED changes nothing (M4 guard)`() =
        runBlocking {
            val f = HandshakePassFixture(scheduler, peer)
            val calls: MutableList<Pair<String, List<Any?>>> = Collections.synchronizedList(mutableListOf())
            f.extra = { method, args ->
                calls += method.name to args.toList()
                ClaimMarkMaps.UNHANDLED
            }
            f.open()
            f.received("spoofed-decline", declined(peer), minedAt = HandshakePassFixture.NEW + 5)
            f.pass()

            assertEquals(
                emptyList(),
                calls.filter { declinedMark.containsMatchIn(it.first) && true in it.second },
                "an unauthenticated on-chain memo marked a chat declined (a free composer lock-out)",
            )
            assertEquals(
                emptyList(),
                PaymentHarness.pendingMessages(f.vm).filter { it.text.startsWith("ZRCPT|") },
                "the on-chain token was shown as a row",
            )
        }

    // ── receiver side ──────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `Reject publishes one DECLINED to the sender's key, without address or text, and the store keeps the request`() =
        runBlocking {
            val store = RelayKeyStore()
            val prefs = CallLogPrefs(store.proxy)
            val vm = B1bRelay.chatViewModel(prefs.proxy)
            val request = request()

            vm.rejectMessageRequest(request)
            B1bRelay.awaitTrue { publishes.isNotEmpty() }
            HandshakeHarness.awaitIdle(vm)

            val sent = synchronized(publishes) { publishes.toList() }
            assertEquals(1, sent.size, "Reject did not publish exactly one decline to the sender (published ${sent.size})")
            val (text, to) = sent.single()
            assertEquals(key, to, "the decline went to a key other than the request's sender")
            assertTrue(text.startsWith("ZRCPT|DECLINED"), "the decline is not a ZRCPT|DECLINED token")
            assertFalse(peer in text, "the decline carries the claimed address")
            assertFalse(request.firstMessage in text, "the decline carries the request's text")
            assertEquals("dropped", Frozen2124.relayReader(text), "a 2.12.4 sender would render the decline as a bubble")
            assertTrue(
                prefs.calls.any { (name, args) -> Regex("(?i)declin").containsMatchIn(name) && (request in args || key in args) },
                "the declined request was not kept for the Declined list (calls: ${prefs.names()})",
            )
            assertTrue(prefs.calls.any { it.first == "blockNostrPubkey" && it.second.firstOrNull() == key }, "the key is no longer blocked")
        }

    @Test
    fun `Restore publishes one RESTORED to the sender's key that a 2_12_4 phone also drops`() =
        runBlocking {
            val store = RelayKeyStore()
            val prefs = CallLogPrefs(store.proxy)
            val vm = B1bRelay.chatViewModel(prefs.proxy)
            val request = request()
            val restore =
                ChatViewModel::class.java.methods.firstOrNull {
                    Regex("(?i)(restore.*declin|undo.*declin)").containsMatchIn(it.name) && it.parameterCount == 1
                } ?: fail("ChatViewModel has no Restore for a declined request (restore…Declined… / undoDecline…)")
            val arg: Any = if (restore.parameterTypes[0] == String::class.java) key else request
            try {
                restore.invoke(vm, arg)
            } catch (e: InvocationTargetException) {
                throw e.targetException
            }
            B1bRelay.awaitTrue { publishes.isNotEmpty() }
            HandshakeHarness.awaitIdle(vm)

            val sent = synchronized(publishes) { publishes.toList() }
            assertEquals(1, sent.size, "Restore did not publish exactly one RESTORED")
            assertEquals(key, sent.single().second)
            assertTrue(sent.single().first.startsWith("ZRCPT|RESTORED"), "the restore is not a ZRCPT|RESTORED token")
            assertEquals("dropped", Frozen2124.relayReader(sent.single().first), "a 2.12.4 sender would render it as a bubble")
        }

    @Test
    fun `a 2_12_4 relay reader drops both tokens (Frozen2124 control)`() {
        assertEquals("dropped", Frozen2124.relayReader(declined(peer)))
        assertEquals("dropped", Frozen2124.relayReader(restored(peer)))
        assertEquals("rendered", Frozen2124.relayReader("DECLINED|${ZMSGProtocol.generateAddressHash(peer)}"))
    }
}
