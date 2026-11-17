package co.electriccoin.zcash.ui.screen.chat.viewmodel

import androidx.lifecycle.viewModelScope
import co.electriccoin.zcash.ui.nostr.NostrChatBridge
import co.electriccoin.zcash.ui.screen.chat.crypto.E2EEncryption
import co.electriccoin.zcash.ui.screen.chat.crypto.SeedDerivedE2EKeyProvider
import co.electriccoin.zcash.ui.screen.chat.crypto.ratchet.E2EMessageProcessor
import co.electriccoin.zcash.ui.screen.chat.crypto.ratchet.InMemoryRatchetStateStore
import co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferences
import co.electriccoin.zcash.ui.screen.chat.model.ChatListState
import co.electriccoin.zcash.ui.screen.chat.model.ChatMessage
import co.electriccoin.zcash.ui.screen.chat.model.ChatSendCopy
import co.electriccoin.zcash.ui.screen.chat.model.ConversationMode
import co.electriccoin.zcash.ui.screen.chat.model.MessageStatus
import co.electriccoin.zcash.ui.screen.chat.model.SendMessageState
import co.electriccoin.zcash.ui.screen.chat.model.ZBootMessage
import co.electriccoin.zcash.ui.screen.chat.model.ZFILEMessage
import co.electriccoin.zcash.ui.screen.chat.model.ZFILEType
import co.electriccoin.zcash.ui.screen.chat.view.ChatResourceText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Modifier
import java.lang.reflect.Proxy
import java.util.Base64
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * SR-B2 / ZC-107 (fix-all 2.13.0, train T5b; photos-privacy SPEC §3 with Review changes (SR-B2), corrected by t5b SPECS
 * §4 C-SRB2-1..C-SRB2-4): queued Tunnel messages never go over the relays after the chat was switched to Shielded.
 *
 * At 8a572cb01 a Tunnel chat whose contact's relay key is not known yet queues the user's messages ("waiting for secure
 * connection", `tunnel-wait-` rows) and pays our on-chain ZBOOT. If the user then switches the chat to Shielded, the
 * contact's ZBOOT (or a KEX that carries the relay key, or the rehydrate at the next start) runs
 * `flushTunnelPendingPayloads`, which publishes every queued payload over the relays without reading the mode: the user's
 * text leaves through the relays of a chat they made Shielded. A relay row in a Shielded chat is then a dead end: its
 * Retry is refused by sendMessage / sendReply with RETRY_MODE_CHANGED.
 *
 * Wanted: after the atomic claim the flush reads the mode (unset = Shielded; a chat routeIncomingBoot just adopted to
 * Tunnel still flushes); in a Shielded chat each queued payload becomes a FAILED row with the reason
 * FAILED_SWITCHED_TO_SHIELDED under a fresh `nostr-out-` id, the `tunnel-wait-` row is removed and nothing is published.
 * Its Retry sends the payload as a new Shielded send (sendReply for a reply, SR-A's exact question for a photo or file)
 * with every question of that path; the row is removed only once the new send took the payload, and Cancel or a refused
 * send keeps it FAILED with its text. Nothing is paid twice.
 *
 * New API is reached by name (the ChatSendCopy constant, the resource key), so this file compiles before it exists. The
 * relays are a recording publisher; the wallet is HandshakeSeam (every proposal is counted). All values are synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class QueuedTunnelMessagesStayOffRelaysAfterShieldedSwitchTest {
    private val sendScopes = SendHarnessScopes()
    private val main = UnconfinedTestDispatcher()

    /**
     * ZC-1276: runs what is due on Main and moves virtual time 100 ms, never to the end: a jump would fire a send's 15-minute
     * build limit while its build runs on a real thread. With a real wait of at least 5 ms per step the limit would need 45 s,
     * beyond the 20 s these loops wait.
     */
    private fun pumpMain() {
        main.scheduler.advanceTimeBy(100L)
        main.scheduler.runCurrent()
    }

    private val publishes: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private var savedOwnHistoryRead = false

    private companion object {
        const val TEXT_1 = "synthetic queued tunnel text one"
        const val TEXT_2 = "synthetic queued tunnel text two"
        const val REPLIED_TO = "nmsg-synthetic-replied-to"
        const val REPLY_PREVIEW = "synthetic quoted line"
        const val QUESTION = "NeedsPaidActionConfirmation"

        /** A text long enough for several on-chain memos: its Shielded send asks first (LONG_MESSAGE). */
        val LONG_TEXT = "synthetic long queued tunnel text ".repeat(40)
    }

    @Before
    fun setUp() {
        sendScopes.open()
        Dispatchers.setMain(main)
        AutoSpendVm.resetProcessState()
        AutoSpendVm.setHistoryRead(true)
        FirstMessagePlanChat.resetOwedKexState()
        clearTunnelQueue()
        savedOwnHistoryRead = SeedDerivedE2EKeyProvider.processOwnHistoryRead.get()
        SeedDerivedE2EKeyProvider.processOwnHistoryRead.set(true)
        NostrChatBridge.unregisterChatNotifier()
        NostrChatBridge.registerPublisher { text, _ ->
            publishes += text
            NostrChatBridge.PublishResult(1, "synthetic-srb2-${publishes.size}")
        }
    }

    @After
    fun tearDown() {
        NostrChatBridge.unregisterPublisher()
        SeedDerivedE2EKeyProvider.processOwnHistoryRead.set(savedOwnHistoryRead)
        sendScopes.close()
        Dispatchers.resetMain()
        AutoSpendVm.resetProcessState()
        FirstMessagePlanChat.resetOwedKexState()
        clearTunnelQueue()
    }

    // ------------------------------------------------------------------ B2-1

    @Test
    fun `B2-1 two queued Tunnel texts are not published after a switch to Shielded and become FAILED rows`() =
        runBlocking<Unit> {
            val c = Chat(1)
            val vm = c.viewModel()
            c.queue(vm, TEXT_1)
            c.queue(vm, TEXT_2)
            assertEquals(2, c.storedRows().count { it.id.startsWith("tunnel-wait-") }, "fixture: the two texts did not queue")

            c.switchToShielded(vm)
            c.inboundBoot(vm, epoch = 1)

            assertEquals(emptyList(), userPublishes(TEXT_1, TEXT_2), "queued text went over the relays of a Shielded chat")
            c.assertSwitchedRows(vm, listOf(TEXT_1, TEXT_2))
        }

    // ------------------------------------------------------------------ B2-2

    @Test
    fun `B2-2 Retry sends the row as a new Shielded message, on-chain once, and removes the old row`() =
        runBlocking<Unit> {
            val c = Chat(2)
            val vm = c.viewModel()
            c.queue(vm, TEXT_1)
            c.queue(vm, TEXT_2)
            c.switchToShielded(vm)
            c.inboundBoot(vm, epoch = 1)
            val row = c.switchedRow(vm, TEXT_1)
            val proposalsBefore = c.proposals()

            val accepted = vm.retryMessage(c.peer, row.id)
            c.awaitSettled(vm)

            assertTrue(accepted, "Retry of the switched row was refused, state ${HandshakeHarness.sendMessageState(vm)}")
            assertEquals(1, c.proposals() - proposalsBefore, "the Retry did not send the message on-chain exactly once")
            assertEquals(emptyList(), userPublishes(TEXT_1, TEXT_2), "the Retry went over the relays")
            assertFalse(c.outgoingRows(vm).any { it.id == row.id }, "the old row is still shown")
            assertFalse(c.storedRows().any { it.id == row.id }, "the old row is still stored")
            val sent = c.outgoingRows(vm).filter { it.text == TEXT_1 }
            assertEquals(1, sent.size, "the retried text is not one row: $sent")
            assertTrue(sent.single().id.startsWith("pending_"), "the retried text is not an on-chain row: ${sent.single().id}")
            assertEquals(MessageStatus.FAILED, c.outgoingRows(vm).single { it.text == TEXT_2 }.status, "the other row changed")
        }

    @Test
    fun `B2-2b a double Retry tap pays once`() =
        runBlocking<Unit> {
            val c = Chat(3)
            val vm = c.viewModel()
            c.queue(vm, TEXT_1)
            c.switchToShielded(vm)
            c.inboundBoot(vm, epoch = 1)
            val row = c.switchedRow(vm, TEXT_1)
            val proposalsBefore = c.proposals()

            vm.retryMessage(c.peer, row.id)
            vm.retryMessage(c.peer, row.id)
            c.awaitSettled(vm)
            vm.retryMessage(c.peer, row.id)
            c.awaitSettled(vm)

            assertEquals(1, c.proposals() - proposalsBefore, "the switched row was paid more than once")
        }

    // ------------------------------------------------------------------ B2-3

    @Test
    fun `B2-3 a queued photo link is not published, and its Retry asks SR-A's exact question without uploading`() =
        runBlocking<Unit> {
            val c = Chat(4)
            val vm = c.viewModel()
            val line = c.photoLine(vm)
            c.queue(vm, line)
            c.switchToShielded(vm)
            c.inboundBoot(vm, epoch = 1)

            assertEquals(emptyList(), fileLinkPublishes(), "the link went over the relays")
            val row =
                c.outgoingRows(vm).singleOrNull { it.fileZfileContent == line }
                    ?: fail("no row for the queued photo: ${c.outgoingRows(vm)}")
            assertEquals(MessageStatus.FAILED, row.status, "the queued photo is not FAILED")
            assertEquals(switchedReason(), row.failureReason, "the queued photo does not say why")
            assertTrue(row.id.startsWith("nostr-out-"), "the queued photo's row is not a fresh relay row: ${row.id}")
            val proposalsBefore = c.proposals()

            vm.retryMessage(c.peer, row.id)
            c.awaitSettled(vm)

            val state = HandshakeHarness.sendMessageState(vm)
            val asked = state as? SendMessageState.NeedsPaidActionConfirmation ?: fail("the photo's Retry asked nothing, state $state")
            assertEquals("SHIELDED_ATTACHMENT_RETRY", asked.confirmation.action.name, "the photo's Retry is not SR-A's question")
            assertEquals(0, c.proposals() - proposalsBefore, "the photo's Retry paid before the answer")

            vm.confirmPaidAction()
            c.awaitSettled(vm)

            assertEquals(1, c.proposals() - proposalsBefore, "the photo's link was not sent on-chain once after Pay")
            assertEquals(emptyList(), fileLinkPublishes(), "the link went over the relays")
            assertFalse(c.storedRows().any { it.id == row.id }, "the relay row is still stored after the on-chain send")
        }

    // ------------------------------------------------------------------ B2-4

    @Test
    fun `B2-4 the rehydrate at start does not publish queued rows of a chat that is Shielded now`() =
        runBlocking<Unit> {
            val c = Chat(5, mode = ConversationMode.VAULT)
            c.explicit += c.peer
            c.pubkeys[c.peer] = c.relayKey
            c.seedTunnelWaitRow("tunnel-wait-synthetic-srb2-a", TEXT_1)
            c.seedTunnelWaitRow("tunnel-wait-synthetic-srb2-b", TEXT_2)
            val vm = c.viewModel()

            c.rehydrate(vm)

            assertEquals(emptyList(), userPublishes(TEXT_1, TEXT_2), "the rehydrate published queued text of a Shielded chat")
            c.assertSwitchedRows(vm, listOf(TEXT_1, TEXT_2))
        }

    // ------------------------------------------------------------------ B2-5 (controls)

    @Test
    fun `B2-5 control - with no switch the queued texts go over the relays as today`() =
        runBlocking<Unit> {
            val c = Chat(6)
            val vm = c.viewModel()
            c.queue(vm, TEXT_1)
            c.queue(vm, TEXT_2)

            c.inboundBoot(vm, epoch = 1)

            assertEquals(setOf(TEXT_1, TEXT_2), userPublishes(TEXT_1, TEXT_2).toSet(), "the queued Tunnel texts were not published")
            assertFalse(c.storedRows().any { it.id.startsWith("tunnel-wait-") }, "a tunnel-wait row is left")
            assertTrue(c.outgoingRows(vm).none { it.status == MessageStatus.FAILED }, "a Tunnel row failed: ${c.outgoingRows(vm)}")
        }

    @Test
    fun `B2-5b control - a Shielded chat the contact's ZBOOT adopts to Tunnel still flushes its queue`() =
        runBlocking<Unit> {
            val c = Chat(7)
            val vm = c.viewModel()
            c.queue(vm, TEXT_1)
            // Not the user's pin: the chat became Shielded some other way (C-SRB2-4: the mode is read after the adopt).
            c.base.mode = ConversationMode.VAULT
            c.sentBoot.clear()

            c.inboundBoot(vm, epoch = 1)

            assertEquals(ConversationMode.TUNNEL, c.base.mode, "fixture: the ZBOOT did not adopt Tunnel")
            assertEquals(listOf(TEXT_1), userPublishes(TEXT_1), "the adopted Tunnel chat did not flush its queue")
        }

    // ------------------------------------------------------------------ B2-6

    @Test
    fun `B2-6 after a restart both FAILED rows are still there under fresh ids, and a second ZBOOT leaves them`() =
        runBlocking<Unit> {
            val c = Chat(8)
            val vm = c.viewModel()
            c.queue(vm, TEXT_1)
            c.queue(vm, TEXT_2)
            c.switchToShielded(vm)
            c.inboundBoot(vm, epoch = 1)

            val restarted = c.viewModel()
            c.rehydrate(restarted)

            c.assertSwitchedRows(restarted, listOf(TEXT_1, TEXT_2))
            val ids = c.outgoingRows(restarted).map { it.id }.toSet()

            c.inboundBoot(restarted, epoch = 2)

            assertEquals(emptyList(), userPublishes(TEXT_1, TEXT_2), "a second ZBOOT published the rows")
            c.assertSwitchedRows(restarted, listOf(TEXT_1, TEXT_2))
            assertEquals(ids, c.outgoingRows(restarted).map { it.id }.toSet(), "a second ZBOOT changed the rows")
        }

    // ------------------------------------------------------------------ B2-7

    @Test
    fun `B2-7a Cancel on the Retry's question keeps the row FAILED with its text, and Pay later sends it once`() =
        runBlocking<Unit> {
            val c = Chat(9)
            val vm = c.viewModel()
            c.queue(vm, LONG_TEXT)
            c.switchToShielded(vm)
            c.inboundBoot(vm, epoch = 1)
            val row = c.switchedRow(vm, LONG_TEXT)
            val proposalsBefore = c.proposals()

            vm.retryMessage(c.peer, row.id)
            c.awaitSettled(vm)
            val state = HandshakeHarness.sendMessageState(vm)
            assertEquals(QUESTION, state.javaClass.simpleName, "a several-memo Retry did not ask first, state $state")
            vm.dismissPaidAction()
            c.awaitSettled(vm)

            assertEquals(0, c.proposals() - proposalsBefore, "Cancel paid")
            c.assertRowKept(vm, row.id, LONG_TEXT)

            vm.retryMessage(c.peer, row.id)
            c.awaitSettled(vm)
            vm.confirmPaidAction()
            c.awaitSettled(vm)

            assertEquals(1, c.proposals() - proposalsBefore, "Pay did not send the long text on-chain once")
            assertFalse(c.storedRows().any { it.id == row.id }, "the old row is still stored after the send took it")
            assertEquals(emptyList(), userPublishes(LONG_TEXT), "the long text went over the relays")
        }

    @Test
    fun `B2-7b a Retry the send refuses keeps the row FAILED with its text`() =
        runBlocking<Unit> {
            val c = Chat(10)
            val vm = c.viewModel()
            c.queue(vm, TEXT_1)
            c.switchToShielded(vm)
            c.inboundBoot(vm, epoch = 1)
            val row = c.switchedRow(vm, TEXT_1)
            val proposalsBefore = c.proposals()
            c.base.keyChanged = true

            val accepted = vm.retryMessage(c.peer, row.id)
            c.awaitSettled(vm)

            assertFalse(accepted, "a refused Retry reported accepted")
            assertEquals(0, c.proposals() - proposalsBefore, "a refused Retry paid")
            c.assertRowKept(vm, row.id, TEXT_1)
        }

    @Test
    fun `B2-7c a reply row keeps its reply and its Retry goes through sendReply`() =
        runBlocking<Unit> {
            val c = Chat(11)
            val vm = c.viewModel()
            assertTrue(vm.sendReply(c.peer, TEXT_1, REPLIED_TO, REPLY_PREVIEW), "fixture: the reply was not accepted")
            c.awaitSettled(vm)
            assertEquals(1, c.storedRows().count { it.id.startsWith("tunnel-wait-") }, "fixture: the reply did not queue")
            c.switchToShielded(vm)
            c.inboundBoot(vm, epoch = 1)

            assertEquals(emptyList(), userPublishes(TEXT_1), "the queued reply went over the relays")
            val row = c.switchedRow(vm, TEXT_1)
            assertEquals(REPLIED_TO, row.replyToId, "the FAILED reply row lost its reply")
            assertEquals(REPLY_PREVIEW, row.replyToPreview, "the FAILED reply row lost its quote")
            val proposalsBefore = c.proposals()

            assertTrue(vm.retryMessage(c.peer, row.id), "the reply's Retry was refused, state ${HandshakeHarness.sendMessageState(vm)}")
            c.awaitSettled(vm)

            assertEquals(1, c.proposals() - proposalsBefore, "the reply was not sent on-chain once")
            assertFalse(c.storedRows().any { it.id == row.id }, "the old reply row is still stored")
            val sent = c.outgoingRows(vm).singleOrNull { it.text == TEXT_1 } ?: fail("no row for the retried reply: ${c.outgoingRows(vm)}")
            assertTrue(sent.id.startsWith("pending_"), "the retried reply is not an on-chain row: ${sent.id}")
            assertEquals(REPLIED_TO, sent.replyToId, "the retried reply lost its reply")
        }

    @Test
    fun `B2-7d a Retry waiting on the cost notice keeps the row when the notice is dismissed`() =
        runBlocking<Unit> {
            val c = Chat(12)
            c.costAcknowledged = false
            val vm = c.viewModel()
            c.queue(vm, TEXT_1)
            c.switchToShielded(vm)
            c.inboundBoot(vm, epoch = 1)
            val row = c.switchedRow(vm, TEXT_1)
            val proposalsBefore = c.proposals()

            vm.retryMessage(c.peer, row.id)
            c.awaitSettled(vm)
            assertTrue(c.costNoticeShown(vm), "the Retry did not show the cost notice, state ${HandshakeHarness.sendMessageState(vm)}")
            vm.dismissCostDisclaimer()
            c.awaitSettled(vm)

            assertEquals(0, c.proposals() - proposalsBefore, "a dismissed cost notice paid")
            c.assertRowKept(vm, row.id, TEXT_1)

            vm.retryMessage(c.peer, row.id)
            c.awaitSettled(vm)
            vm.acknowledgeCostDisclaimer()
            c.awaitSettled(vm)

            assertEquals(1, c.proposals() - proposalsBefore, "the acknowledged Retry did not send once")
            assertFalse(c.storedRows().any { it.id == row.id }, "the old row is still stored")
        }

    // ------------------------------------------------------------------ S-B1 text

    @Test
    fun `S-B1 the reason is a resource in English and Spanish and promises no question`() {
        val en = ChatResourceText.strings("values")
        val es = ChatResourceText.strings("values-es")
        val key = "chat_send_failed_switched_to_shielded"
        val english = en[key] ?: fail("no English $key")
        val spanish = es[key] ?: fail("no Spanish $key")
        assertEquals(
            "Not sent — this chat switched to Shielded before the free connection was ready, so it was not sent through the " +
                "relays. Tap Retry to send it as a Shielded message instead: that is a paid on-chain send, like every Shielded " +
                "message.",
            english,
        )
        assertEquals(
            "No enviado: este chat pasó a Shielded antes de que la conexión gratuita estuviera lista, así que no se envió por " +
                "los relays. Toca Reintentar para enviarlo como mensaje Shielded: es un envío pagado en la cadena, como todo " +
                "mensaje Shielded.",
            spanish,
        )
        for (promise in listOf("ask", "see the cost", "free")) {
            assertFalse(english.substringAfter("Tap Retry").contains(promise), "the Retry sentence promises '$promise'")
        }
        assertEquals(english, switchedReason(), "ChatSendCopy.FAILED_SWITCHED_TO_SHIELDED is not the English text")
        assertNotNull(ChatResourceText.stringId(key))
        assertEquals(ChatResourceText.stringId(key), ChatSendCopy.localizedResource(english), "the reason shows no resource")
    }

    // ------------------------------------------------------------------ fixture

    private fun switchedReason(): String =
        try {
            ChatSendCopy::class.java.getField("FAILED_SWITCHED_TO_SHIELDED").get(null) as String
        } catch (e: NoSuchFieldException) {
            throw AssertionError("ChatSendCopy has no FAILED_SWITCHED_TO_SHIELDED", e)
        }

    /** The relay publishes that carry any of [texts] (the user's content; ZBOOT and mode controls carry none). */
    private fun userPublishes(vararg texts: String): List<String> =
        synchronized(publishes) { publishes.toList() }.mapNotNull { sent -> texts.firstOrNull { sent.contains(it) } }

    /** The relay publishes that carry an attachment link. */
    private fun fileLinkPublishes(): List<String> = synchronized(publishes) { publishes.filter { it.contains("ZFILE|") } }

    private fun clearTunnelQueue() {
        for (name in listOf("tunnelPendingPayloads", "tunnelQueueRehydrated")) {
            val field =
                ChatViewModel::class.java.declaredFields.firstOrNull { it.name == name && Modifier.isStatic(it.modifiers) } ?: continue
            field.isAccessible = true
            (field.get(null) as? MutableMap<*, *>)?.clear()
            (field.get(null) as? MutableCollection<*>)?.clear()
        }
    }

    /**
     * One contact, a Tunnel chat (or [mode]) with both E2E keys held and the contact's relay key unknown until its ZBOOT
     * arrives. The store is PaidChatFixture's (rows, keys, mode, the 15,000 fee) with the relay key, the pinned mode, the
     * ZBOOT bookkeeping and the cost acknowledgement kept here.
     */
    private inner class Chat(
        val n: Int,
        mode: ConversationMode = ConversationMode.TUNNEL,
    ) {
        val peer: String = SyntheticUnifiedAddress.mainnet(6_700 + n)
        val base = PaidChatFixture(peer, mode)
        val relayKey: String = "%064x".format(n.toLong() * 0x2545f491L + 0x1234567L)
        private val identity = E2EEncryption.generateKeyPair()
        val pubkeys: MutableMap<String, String> = ConcurrentHashMap()
        private val relays: MutableMap<String, String> = ConcurrentHashMap()
        val explicit: MutableSet<String> = ConcurrentHashMap.newKeySet()
        private val bootSigs: MutableSet<String> = ConcurrentHashMap.newKeySet()
        private val epochs: MutableMap<String, Long> = ConcurrentHashMap()
        val sentBoot: MutableMap<String, String> = ConcurrentHashMap()

        @Volatile var costAcknowledged: Boolean = true

        val proxy: ZchatPreferences =
            Proxy.newProxyInstance(
                ZchatPreferences::class.java.classLoader,
                arrayOf(ZchatPreferences::class.java),
            ) { _, method, args ->
                fun str(i: Int) = args!![i] as String
                when (method.name) {
                    "getPeerNostrPubkey" -> pubkeys[str(0)]
                    "setPeerNostrPubkey" -> put(pubkeys, str(0), args!![1] as String?)
                    "getPeerNostrRelay" -> relays[str(0)]
                    "setPeerNostrRelay" -> put(relays, str(0), args!![1] as String?)
                    "findPeerByNostrPubkey" -> pubkeys.entries.firstOrNull { it.value.equals(str(0), ignoreCase = true) }?.key
                    "getE2EPeerPublicKey" -> if (str(0) == peer) identity.publicKey else null
                    "isConversationModeExplicit" -> str(0) in explicit
                    "setConversationModeExplicit" -> explicit.add(str(0))
                    "hasProcessedBootSig" -> str(0) in bootSigs
                    "markBootSigProcessed" -> bootSigs.add(str(0))
                    "getPeerBootEpoch" -> epochs[str(0)] ?: 0L
                    "setPeerBootEpoch" -> epochs.put(str(0), args!![1] as Long)
                    "getSentNostrBootPubkey" -> sentBoot[str(0)]
                    "setSentNostrBootPubkey" -> {
                        put(sentBoot, str(0), args!![1] as String?)
                        true
                    }
                    "hasAcknowledgedMessageCost" -> costAcknowledged
                    "setAcknowledgedMessageCost" -> {
                        costAcknowledged = true
                    }
                    else ->
                        try {
                            method.invoke(base.proxy, *(args ?: emptyArray()))
                        } catch (e: InvocationTargetException) {
                            throw e.targetException
                        }
                }.let { if (method.returnType == Void.TYPE) null else it }
            } as ZchatPreferences

        private fun put(
            map: MutableMap<String, String>,
            key: String,
            value: String?,
        ) {
            if (value == null) map.remove(key) else map[key] = value
        }

        @Suppress("UNCHECKED_CAST")
        fun costNoticeShown(vm: ChatViewModel): Boolean =
            (
                ChatViewModel::class.java
                    .getDeclaredField("_showCostDisclaimer")
                    .apply { isAccessible = true }
                    .get(vm) as MutableStateFlow<Boolean>
            ).value

        suspend fun viewModel(): ChatViewModel {
            val vm = HandshakeHarness.chatViewModelWith(proxy, base.seam).also(AutoSpendVm::wireBootstrapFields)
            val processors = ConcurrentHashMap<String, E2EMessageProcessor>()
            processors["$peer:${PaidChatFixture.CONV_ID}"] =
                E2EMessageProcessor(ByteArray(32) { 7 }, PaidChatFixture.CONV_ID, true, InMemoryRatchetStateStore())
            AutoSpendVm.setField(vm, "messageProcessors", processors)
            AutoSpendVm.setField(vm, "decryptedTextCache", ConcurrentHashMap<String, String>())
            AutoSpendVm.setField(vm, "pendingMessages", MutableStateFlow(emptyList<ChatMessage>()))
            AutoSpendVm.setField(vm, "messageQueue", mutableListOf<Any?>())
            AutoSpendVm.setField(vm, "_chatListState", MutableStateFlow<ChatListState>(ChatListState.Loading))
            AutoSpendVm.setField(vm, "_showCostDisclaimer", MutableStateFlow(false))
            AutoSpendVm.setField(vm, "_blockHeight", MutableStateFlow<Long?>(null))
            AutoSpendVm.setField(vm, "hiddenMessages", MutableStateFlow(emptySet<String>()))
            AutoSpendVm.setField(vm, "addressCache", NoOpAddressCache.proxy)
            AutoSpendVm.setField(vm, "replyRefSentinel", '\u0001')
            AutoSpendVm.setField(vm, "replyRefPrefix", "\u0001RPL:")
            AutoSpendVm.setField(vm, "replyPreviewSep", '\u001f')
            AutoSpendVm.setField(vm, "_restoredTypedText", MutableStateFlow<Any?>(null))
            return vm
        }

        fun proposals(): Int = base.seam.proposalRequests.get()

        fun storedRows(): List<ZchatPreferences.PendingMessageData> = proxy.getPendingMessages().filter { it.peerAddress == peer }

        @Suppress("UNCHECKED_CAST")
        fun pending(vm: ChatViewModel): MutableStateFlow<List<ChatMessage>> =
            ChatViewModel::class.java
                .getDeclaredField("pendingMessages")
                .apply { isAccessible = true }
                .get(vm) as MutableStateFlow<List<ChatMessage>>

        fun outgoingRows(vm: ChatViewModel): List<ChatMessage> = pending(vm).value.filter { it.isOutgoing && it.peerAddress == peer }

        /** A Tunnel send while the contact's relay key is unknown: queued, a `tunnel-wait-` row. */
        suspend fun queue(
            vm: ChatViewModel,
            text: String,
        ) {
            assertTrue(vm.sendMessage(peer, text), "fixture: the Tunnel send was refused, state ${HandshakeHarness.sendMessageState(vm)}")
            awaitSettled(vm)
        }

        /** The user switches the chat to Shielded (free here: no relay key, so only the pending mode sync is armed). */
        suspend fun switchToShielded(vm: ChatViewModel) {
            vm.changeConversationMode(peer, ConversationMode.VAULT)
            awaitSettled(vm)
            assertEquals(ConversationMode.VAULT, base.mode, "fixture: the switch did not take")
            assertTrue(peer in explicit, "fixture: the switch is not the user's pin")
        }

        /** The contact's signed ZBOOT (v3, our stored E2E identity of the contact), carrying its relay key. */
        suspend fun inboundBoot(
            vm: ChatViewModel,
            epoch: Long,
        ) {
            val signed = ZBootMessage.signedDataFor(PaidChatFixture.CONV_ID, relayKey, RelayKeyBinding.RELAY, epoch)
            val boot =
                ZBootMessage(
                    convId = PaidChatFixture.CONV_ID,
                    senderNostrPubkeyHex = relayKey,
                    relayUrl = RelayKeyBinding.RELAY,
                    signature = E2EEncryption.sign(identity.privateKey, signed),
                    epoch = epoch,
                    version = 3,
                ).serialize()
            invoke(vm, "routeIncomingBoot", peer, boot)
            awaitSettled(vm)
            assertEquals(relayKey, pubkeys[peer], "fixture: the ZBOOT was not accepted")
        }

        /** A start of the app: the stored rows are read again (loadPendingMessagesFromPrefs). */
        suspend fun rehydrate(vm: ChatViewModel) {
            invoke(vm, "loadPendingMessagesFromPrefs")
            awaitSettled(vm)
        }

        /** A `tunnel-wait-` row as renderTunnelWaitingBubble stores it. */
        fun seedTunnelWaitRow(
            id: String,
            text: String,
        ) {
            proxy.addPendingMessage(
                ZchatPreferences.PendingMessageData(
                    id = id,
                    text = text,
                    timestampMillis = System.currentTimeMillis() - 60_000,
                    peerAddress = peer,
                    isOutgoing = true,
                    isPending = true,
                    status = MessageStatus.SENDING.name,
                    submitTracked = true,
                ),
            )
        }

        /** A photo link whose file key is wrapped with today's shared key (its Retry can be priced, SR-A). */
        fun photoLine(vm: ChatViewModel): String {
            val shared = vm.getE2ESharedKey(peer) ?: fail("fixture sanity: no shared key")
            val wrapped = Base64.getEncoder().encodeToString(E2EEncryption.wrapFileKey(E2EEncryption.generateFileKey(), shared))
            val sha = "%064x".format(n.toLong() * 0x51ed27L + 0xabcdefL)
            return ZFILEMessage(
                hash = sha.take(32),
                type = ZFILEType.JPEG,
                size = 4_096L,
                url = "https://nostr.download/$sha",
                wrappedKey = wrapped,
                blurhash = "",
            ).serialize()
        }

        /** The FAILED row the switch left for [text]. */
        fun switchedRow(
            vm: ChatViewModel,
            text: String,
        ): ChatMessage {
            val row = outgoingRows(vm).singleOrNull { it.text == text } ?: fail("no row for the queued text: ${outgoingRows(vm)}")
            assertEquals(MessageStatus.FAILED, row.status, "the queued text's row is not FAILED: $row")
            return row
        }

        /** Each of [texts] is one FAILED row with the reason, shown and stored under a fresh `nostr-out-` id. */
        fun assertSwitchedRows(
            vm: ChatViewModel,
            texts: List<String>,
        ) {
            val reason = switchedReason()
            for (text in texts) {
                val row = switchedRow(vm, text)
                assertEquals(reason, row.failureReason, "the row does not say why it was not sent")
                assertTrue(row.id.startsWith("nostr-out-"), "the row is not under a fresh relay-row id: ${row.id}")
                val stored = storedRows().singleOrNull { it.id == row.id } ?: fail("the FAILED row is not stored: ${storedRows()}")
                assertEquals(MessageStatus.FAILED.name, stored.status, "the stored row is not FAILED")
                assertEquals(reason, stored.failureReason, "the stored row lost its reason")
                assertEquals(text, stored.text, "the stored row lost its text")
                assertFalse(stored.isPending, "the stored row is still pending")
            }
            assertFalse(storedRows().any { it.id.startsWith("tunnel-wait-") }, "a tunnel-wait row is left on disk")
            assertFalse(outgoingRows(vm).any { it.id.startsWith("tunnel-wait-") }, "a tunnel-wait row is still shown")
        }

        /** The row [id] is shown and stored FAILED with [text] and the reason. */
        fun assertRowKept(
            vm: ChatViewModel,
            id: String,
            text: String,
        ) {
            val shown = outgoingRows(vm).singleOrNull { it.id == id } ?: fail("the row is gone from the chat: ${outgoingRows(vm)}")
            assertEquals(MessageStatus.FAILED, shown.status, "the kept row is not FAILED")
            assertEquals(text, shown.text, "the kept row lost its text")
            val stored = storedRows().singleOrNull { it.id == id } ?: fail("the row is gone from the store: ${storedRows()}")
            assertEquals(MessageStatus.FAILED.name, stored.status, "the stored row is not FAILED")
            assertEquals(text, stored.text, "the stored row lost its text")
            assertEquals(switchedReason(), stored.failureReason, "the stored row lost its reason")
        }

        private fun invoke(
            vm: ChatViewModel,
            name: String,
            vararg args: Any,
        ): Any? {
            val method =
                ChatViewModel::class.java.declaredMethods.firstOrNull { it.name == name && it.parameterCount == args.size }
                    ?: throw AssertionError("ChatViewModel has no $name(${args.size} args)")
            method.isAccessible = true
            return try {
                method.invoke(vm, *args)
            } catch (e: InvocationTargetException) {
                throw e.targetException
            }
        }

        /** Runs the view model's coroutines until nothing is left, or a question is on screen. */
        suspend fun awaitSettled(vm: ChatViewModel) {
            val deadline = System.currentTimeMillis() + 20_000
            while (true) {
                pumpMain()
                if (HandshakeHarness.sendMessageState(vm).javaClass.simpleName == QUESTION) return
                if (vm.viewModelScope.coroutineContext.job.children
                        .none { it.isActive }
                ) {
                    break
                }
                if (System.currentTimeMillis() > deadline) {
                    fail("the view model did not settle (state ${HandshakeHarness.sendMessageState(vm)})")
                }
                delay(5)
            }
            HandshakeHarness.awaitIdle(vm)
        }
    }
}
