package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.nostr.NostrChatBridge
import co.electriccoin.zcash.ui.screen.chat.crypto.ratchet.E2EMessageProcessor
import co.electriccoin.zcash.ui.screen.chat.crypto.ratchet.InMemoryRatchetStateStore
import co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferences
import co.electriccoin.zcash.ui.screen.chat.model.ChatListState
import co.electriccoin.zcash.ui.screen.chat.model.ChatMessage
import co.electriccoin.zcash.ui.screen.chat.model.ConversationMode
import co.electriccoin.zcash.ui.screen.chat.view.ChatResourceText
import co.electriccoin.zcash.ui.screen.chat.viewmodel.HandshakeHarness.awaitIdle
import co.electriccoin.zcash.ui.screen.chat.viewmodel.HandshakeHarness.chatViewModelWith
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.util.concurrent.ConcurrentHashMap

/**
 * ZC-393 (fix-all 2.13.0, train T5b; t5b-add SPECS §2.1 "five callers", §2.3 item 6, §2.6, residual R-1): the stricter
 * relay count never starts an on-chain spend.
 *
 * Five relay sends fall back to a paid on-chain send when no relay took them: announceRotationOverNostr, the ZBOOT
 * relay-first block of sendNostrBootHandshake, ChatViewModel.trySendGroupMemoOverNostr, sendGroupControlMemo and
 * GroupViewModel.trySendGroupMemoOverNostr. When `PublishResult.acks` becomes "relays every ZChat version reads", these
 * five must keep deciding on any readable relay (`readableAcks`), or an outage of ZChat's relay and nos.lol would turn a
 * free relay delivery into a ~17,000 zat spend. Wanted: the five read readableAcks and no other publish result under
 * screen/chat/viewmodel decides on it (publishNostrAndRenderLocal and sendPaymentRequest read it only to word a FAILED
 * row); with a publisher answering (acks 0, readableAcks 1) the ZBOOT relay-first path and a group control memo make 0
 * proposals. Controls (pass before and after): the same paths with (acks 0, readableAcks 0) pay on-chain, as today.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PaidRelayFallbacksKeepTheirCountTest {
    private val sendScopes = SendHarnessScopes()

    private companion object {
        const val PEER = "usyntheticpaidfallbackcount01"
        const val PEER_RELAY_KEY = "3c9e1a7f5b2d8e4a6c0f9b3d7e1a5c8f2b6d0e4a8c3f7b1d5e9a2c6f0b4d8e1a"
        const val CVM = "ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/chat/viewmodel/ChatViewModel.kt"
        const val GVM = "ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/chat/viewmodel/GroupViewModel.kt"
        const val ZCVM = "ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/chat/viewmodel/ZchatComposeVM.kt"
        val FIVE =
            listOf(
                CVM to "announceRotationOverNostr",
                CVM to "sendNostrBootHandshake",
                CVM to "trySendGroupMemoOverNostr",
                CVM to "sendGroupControlMemo",
                GVM to "trySendGroupMemoOverNostr",
            )
        val WORDING_ONLY = setOf("publishNostrAndRenderLocal", "sendPaymentRequest")
        val MEMBER_FUN =
            Regex(
                """\n    (?:(?:private|internal|public|override|suspend|inline)\s+)*fun\s+(?:<[^>]*>\s*)?([A-Za-z0-9_]+)\s*\(""",
            )
    }

    @Before
    fun setUp() {
        sendScopes.open()
        Dispatchers.setMain(UnconfinedTestDispatcher())
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

    // ── the source ──────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `the five paid fallbacks decide on readableAcks, and no other publish result does`() {
        val problems = mutableListOf<String>()
        FIVE.forEach { (path, name) ->
            val body = functionBody(ChatResourceText.source(path), name)
            val file = path.substringAfterLast('/')
            if ("readableAcks" !in body) problems += "$file $name does not read readableAcks"
            if (Regex("""\.acks\b""").containsMatchIn(body)) problems += "$file $name still reads .acks"
        }
        listOf(CVM, GVM, ZCVM).forEach { path ->
            functions(ChatResourceText.source(path))
                .filter { (_, body) -> "readableAcks" in body }
                .map { it.first }
                .filter { name -> FIVE.none { it.second == name } && name !in WORDING_ONLY }
                .forEach { problems += "${path.substringAfterLast('/')} $it reads readableAcks" }
        }
        assertTrue(problems.joinToString("\n"), problems.isEmpty())
    }

    // ── the ZBOOT relay-first path ──────────────────────────────────────────────────────────────────

    @Test
    fun `a ZBOOT only a public relay took is delivered free, no proposal`() =
        runBlocking {
            val chat = ZbootChat()
            publisher(acks = 0, readable = 1)
            val vm = chat.viewModel()

            vm.ensureNostrBootstrapSent(PEER)
            awaitIdle(vm)

            assertEquals("the stricter count started an on-chain ZBOOT", 0, chat.seam.proposalRequests.get())
            assertTrue("our relay identity was not marked delivered", chat.prefs.sentNostrBoot[PEER] != null)
        }

    @Test
    fun `control - a ZBOOT no relay took falls back on-chain as today`() =
        runBlocking {
            val chat = ZbootChat()
            NostrChatBridge.registerPublisher { _, _ -> NostrChatBridge.PublishResult(0, "synthetic-zboot") }
            val vm = chat.viewModel()

            vm.ensureNostrBootstrapSent(PEER)
            awaitIdle(vm)

            assertEquals(1, chat.seam.proposalRequests.get())
        }

    // ── a group control memo (the GROUP_KEY_REQUEST grant) ──────────────────────────────────────────

    @Test
    fun `a group control memo only a public relay took is delivered free, no proposal`() =
        runBlocking {
            val fixture = GroupGrantFixture(member = "usyntheticpaidfallbackgrant1", consent = true)
            val seam = HandshakeSeam(fixture.member)
            publisher(acks = 0, readable = 1)
            val vm = chatViewModelWith(withRelayKey(fixture), seam)

            fixture.scan(vm, fixture.txId(1))
            awaitIdle(vm)

            assertEquals("the stricter count started an on-chain grant", 0, seam.proposalRequests.get())
            assertFalse("the grant was not recorded as delivered", fixture.grantedRequests.isEmpty())
        }

    @Test
    fun `control - a group control memo no relay took falls back on-chain as today`() =
        runBlocking {
            val fixture = GroupGrantFixture(member = "usyntheticpaidfallbackgrant2", consent = true)
            val seam = HandshakeSeam(fixture.member)
            NostrChatBridge.registerPublisher { _, _ -> NostrChatBridge.PublishResult(0, "synthetic-grant") }
            val vm = chatViewModelWith(withRelayKey(fixture), seam)

            fixture.scan(vm, fixture.txId(2))
            awaitIdle(vm)

            assertEquals(1, seam.proposalRequests.get())
        }

    // --------------------------------------------------------------- harness

    /** Every publish answered (acks, id, readableAcks) through PublishResult's 3-argument constructor (by name). */
    private fun publisher(
        acks: Int,
        readable: Int,
    ) {
        val constructor =
            NostrChatBridge.PublishResult::class.java.constructors.firstOrNull { it.parameterCount == 3 }
                ?: throw AssertionError("PublishResult has no readableAcks (ZC-393): (acks, messageId) only")
        NostrChatBridge.registerPublisher { _, _ ->
            constructor.newInstance(acks, "synthetic-relay-event", readable) as NostrChatBridge.PublishResult
        }
    }

    private fun functions(source: String): List<Pair<String, String>> {
        val starts = MEMBER_FUN.findAll(source).toList()
        return starts.mapIndexed { i, m ->
            val end = if (i + 1 < starts.size) starts[i + 1].range.first else source.length
            m.groupValues[1] to source.substring(m.range.first, end)
        }
    }

    private fun functionBody(
        source: String,
        name: String,
    ): String =
        // Every overload of [name] (sendNostrBootHandshake has two; the relay-first block is in the second).
        functions(source)
            .filter { it.first == name }
            .joinToString("\n") { it.second }
            .ifEmpty { throw AssertionError("fixture: no function $name") }

    /** [fixture]'s store, with the member's relay key known (the NOSTR-first branch of sendGroupControlMemo). */
    private fun withRelayKey(fixture: GroupGrantFixture): ZchatPreferences =
        interceptStore(fixture.proxy, mapOf("getPeerNostrPubkey" to { a -> if (a!![0] == fixture.member) PEER_RELAY_KEY else null }))

    /**
     * A Tunnel chat with [PEER]: keys both ways, their relay key known, the standing automatic-setup consent given and a
     * ZBOOT allowance quoted (as ModeSwitchAsksBeforeEveryPaymentTest's chat), so a ZBOOT may go on-chain when no relay
     * takes it.
     */
    private class ZbootChat {
        val prefs = AutoSpendPrefs(consent = true).apply { session(PEER, complete = true, mode = ConversationMode.TUNNEL) }
        val seam = HandshakeSeam(PEER)
        private val zbootQuoted: MutableSet<String> = ConcurrentHashMap.newKeySet<String>().apply { add(PEER) }

        private val store: ZchatPreferences =
            interceptStore(
                prefs.proxy,
                mapOf(
                    "hasAcknowledgedMessageCost" to { _ -> true },
                    "getPeerNostrPubkey" to { a -> if (a!![0] == PEER) PEER_RELAY_KEY else null },
                    "isZbootQuoted" to { a -> a!![0] in zbootQuoted },
                    "setZbootQuoted" to { a ->
                        if (a!![1] as Boolean) zbootQuoted += a[0] as String else zbootQuoted -= a[0] as String
                        true
                    },
                ),
            )

        suspend fun viewModel(): ChatViewModel {
            val vm = chatViewModelWith(store, seam).also(AutoSpendVm::wireBootstrapFields)
            val convId = prefs.convIdFor(PEER)
            val processors = ConcurrentHashMap<String, E2EMessageProcessor>()
            processors["$PEER:$convId"] = E2EMessageProcessor(ByteArray(32) { 7 }, convId, true, InMemoryRatchetStateStore())
            AutoSpendVm.setField(vm, "messageProcessors", processors)
            AutoSpendVm.setField(vm, "decryptedTextCache", ConcurrentHashMap<String, String>())
            AutoSpendVm.setField(vm, "pendingMessages", MutableStateFlow(emptyList<ChatMessage>()))
            AutoSpendVm.setField(vm, "messageQueue", mutableListOf<Any?>())
            AutoSpendVm.setField(vm, "_chatListState", MutableStateFlow<ChatListState>(ChatListState.Loading))
            AutoSpendVm.setField(vm, "_showCostDisclaimer", MutableStateFlow(false))
            AutoSpendVm.setField(vm, "_blockHeight", MutableStateFlow<Long?>(null))
            return vm
        }
    }
}

/** [delegate] with the calls named in [handlers] answered by them instead. */
private fun interceptStore(
    delegate: ZchatPreferences,
    handlers: Map<String, (Array<Any?>?) -> Any?>,
): ZchatPreferences =
    Proxy.newProxyInstance(
        ZchatPreferences::class.java.classLoader,
        arrayOf(ZchatPreferences::class.java),
    ) { _, method, args ->
        val handler = handlers[method.name]
        if (handler != null) {
            handler(args)
        } else {
            try {
                method.invoke(delegate, *(args ?: emptyArray()))
            } catch (e: InvocationTargetException) {
                throw e.targetException
            }
        }
    } as ZchatPreferences
