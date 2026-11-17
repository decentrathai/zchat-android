package co.electriccoin.zcash.ui.screen.chat.viewmodel

import androidx.lifecycle.viewModelScope
import co.electriccoin.zcash.ui.nostr.NostrChatBridge
import co.electriccoin.zcash.ui.screen.chat.crypto.ratchet.E2EMessageProcessor
import co.electriccoin.zcash.ui.screen.chat.crypto.ratchet.InMemoryRatchetStateStore
import co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferences
import co.electriccoin.zcash.ui.screen.chat.model.ChatListState
import co.electriccoin.zcash.ui.screen.chat.model.ChatMessage
import co.electriccoin.zcash.ui.screen.chat.model.ConversationMode
import co.electriccoin.zcash.ui.screen.chat.model.ZMSGProtocol
import co.electriccoin.zcash.ui.screen.chat.model.onChainMemoCountFor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.util.Base64
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/**
 * W-72 (register ZC-847, ZC-855), SPEC_F_20261019 rule R4 with SPEC_DECISIONS D4, D5 and D8, RED test 10 and the send half
 * of test 11: the next on-chain ordinary message to a contact whose key carries a pre-update mark goes out as the v4 INIT
 * under the probe id N (the store's startLegacyProbe: made once, then reused), `peer:` unchanged, and its transaction is
 * recorded with the probe; only when the INIT form needs as many memos as the plain form, so the price question the user
 * confirmed is the price paid (the total is the same as without a mark). A message whose INIT form needs one more memo goes
 * as today and makes no probe. A Tunnel message over the relays never carries N and makes none. With an unanswered reply id
 * from the contact (replyconv:, set by the contact's own probe) the send answers under that id first, as a plain reply, and
 * marks it answered; our own probe waits for the next send. An unmarked chat is unchanged.
 *
 * Today every message goes under the chat's own id. Every address, key and id is synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LegacyProbeRidesOnTheNextOnChainSendTest {
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

    private val sendScopes = SendHarnessScopes()

    @Before
    fun setUp() {
        sendScopes.open()
        Dispatchers.setMain(main)
        AutoSpendVm.resetProcessState()
        AutoSpendVm.setHistoryRead(true)
        NostrChatBridge.unregisterPublisher()
    }

    @After
    fun tearDown() {
        NostrChatBridge.unregisterPublisher()
        sendScopes.close()
        Dispatchers.resetMain()
        AutoSpendVm.resetProcessState()
    }

    @Test
    fun `(10) a marked chat's next on-chain message is the INIT under the probe id at today's price, and the id is reused`() =
        runBlocking {
            val control = ProbeChat(n = 1, marked = false)
            val controlVm = control.viewModel()
            val controlTotal = control.send(controlVm, "hello")
            val controlMemo = control.memosOf(0).single()
            assertTrue(
                "fixture: the unmarked chat's message is not a reply under its id",
                controlMemo.startsWith("ZMSG|v4|${PaidChatFixture.CONV_ID}|")
            )
            assertFalse("fixture: the unmarked chat sent an INIT", controlMemo.contains("|INIT|"))
            assertTrue("an unmarked chat made a probe", control.probeStarts.isEmpty())

            val chat = ProbeChat(n = 2, marked = true)
            val vm = chat.viewModel()
            val total = chat.send(vm, "hello")

            val probe = chat.probe ?: throw AssertionError("no probe id was made for the marked chat")
            assertNotEquals("the probe went under the chat's own id", PaidChatFixture.CONV_ID, probe)
            val memo = chat.memosOf(0).single()
            assertTrue("the probe is not the v4 INIT under its id: ${memo.take(40)}", memo.startsWith("ZMSG|v4|$probe|INIT|"))
            assertEquals("the probe pays other outputs or amounts than the same message without a mark (D8)", controlTotal, total)
            assertEquals("one message, one proposal", 1, chat.proposals())
            assertTrue("the probe's transaction was not recorded with it", chat.probeTxIds.isNotEmpty())
            assertTrue("peer: was moved", chat.peerWrites.isEmpty())

            chat.send(vm, "hello again")
            val second = chat.memosOf(1).single()
            assertTrue("the second probe send did not reuse the id (D4)", second.startsWith("ZMSG|v4|$probe|INIT|"))
            assertEquals("a second id was made", 1, chat.probeStarts.toSet().size)
        }

    @Test
    fun `(10) a message whose INIT form needs one more memo goes as today and makes no probe`() =
        runBlocking {
            val chat = ProbeChat(n = 3, marked = true)
            val vm = chat.viewModel()
            val text = textWhoseInitNeedsOneMoreMemo()

            chat.send(vm, text)

            assertTrue("a probe was made for a message whose INIT form costs more", chat.probeStarts.isEmpty())
            val memos = chat.memosOf(0)
            assertTrue("the message did not go under the chat's own id", memos.first().contains(PaidChatFixture.CONV_ID))
            assertFalse("the message went as an INIT", memos.first().contains("|INIT|"))
        }

    @Test
    fun `(10) a Tunnel message over the relays never carries the probe id and makes none`() =
        runBlocking {
            val chat = ProbeChat(n = 4, marked = true, mode = ConversationMode.TUNNEL)
            val published = Collections.synchronizedList(mutableListOf<String>())
            NostrChatBridge.registerPublisher { content, _ ->
                published += content
                NostrChatBridge.PublishResult(acks = 1, messageId = "synthetic-w72-relay")
            }
            val vm = chat.viewModel()

            vm.sendMessage(chat.peer, "over the relays")
            chat.settle(vm)

            assertTrue("fixture: nothing went over the relays", published.isNotEmpty())
            assertTrue("a relay send made a probe", chat.probeStarts.isEmpty())
            assertEquals("a relay send was proposed on-chain", 0, chat.proposals())
        }

    @Test
    fun `(11) an unanswered reply id is answered first as a plain reply, then our own probe goes`() =
        runBlocking {
            val chat = ProbeChat(n = 5, marked = true)
            chat.reply = THEIR_PROBE
            val vm = chat.viewModel()

            chat.send(vm, "answering your probe")

            val memo = chat.memosOf(0).single()
            assertTrue("the answer did not go under the contact's probe id", memo.startsWith("ZMSG|v4|$THEIR_PROBE|"))
            assertFalse("the answer went as an INIT", memo.contains("|INIT|"))
            assertTrue("the reply id was not marked answered", chat.answered)
            assertTrue("our own probe went before the answer (D4)", chat.probeStarts.isEmpty())

            chat.send(vm, "and now ours")
            val ours = chat.memosOf(1).single()
            val probe = chat.probe ?: throw AssertionError("no probe after the answer")
            assertTrue("our probe did not follow the answer", ours.startsWith("ZMSG|v4|$probe|INIT|"))
        }

    @Test
    fun `(11) an unmarked chat answers an unanswered reply id too, and only once`() =
        runBlocking {
            val chat = ProbeChat(n = 6, marked = false)
            chat.reply = THEIR_PROBE
            val vm = chat.viewModel()

            chat.send(vm, "one")
            chat.send(vm, "two")

            assertTrue(chat.memosOf(0).single().startsWith("ZMSG|v4|$THEIR_PROBE|"))
            assertTrue("an answered reply id was used again", chat.memosOf(1).single().startsWith("ZMSG|v4|${PaidChatFixture.CONV_ID}|"))
        }

    /** A text whose INIT form needs one more memo than the plain (encrypted) form, in the middle of that range. */
    private fun textWhoseInitNeedsOneMoreMemo(): String {
        val differ =
            (100..2_000).filter { len ->
                val t = "x".repeat(len)
                onChainMemoCountFor(t, encrypted = true, isInit = true, self = HandshakeHarness.SELF) >
                    onChainMemoCountFor(t, encrypted = true, isInit = false, self = HandshakeHarness.SELF)
            }
        assertTrue("fixture: no length where the INIT costs a memo more", differ.isNotEmpty())
        val first = differ.first()
        val run = differ.takeWhile { it - first == differ.indexOf(it) }
        return "x".repeat(run[run.size / 2])
    }

    private inner class ProbeChat(
        n: Int,
        val marked: Boolean,
        mode: ConversationMode = ConversationMode.VAULT,
    ) {
        val peer: String = SyntheticUnifiedAddress.mainnet(72_300 + n)
        val base = PaidChatFixture(peer, mode)
        private val relayKey = "%064x".format(n.toLong() * 0x9e3779b1L + 0x1234567L)

        @Volatile var probe: String? = null
        val probeStarts: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val probeTxIds: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val peerWrites: MutableList<String> = Collections.synchronizedList(mutableListOf())

        @Volatile var reply: String? = null

        @Volatile var answered = false

        val proxy: ZchatPreferences =
            Proxy.newProxyInstance(ZchatPreferences::class.java.classLoader, arrayOf(ZchatPreferences::class.java)) { _, method, args ->
                val a = args ?: emptyArray()
                when (method.name) {
                    "getLegacyKeyMark" -> if (marked) "c3ludGhldGljLW1hcmtlZC1rZXk" else null
                    "getLegacyProbe" -> if (marked) probe else null
                    "startLegacyProbe" ->
                        if (marked) {
                            val id = probe ?: ZMSGProtocol.generateConversationId().also { probe = it }
                            probeStarts += id
                            id
                        } else {
                            null
                        }
                    "addLegacyProbeTx" -> {
                        if (a[1] == probe) probeTxIds += a[2] as String
                        a[1] == probe
                    }
                    "getReplyConversationId" -> reply?.takeIf { !answered }
                    "answerReplyConversationId" -> {
                        if (a[1] == reply) answered = true
                        true
                    }
                    "getPeerByConversationId" ->
                        if (a[0] == probe || a[0] == reply) peer else delegate(method, a)
                    "setConversationId" -> {
                        peerWrites += a[1] as String
                        null
                    }
                    "getPeerNostrPubkey" -> if (mode != ConversationMode.VAULT && a[0] == peer) relayKey else null
                    "findPeerByNostrPubkey" -> if (a[0] == relayKey) peer else null
                    else -> delegate(method, a)
                }
            } as ZchatPreferences

        private fun delegate(
            method: java.lang.reflect.Method,
            a: Array<Any?>,
        ): Any? =
            try {
                method.invoke(base.proxy, *a)
            } catch (e: InvocationTargetException) {
                throw e.targetException
            }

        suspend fun viewModel(): ChatViewModel {
            val vm = HandshakeHarness.chatViewModelWith(proxy, base.seam).also(AutoSpendVm::wireBootstrapFields)
            AutoSpendVm.setField(vm, "decryptedTextCache", ConcurrentHashMap<String, String>())
            AutoSpendVm.setField(vm, "pendingMessages", kotlinx.coroutines.flow.MutableStateFlow(emptyList<ChatMessage>()))
            AutoSpendVm.setField(vm, "messageQueue", mutableListOf<Any?>())
            AutoSpendVm.setField(vm, "_chatListState", kotlinx.coroutines.flow.MutableStateFlow<ChatListState>(ChatListState.Loading))
            AutoSpendVm.setField(vm, "_showCostDisclaimer", kotlinx.coroutines.flow.MutableStateFlow(false))
            AutoSpendVm.setField(vm, "_blockHeight", kotlinx.coroutines.flow.MutableStateFlow<Long?>(null))
            // One root for every id of the chat (the ratchet's floors are kept per root): a processor for any id asked.
            val ratchets = InMemoryRatchetStateStore()
            val processors =
                object : ConcurrentHashMap<String, E2EMessageProcessor>() {
                    override fun get(key: String): E2EMessageProcessor? =
                        super.get(key) ?: if (key.startsWith("$peer:")) {
                            computeIfAbsent(key) { E2EMessageProcessor(ByteArray(32) { 7 }, key.substringAfter(':'), true, ratchets) }
                        } else {
                            null
                        }
                }
            AutoSpendVm.setField(vm, "messageProcessors", processors)
            AutoSpendVm.setField(vm, "replyRefSentinel", '\u0001')
            AutoSpendVm.setField(vm, "replyRefPrefix", "\u0001RPL:")
            AutoSpendVm.setField(vm, "replyPreviewSep", '\u001f')
            return vm
        }

        /**
         * The user sends [text] (a message to a contact with keys goes at once; a price question, if one shows, is confirmed).
         * Returns what the proposal pays: its ZIP 321 request with the memos left out (every output's address and amount).
         */
        suspend fun send(
            vm: ChatViewModel,
            text: String,
        ): String {
            val before = proposals()
            vm.sendMessage(peer, text)
            settle(vm)
            if (HandshakeHarness.sendMessageState(vm).javaClass.simpleName == "NeedsPaidActionConfirmation") {
                vm.confirmPaidAction()
                settle(vm)
            }
            assertEquals("fixture: the message was not proposed once", before + 1, proposals())
            val uri = synchronized(base.seam.proposalUris) { base.seam.proposalUris.last() }
            return uri
                .substringAfter('?')
                .split('&')
                .filterNot { it.substringBefore('=').let { k -> k == "memo" || k.startsWith("memo.") } }
                .joinToString("&")
        }

        suspend fun settle(vm: ChatViewModel) {
            val deadline = System.currentTimeMillis() + 20_000
            while (System.currentTimeMillis() < deadline) {
                pumpMain()
                if (vm.viewModelScope.coroutineContext.job.children
                        .none { it.isActive }
                ) {
                    break
                }
                kotlinx.coroutines.delay(5)
            }
            HandshakeHarness.awaitIdle(vm)
        }

        fun proposals(): Int = base.seam.proposalRequests.get()

        /** The memos of proposal [index], decoded from its ZIP 321 request (the platform-fee output carries none). */
        fun memosOf(index: Int): List<String> {
            val uri =
                synchronized(base.seam.proposalUris) { base.seam.proposalUris.getOrNull(index) }
                    ?: throw AssertionError("no proposal $index (${base.seam.proposalUris.size} made)")
            return uri
                .substringAfter('?')
                .split('&')
                .filter { it.substringBefore('=').let { k -> k == "memo" || k.startsWith("memo.") } }
                .map { String(Base64.getUrlDecoder().decode(it.substringAfter('=')), Charsets.UTF_8) }
        }
    }

    private companion object {
        const val THEIR_PROBE = "ZC72THR1"
    }
}
