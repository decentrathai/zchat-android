package co.electriccoin.zcash.ui.screen.chat.viewmodel

import cash.z.ecc.android.bip39.Mnemonics
import cash.z.ecc.android.bip39.toSeed
import cash.z.ecc.android.sdk.fixture.WalletFixture
import cash.z.ecc.android.sdk.model.PersistableWallet
import cash.z.ecc.android.sdk.model.SeedPhrase
import co.electriccoin.zcash.ui.common.provider.PersistableWalletProvider
import co.electriccoin.zcash.ui.nostr.NOSTRIdentity
import co.electriccoin.zcash.ui.nostr.NostrChatBridge
import co.electriccoin.zcash.ui.nostr.RelayCopyMarksApi
import co.electriccoin.zcash.ui.screen.chat.crypto.E2EEncryption
import co.electriccoin.zcash.ui.screen.chat.crypto.ratchet.E2EMessageProcessor
import co.electriccoin.zcash.ui.screen.chat.crypto.ratchet.InMemoryRatchetStateStore
import co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferences
import co.electriccoin.zcash.ui.screen.chat.model.ChatListState
import co.electriccoin.zcash.ui.screen.chat.model.ChatMessage
import co.electriccoin.zcash.ui.screen.chat.model.ConversationMode
import co.electriccoin.zcash.ui.screen.chat.model.ZBootMessage
import co.electriccoin.zcash.ui.screen.chat.viewmodel.HandshakeHarness.chatViewModelWith
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * ZC-420 (fix-all 2.13.0, train T6b; ZC-420-SPEC §3.4, §4.2): the fixture of the relay-identity repair tests.
 *
 * [RelayIdentityChat] is PaidRelayFallbacksKeepTheirCountTest's ZbootChat (copied; that test is not edited): a Tunnel chat
 * with [PEER], keys both ways, the contact's relay key known and the standing automatic-setup consent given, over
 * [AutoSpendPrefs], with a few more answers a repair reads: the conversation id (without creating one), our rotation
 * index, the contact list of a rotation, and switches for the contact's relay key and conversation id. [IdentityRelayNet] is the
 * relay publisher and the kept-wrap resender the service installs on [NostrChatBridge], scripted per call. The marks
 * are an in-memory `RelayCopyMarks` reached by name ([RelayCopyMarksApi]). Every key is made per run; every address is
 * synthetic.
 */
internal object RelayIdentity {
    const val PEER = "usyntheticzc420relayidentitypeer01"
    const val PEER_RELAY_KEY = "4d2b8f1e6a3c9d7b5e0f2a8c4b6d1e9f3a7c5b2d8e0f4a6c9b1d3e7f5a2c8b4d"
    const val HOUR_MS = 3_600_000L

    /** Our relay key (x-only, hex) at rotation index [index], from the fixture wallet's seed. */
    fun ourRelayKey(index: Int): String {
        val seed =
            Mnemonics
                .MnemonicCode(SeedPhrase.new(WalletFixture.Alice.seedPhrase).joinToString())
                .toSeed()
        return NOSTRIdentity.fromSeed(seed, index).publicKey.joinToString("") { "%02x".format(it) }
    }

    /** The ChatViewModel's public repair of the chat-open effect (reached by name: it is new in ZC-420). */
    fun repairAtChatOpen(
        vm: ChatViewModel,
        peer: String = PEER,
    ) {
        val method =
            ChatViewModel::class.java.methods.firstOrNull { it.name == "repairUnconfirmedRelayIdentity" && it.parameterCount == 1 }
                ?: throw AssertionError("ChatViewModel.repairUnconfirmedRelayIdentity(peer) does not exist (ZC-420)")
        try {
            method.invoke(vm, peer)
        } catch (e: InvocationTargetException) {
            throw e.targetException
        }
    }

    /** A new block at [height]: onChainTip(Long?), as observeBlockHeight calls it. */
    fun tick(
        vm: ChatViewModel,
        height: Long,
    ) {
        try {
            ChatViewModel::class.java
                .getDeclaredMethod("onChainTip", Long::class.javaObjectType)
                .apply { isAccessible = true }
                .invoke(vm, height)
        } catch (e: InvocationTargetException) {
            throw e.targetException
        }
    }

    /** A memo as a ZBOOT, or a failure that names what was published instead. */
    fun zboot(memo: String): ZBootMessage =
        ZBootMessage.parse(memo) ?: throw AssertionError("the published memo is no ZBOOT: ${memo.take(12)}…")

    /** Whether [boot] verifies under our E2E public key for [PEER] in [chat]. */
    fun verifiesUnderOurKey(
        chat: RelayIdentityChat,
        boot: ZBootMessage,
    ): Boolean {
        val ours = chat.prefs.ourKeys.getValue(PEER)
        return E2EEncryption.verify(ours.publicKey, boot.signedData(), boot.signature)
    }
}

/** The relay publisher and the kept-wrap resender, as NostrChatBridge hooks; every call recorded. */
internal class IdentityRelayNet {
    data class Published(
        val memo: String,
        val to: String,
    )

    val published: MutableList<Published> = CopyOnWriteArrayList()
    val resendAsks: MutableList<String> = CopyOnWriteArrayList()

    /** The answer to the next publishes: (shared, rumor id, readable). */
    @Volatile var answer: Triple<Int, String, Int> = Triple(1, "synthetic-rumor", 1)

    /** While set, a publish waits for it (a publish whose relay answer has not come yet). */
    @Volatile var hold: CompletableDeferred<Unit>? = null

    /** The kept-wrap resender's answer per rumor id; null: nothing kept (as after a restart). */
    @Volatile var resender: ((String) -> Triple<Int, String, Int>?)? = null

    fun result(answer: Triple<Int, String, Int>): NostrChatBridge.PublishResult =
        NostrChatBridge.PublishResult::class.java.constructors
            .first { it.parameterCount == 3 }
            .newInstance(answer.first, answer.second, answer.third) as NostrChatBridge.PublishResult

    fun install() {
        NostrChatBridge.registerPublisher { memo, to ->
            published += Published(memo, to)
            hold?.await()
            result(answer)
        }
        NostrChatBridge.registerKeptResender { id, _ ->
            resendAsks += id
            resender?.invoke(id)?.let(::result)
        }
    }

    fun uninstall() {
        NostrChatBridge.unregisterPublisher()
        NostrChatBridge.unregisterKeptResender()
    }
}

/** A wallet provider whose seed is not ready (the wallet is still loading). */
internal object SeedNotReadyWalletProvider : PersistableWalletProvider {
    override val persistableWallet: Flow<PersistableWallet?> = flow { }

    override suspend fun store(persistableWallet: PersistableWallet) = Unit

    override suspend fun getPersistableWallet(): PersistableWallet? = null

    override suspend fun requirePersistableWallet(): PersistableWallet = throw IllegalStateException("synthetic: the seed is not ready")
}

/**
 * A Tunnel chat with [RelayIdentity.PEER]: keys both ways, the contact's relay key known, the standing automatic-setup
 * consent given (so a ZBOOT no relay takes goes on-chain, as today). [marks] is the in-memory mark store, registered on
 * the bridge by [registerMarks] when the build has marks.
 */
internal class RelayIdentityChat(
    mode: ConversationMode = ConversationMode.TUNNEL,
) {
    val prefs = AutoSpendPrefs(consent = true).apply { session(RelayIdentity.PEER, complete = true, mode = mode) }
    val seam = HandshakeSeam(RelayIdentity.PEER)
    val zbootQuoted: MutableSet<String> = ConcurrentHashMap.newKeySet()
    val peerRelayKey = AtomicReference<String?>(RelayIdentity.PEER_RELAY_KEY)
    val conversationKnown = AtomicBoolean(true)
    val conversationCreates = AtomicInteger()
    val rotation = AtomicInteger(0)
    val marks = RelayCopyMarksApi.MemoryMarks()

    val store: ZchatPreferences =
        interceptRelayIdentityStore(
            prefs.proxy,
            mapOf(
                "hasAcknowledgedMessageCost" to { _ -> true },
                "getPeerNostrPubkey" to { a -> if (a!![0] == RelayIdentity.PEER) peerRelayKey.get() else null },
                "isZbootQuoted" to { a -> a!![0] in zbootQuoted },
                "setZbootQuoted" to { a ->
                    if (a!![1] as Boolean) zbootQuoted += a[0] as String else zbootQuoted -= a[0] as String
                    true
                },
                "getConversationId" to { a ->
                    if (a!![0] == RelayIdentity.PEER && conversationKnown.get()) prefs.convIdFor(RelayIdentity.PEER) else null
                },
                // FC-ID (ZC-892): our KEX is built only under an own-made id; this fixture's id is that id.
                "ownConversationId" to { a -> prefs.convIdFor(a!![0] as String) },
                "ownConversationIdForStart" to { a -> prefs.convIdFor(a!![0] as String) },
                "getOrCreateConversationIdForMessage" to { a ->
                    conversationCreates.incrementAndGet()
                    prefs.convIdFor(a!![0] as String) to false
                },
                "getOrCreateConversationId" to { a ->
                    conversationCreates.incrementAndGet()
                    prefs.convIdFor(a!![0] as String) to false
                },
                "getAllPeerToConvIdMappings" to { _ -> mapOf(RelayIdentity.PEER to prefs.convIdFor(RelayIdentity.PEER)) },
                "getNostrRotationIndex" to { _ -> rotation.get() },
                "setNostrRotationIndex" to { a ->
                    rotation.set(a!![0] as Int)
                    null
                },
            ),
        )

    /** Registers [marks] on the bridge; false when this build has no relay-copy marks (before ZC-420). */
    fun registerMarks(): Boolean {
        if (!RelayCopyMarksApi.available()) return false
        RelayCopyMarksApi.register(marks)
        return true
    }

    /** The identity slot of [RelayIdentity.PEER] as the build names it. */
    fun slot(): String = RelayCopyMarksApi.Slots.identity(RelayIdentity.PEER)

    /** The delivered-identity marker. */
    fun marker(): String? = prefs.sentNostrBoot[RelayIdentity.PEER]

    suspend fun viewModel(): ChatViewModel {
        val vm = chatViewModelWith(store, seam).also(AutoSpendVm::wireBootstrapFields)
        val convId = prefs.convIdFor(RelayIdentity.PEER)
        val processors = ConcurrentHashMap<String, E2EMessageProcessor>()
        processors["${RelayIdentity.PEER}:$convId"] = E2EMessageProcessor(ByteArray(32) { 7 }, convId, true, InMemoryRatchetStateStore())
        AutoSpendVm.setField(vm, "messageProcessors", processors)
        AutoSpendVm.setField(vm, "decryptedTextCache", ConcurrentHashMap<String, String>())
        AutoSpendVm.setField(vm, "pendingMessages", MutableStateFlow(emptyList<ChatMessage>()))
        AutoSpendVm.setField(vm, "messageQueue", mutableListOf<Any?>())
        AutoSpendVm.setField(vm, "_chatListState", MutableStateFlow<ChatListState>(ChatListState.Loading))
        AutoSpendVm.setField(vm, "_showCostDisclaimer", MutableStateFlow(false))
        AutoSpendVm.setField(vm, "_blockHeight", MutableStateFlow<Long?>(null))
        AutoSpendVm.setField(vm, "addressCache", NoOpAddressCache.proxy)
        return vm
    }
}

/** [delegate] with the calls named in [handlers] answered by them instead (PaidRelayFallbacksKeepTheirCountTest's). */
internal fun interceptRelayIdentityStore(
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
