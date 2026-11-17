package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.nostr.NostrChatBridge
import co.electriccoin.zcash.ui.screen.chat.crypto.ratchet.E2EMessageProcessor
import co.electriccoin.zcash.ui.screen.chat.crypto.ratchet.InMemoryRatchetStateStore
import co.electriccoin.zcash.ui.screen.chat.datasource.SelfAddressRegistry
import co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferences
import co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferencesImpl
import co.electriccoin.zcash.ui.screen.chat.datasource.realPreferences
import co.electriccoin.zcash.ui.screen.chat.model.ChatListState
import co.electriccoin.zcash.ui.screen.chat.model.ChatMessage
import co.electriccoin.zcash.ui.screen.chat.model.ConversationMode
import co.electriccoin.zcash.ui.screen.chat.model.SendMessageState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/**
 * OT213n B1a (ZC-1478, ZC-1480, ZC-1472, ZC-1473) — one phone with one contact, [peer], driven through the REAL
 * ChatViewModel (HandshakeHarness: the real use case, repository and settlement seam).
 *
 * The store is split in two:
 *  - the handshake state (our and the contact's E2E keys, the standing automatic-setup consent, the bootstrap markers,
 *    the paid-setup records) is [AutoSpendPrefs], the fixture the automatic-spend tests use;
 *  - EVERY other call goes to the REAL [ZchatPreferencesImpl] over in-memory files ([realPreferences]): the chat mode and
 *    its pin, the contact's relay key, the mode-change "since" records, the pending mode prompts and deliveries, the
 *    Ironwood capability record, the stored rows and system notes, the message-cost notice. A store call the fix adds
 *    (for example our own last mode-change time, or a "moved to Shielded by the contact" mark) is therefore kept by the
 *    production store, whatever its name, as long as it is kept in a preferences file. With [realConversationIds] the
 *    conversation-id calls go to the real store too (the INIT-not-sent mark of R2-6 c); with [allReal] every call does.
 *
 * [calls] records every store call by name, [published] every relay publish (the relays acknowledge each with one
 * relay unless [holdPublishes] holds them). Every address and key is synthetic.
 */
internal class RealModeStorePhone(
    val peer: String,
    mode: ConversationMode,
    consent: Boolean = true,
    exchangeComplete: Boolean = false,
    ourKey: Boolean = exchangeComplete,
    relayKey: String? = null,
    private val realConversationIds: Boolean = false,
    // Everything, the handshake state included, in the real store (a key the send makes is then read back).
    private val allReal: Boolean = false,
    val seam: HandshakeSeam = HandshakeSeam(peer),
) {
    val real: ZchatPreferencesImpl = realPreferences().first
    val auto = AutoSpendPrefs(consent = consent)
    val calls: MutableList<String> = Collections.synchronizedList(mutableListOf())
    val published: MutableList<Pair<String, String>> = Collections.synchronizedList(mutableListOf())

    /** While set, a relay publish waits for it (the relays have not answered yet). */
    @Volatile var holdPublishes: CompletableDeferred<Unit>? = null

    init {
        // realPreferences() leaves this lazy delegate unset (no own addresses here; none is this contact).
        ZchatPreferencesImpl::class.java
            .getDeclaredField("selfAddresses\$delegate")
            .apply { isAccessible = true }
            .set(real, lazyOf(SelfAddressRegistry(MemorySharedPreferences())))
        if (ourKey && !allReal) auto.session(peer, complete = exchangeComplete, mode = mode)
        real.setConversationMode(peer, mode)
        relayKey?.let { real.setPeerNostrPubkey(peer, it) }
        real.setAcknowledgedMessageCost()
    }

    private val handshakeCalls =
        setOf(
            "hasConsentedToHandshakeSpend",
            "setConsentedToHandshakeSpend",
            "clearConsentedToHandshakeSpend",
            "getE2EOurPublicKey",
            "getE2EPrivateKey",
            "getE2EPeerPublicKey",
            "setE2EPeerPublicKey",
            "storePeerKey",
            "isE2EKeyExchangeComplete",
            "isE2EEnabled",
            "isOwnBootSent",
            "setOwnBootSent",
            "getSentNostrBootPubkey",
            "setSentNostrBootPubkey",
            "getHandshakePaidRecord",
            "setHandshakePaidRecord",
            "getNostrRotationIndex",
            "resolvePeerAddress",
        )

    private val conversationIdCalls =
        setOf(
            "getOrCreateConversationId",
            "getOrCreateConversationIdForMessage",
            "ownConversationId",
            "ownConversationIdForStart",
            "getPeerByConversationId",
        )

    val store: ZchatPreferences =
        Proxy.newProxyInstance(
            ZchatPreferences::class.java.classLoader,
            arrayOf(ZchatPreferences::class.java),
        ) { p, method, args ->
            when (method.name) {
                "toString" -> "RealModeStorePhone"
                "hashCode" -> System.identityHashCode(p)
                "equals" -> p === args?.get(0)
                else -> {
                    calls += method.name
                    val toAuto =
                        !allReal &&
                            (method.name in handshakeCalls || (!realConversationIds && method.name in conversationIdCalls))
                    try {
                        method.invoke(if (toAuto) auto.proxy else real, *(args ?: emptyArray()))
                    } catch (e: InvocationTargetException) {
                        throw e.targetException
                    }
                }
            }
        } as ZchatPreferences

    /** The conversation id the handshake half uses for [peer] (when [realConversationIds] is off). */
    val convId: String get() = auto.convIdFor(peer)

    fun mode(): ConversationMode = real.getConversationMode(peer)

    /** The system notes stored for [peer] (incoming, not pending rows; the chat shows them as notes). */
    fun notes(): List<ZchatPreferences.PendingMessageData> =
        real.getPendingMessages().filter { it.peerAddress == peer && !it.isOutgoing && !it.isPending }

    /** The relays: reachable, each publish acknowledged by one relay (after [holdPublishes], when set). */
    fun relaysUp() {
        NostrChatBridge.unregisterPublisher()
        NostrChatBridge.registerPublisher { memo, to ->
            published += memo to to
            holdPublishes?.await()
            NostrChatBridge.PublishResult(1, "synthetic-ot213n-relay-${published.size}")
        }
    }

    suspend fun viewModel(): ChatViewModel {
        val vm = HandshakeHarness.chatViewModelWith(store, seam).also(AutoSpendVm::wireBootstrapFields)
        val processors = ConcurrentHashMap<String, E2EMessageProcessor>()
        if (auto.complete.contains(peer)) {
            processors["$peer:$convId"] = E2EMessageProcessor(ByteArray(32) { 7 }, convId, true, InMemoryRatchetStateStore())
        }
        AutoSpendVm.setField(vm, "messageProcessors", processors)
        AutoSpendVm.setField(vm, "decryptedTextCache", ConcurrentHashMap<String, String>())
        AutoSpendVm.setField(vm, "pendingMessages", MutableStateFlow(emptyList<ChatMessage>()))
        AutoSpendVm.setField(vm, "messageQueue", mutableListOf<Any?>())
        AutoSpendVm.setField(vm, "_chatListState", MutableStateFlow<ChatListState>(ChatListState.Loading))
        AutoSpendVm.setField(vm, "_showCostDisclaimer", MutableStateFlow(false))
        AutoSpendVm.setField(vm, "_blockHeight", MutableStateFlow<Long?>(null))
        AutoSpendVm.setField(vm, "replyRefSentinel", '\u0001')
        AutoSpendVm.setField(vm, "replyRefPrefix", "\u0001RPL:")
        AutoSpendVm.setField(vm, "replyPreviewSep", '\u001f')
        AutoSpendVm.setField(vm, "addressCache", NoOpAddressCache.proxy)
        return vm
    }

    companion object {
        /** A private ChatViewModel handler taking (peer address, memo), e.g. handleModeControl. */
        fun invoke(
            vm: ChatViewModel,
            name: String,
            peer: String,
            memo: String,
        ) {
            val method =
                ChatViewModel::class.java
                    .getDeclaredMethod(name, String::class.java, String::class.java)
                    .apply { isAccessible = true }
            try {
                method.invoke(vm, peer, memo)
            } catch (e: InvocationTargetException) {
                throw e.targetException
            }
        }

        fun state(vm: ChatViewModel): SendMessageState = HandshakeHarness.sendMessageState(vm)
    }
}
