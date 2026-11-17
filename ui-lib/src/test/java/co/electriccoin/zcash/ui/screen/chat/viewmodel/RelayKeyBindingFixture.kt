package co.electriccoin.zcash.ui.screen.chat.viewmodel

import androidx.lifecycle.viewModelScope
import cash.z.ecc.android.sdk.Synchronizer
import cash.z.ecc.android.sdk.model.TransactionId
import cash.z.ecc.android.sdk.type.AddressType
import co.electriccoin.zcash.ui.NavigationCommand
import co.electriccoin.zcash.ui.NavigationRouter
import co.electriccoin.zcash.ui.common.model.SynchronizerError
import co.electriccoin.zcash.ui.common.provider.SynchronizerProvider
import co.electriccoin.zcash.ui.common.repository.Transaction
import co.electriccoin.zcash.ui.common.repository.TransactionRepository
import co.electriccoin.zcash.ui.common.repository.ZashiProposalRepositoryImpl
import co.electriccoin.zcash.ui.common.usecase.GetDefaultUnifiedAddressUseCase
import co.electriccoin.zcash.ui.common.usecase.GetSelectedWalletAccountUseCase
import co.electriccoin.zcash.ui.common.usecase.PrefillZchatUseCase
import co.electriccoin.zcash.ui.common.usecase.ValidateAddressUseCase
import co.electriccoin.zcash.ui.common.usecase.Zc101FakeAccountDataSource
import co.electriccoin.zcash.ui.common.usecase.Zc101FakeKeystoneProposalRepository
import co.electriccoin.zcash.ui.common.usecase.Zc101FakeZashiSpendingKeyDataSource
import co.electriccoin.zcash.ui.common.usecase.Zc101RecordingNavigationRouter
import co.electriccoin.zcash.ui.common.usecase.Zc101RecordingProposalDataSource
import co.electriccoin.zcash.ui.common.usecase.Zc101SuspendingBiometricRepository
import co.electriccoin.zcash.ui.common.usecase.syntheticZashiAccount
import co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferences
import co.electriccoin.zcash.ui.screen.chat.model.Contact
import co.electriccoin.zcash.ui.screen.chat.model.ContactBook
import co.electriccoin.zcash.ui.screen.chat.model.ConversationMode
import co.electriccoin.zcash.ui.screen.chat.model.ZchatComposeState
import co.electriccoin.zcash.ui.screen.chat.usecase.CreateChunkedMessageProposalUseCase
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import java.lang.reflect.Proxy
import java.util.Collections
import kotlin.reflect.KClass

/**
 * M1 (fix-all 2.13.0, train T4b; money SPEC §1): one relay (NOSTR) key, one address. Shared pieces of the M1 tests: an
 * in-memory preferences store that keeps the relay-key bindings, modes, E2E keys and stored rows the binding and
 * routing paths read, and a real [ZchatComposeVM] over it. New names (the notice on the New Chat state, the own relay
 * key registry) are read by reflection or answered by name, so the tests compile before the fix. Every address, key
 * and id is synthetic.
 */
internal class RelayKeyStore {
    /** address → relay key, in insertion order (the fake's reverse lookup returns the first match). */
    val pubkeys: MutableMap<String, String> = Collections.synchronizedMap(LinkedHashMap())
    val relays: MutableMap<String, String?> = Collections.synchronizedMap(LinkedHashMap())
    val modes: MutableMap<String, ConversationMode> = Collections.synchronizedMap(LinkedHashMap())
    val explicitModes: MutableSet<String> = Collections.synchronizedSet(LinkedHashSet())
    val e2eOurKeys: MutableMap<String, String> = Collections.synchronizedMap(LinkedHashMap())
    val e2ePeerKeys: MutableMap<String, String> = Collections.synchronizedMap(LinkedHashMap())
    val selfAddresses: MutableSet<String> = Collections.synchronizedSet(LinkedHashSet())
    val ownRelayKeys: MutableSet<String> = Collections.synchronizedSet(LinkedHashSet())
    val nicknames: MutableMap<String, String> = Collections.synchronizedMap(LinkedHashMap())
    val rows: MutableMap<String, ZchatPreferences.PendingMessageData> = Collections.synchronizedMap(LinkedHashMap())
    val seen: MutableSet<String> = Collections.synchronizedSet(LinkedHashSet())

    /** Every write, as "method:firstArg[:secondArg]". */
    val writes: MutableList<String> = Collections.synchronizedList(mutableListOf())

    fun wrote(
        method: String,
        address: String
    ): Boolean = writes.any { it.startsWith("$method:$address") }

    val proxy: ZchatPreferences =
        Proxy.newProxyInstance(
            ZchatPreferences::class.java.classLoader,
            arrayOf(ZchatPreferences::class.java),
        ) { p, method, args ->
            fun arg(i: Int) = args!![i]

            fun str(i: Int) = args!![i] as String
            when (method.name) {
                "toString" -> "RelayKeyStore"
                "hashCode" -> System.identityHashCode(p)
                "equals" -> p === arg(0)
                "getPeerNostrPubkey" -> pubkeys[str(0)]
                "setPeerNostrPubkey" -> {
                    writes += "setPeerNostrPubkey:${str(0)}:${arg(1)}"
                    val pk = arg(1) as String?
                    if (pk == null) pubkeys.remove(str(0)) else pubkeys[str(0)] = pk
                    Unit
                }
                "getPeerNostrRelay" -> relays[str(0)]
                "setPeerNostrRelay" -> {
                    writes += "setPeerNostrRelay:${str(0)}:${arg(1)}"
                    relays[str(0)] = arg(1) as String?
                    Unit
                }
                "findPeerByNostrPubkey" ->
                    synchronized(pubkeys) { pubkeys.entries.firstOrNull { it.value.equals(str(0), ignoreCase = true) }?.key }
                "resolvePeerAddress" -> str(0)
                "isSelfAddress" -> str(0) in selfAddresses
                "isOwnNostrPubkey" -> ownRelayKeys.any { it.equals(str(0), ignoreCase = true) }
                "setE2EKeyChanged" -> {
                    writes += "setE2EKeyChanged:${str(0)}:${arg(1)}"
                    Unit
                }
                "setE2EVerified" -> {
                    writes += "setE2EVerified:${str(0)}:${arg(1)}"
                    Unit
                }
                "getConversationModeOrNull" -> modes[str(0)]
                "getConversationMode" -> modes[str(0)] ?: ConversationMode.VAULT
                "setConversationMode" -> {
                    writes += "setConversationMode:${str(0)}:${arg(1)}"
                    val mode = arg(1) as ConversationMode?
                    if (mode == null) {
                        modes.remove(str(0))
                        explicitModes.remove(str(0))
                    } else {
                        modes[str(0)] = mode
                    }
                    Unit
                }
                "isConversationModeExplicit" -> str(0) in explicitModes
                "setConversationModeExplicit" -> {
                    writes += "setConversationModeExplicit:${str(0)}"
                    explicitModes += str(0)
                    Unit
                }
                "getE2EOurPublicKey" -> e2eOurKeys[str(0)]
                "getE2EPeerPublicKey" -> e2ePeerKeys[str(0)]
                "getNickname" -> nicknames[str(0)]
                "addPendingMessage", "commitPendingMessage" -> {
                    val row = arg(0) as ZchatPreferences.PendingMessageData
                    rows[row.id] = row
                    Unit
                }
                "removePendingMessage" -> {
                    rows.remove(str(0))
                    Unit
                }
                "getPendingMessages" -> synchronized(rows) { rows.values.toList() }
                "hasSeenNostrEvent" -> str(0) in seen
                "markNostrEventSeen" -> {
                    seen += str(0)
                    Unit
                }
                "unmarkNostrEventSeen" -> {
                    seen -= str(0)
                    Unit
                }
                "hasAcknowledgedMessageCost" -> true
                "hasExistingChat" -> false
                "needsConversationInit" -> true
                "getOrCreateConversationId", "getOrCreateConversationIdForMessage" -> Pair("SYNTHM1C", true)
                // FC-ID (ZC-892): our KEX is built only under an id this phone made for the address; this fixture's id is that id.
                "ownConversationId", "ownConversationIdForStart" -> "SYNTHM1C"
                "getPeerKnownOurRotationIndex" -> -1
                else -> unset(method.returnType)
            }
        } as ZchatPreferences

    private fun unset(type: Class<*>): Any? =
        when (type) {
            java.lang.Boolean.TYPE -> false
            Integer.TYPE -> 0
            java.lang.Long.TYPE -> 0L
            java.lang.Float.TYPE -> 0f
            java.lang.Double.TYPE -> 0.0
            List::class.java -> emptyList<Any>()
            Set::class.java -> emptySet<Any>()
            Map::class.java -> emptyMap<Any, Any>()
            else -> null
        }
}

internal object RelayKeyBinding {
    /** Our own chat address (synthetic, a valid Bech32m UA shape). */
    val SELF: String = SyntheticUnifiedAddress.mainnet(7_001)
    const val RELAY = "wss://relay.synthetic.example"

    /** A synthetic x-only relay key: 64 lowercase hex characters, deterministic in [n]. */
    fun relayKey(n: Int): String = "%064x".format(n.toLong() * 0x9e3779b1L + 0x1234567L)

    /** A contact code in the Invite Friend form. */
    fun code(
        address: String,
        relayKey: String,
        relay: String = RELAY,
    ): String = "zchat:c1?z=$address&n=$relayKey&r=${java.net.URLEncoder.encode(relay, "UTF-8")}"

    /** The notice on the New Chat state (M1), or null when the state has none (or no such field before the fix). */
    fun notice(state: ZchatComposeState.Ready): Any? =
        runCatching {
            state.javaClass
                .getDeclaredField("codeNotice")
                .apply { isAccessible = true }
                .get(state)
        }.getOrNull()

    fun noticeKind(state: ZchatComposeState.Ready): String? = notice(state)?.javaClass?.simpleName

    fun noticeField(
        state: ZchatComposeState.Ready,
        name: String,
    ): Any? =
        notice(state)?.let { n ->
            runCatching {
                n.javaClass
                    .getDeclaredField(name)
                    .apply { isAccessible = true }
                    .get(n)
            }.getOrNull()
        }

    fun ZchatComposeVM.ready(): ZchatComposeState.Ready =
        state.value as? ZchatComposeState.Ready ?: error("VM state was ${state.value}, expected Ready")

    /** A real New Chat screen over [store]: our address is [SELF], the SDK calls every other address valid. */
    fun composeVm(
        store: RelayKeyStore,
        contacts: List<Contact> = emptyList(),
        navigationRouter: NavigationRouter = Zc101RecordingNavigationRouter(),
        prefillZchat: PrefillZchatUseCase = PrefillZchatUseCase(),
    ): ZchatComposeVM {
        val accounts = Zc101FakeAccountDataSource(kotlinx.coroutines.runBlocking { syntheticZashiAccount() })
        val synchronizerProvider = AnsweringSynchronizerProvider(SELF)
        val getSelectedWalletAccount = GetSelectedWalletAccountUseCase(accounts)
        return ZchatComposeVM(
            contactBook = ListContactBook(contacts),
            getSelectedWalletAccount = getSelectedWalletAccount,
            getDefaultUnifiedAddress = GetDefaultUnifiedAddressUseCase(synchronizerProvider, getSelectedWalletAccount),
            createChunkedMessageProposal =
                CreateChunkedMessageProposalUseCase(
                    keystoneProposalRepository = Zc101FakeKeystoneProposalRepository(),
                    zashiProposalRepository =
                        ZashiProposalRepositoryImpl(
                            accountDataSource = accounts,
                            proposalDataSource = Zc101RecordingProposalDataSource(),
                            zashiSpendingKeyDataSource = Zc101FakeZashiSpendingKeyDataSource(),
                        ),
                    accountDataSource = accounts,
                    navigationRouter = Zc101RecordingNavigationRouter(),
                    biometricRepository = Zc101SuspendingBiometricRepository(),
                ),
            navigationRouter = navigationRouter,
            prefillZchat = prefillZchat,
            transactionRepository = EmptyTransactions,
            zchatPreferences = store.proxy,
            validateAddress = ValidateAddressUseCase(synchronizerProvider),
        )
    }

    fun close(vm: ZchatComposeVM) {
        vm.viewModelScope.cancel()
        // A cancelled screen still resumes on Main while it ends; resetting Main first leaks into another test.
        kotlinx.coroutines.runBlocking {
            kotlinx.coroutines.withTimeoutOrNull(5_000L) {
                vm.viewModelScope.coroutineContext[kotlinx.coroutines.Job]?.join()
            }
        }
    }

    /** Answers the Synchronizer calls the New Chat and scan screens make: our address, and "valid" for any other. */
    class AnsweringSynchronizerProvider(
        private val self: String,
    ) : SynchronizerProvider {
        private val answering =
            Proxy.newProxyInstance(
                Synchronizer::class.java.classLoader,
                arrayOf(Synchronizer::class.java),
            ) { proxy, method, args ->
                when (method.name) {
                    "toString" -> "M1AnsweringSynchronizer"
                    "hashCode" -> System.identityHashCode(proxy)
                    "equals" -> proxy === args!![0]
                    "getUnifiedAddress" -> self
                    "validateAddress" -> AddressType.Unified
                    else -> throw UnsupportedOperationException("unexpected Synchronizer call: ${method.name}")
                }
            } as Synchronizer

        override val error: StateFlow<SynchronizerError?> = MutableStateFlow(null)
        override val synchronizer: StateFlow<Synchronizer?> = MutableStateFlow(answering)

        override suspend fun getSynchronizer(): Synchronizer = answering

        override fun resetSynchronizer() = Unit
    }

    object EmptyTransactions : TransactionRepository {
        override val transactions: Flow<List<Transaction>?> = flowOf(null)

        override suspend fun getMemos(transaction: Transaction): List<String> = emptyList()

        override fun observeTransaction(txId: String): Flow<Transaction?> = flowOf(null)

        override fun observeTransactionsByMemo(memo: String): Flow<List<TransactionId>?> = flowOf(null)

        override suspend fun getTransactions(): List<Transaction> = emptyList()

        override suspend fun getAllRecipientAddresses(transaction: Transaction): List<String> = emptyList()
    }

    class ListContactBook(
        private val contacts: List<Contact>,
    ) : ContactBook {
        override fun addContact(contact: Contact) = Unit

        override fun removeContact(address: String) = Unit

        override fun getContact(address: String): Contact? = contacts.firstOrNull { it.address == address }

        override fun getAllContacts(): List<Contact> = contacts

        override fun hasContact(address: String): Boolean = contacts.any { it.address == address }

        override fun updateContactName(
            address: String,
            newName: String
        ) = Unit

        override fun clearAll() = Unit
    }

    /** Records every route passed to replace, and counts the rest. */
    class RecordingRouter : NavigationRouter {
        val replaced: MutableList<Any> = Collections.synchronizedList(mutableListOf())
        val backs: MutableList<String> = Collections.synchronizedList(mutableListOf())

        override fun forward(vararg routes: Any) = Unit

        override fun replace(vararg routes: Any) {
            replaced.addAll(routes)
        }

        override fun replaceAll(vararg routes: Any) = Unit

        override fun back() {
            backs += "back"
        }

        override fun backTo(route: KClass<*>) = Unit

        override fun backToRoot() = Unit

        override fun observePipeline(): Flow<NavigationCommand> = emptyFlow()
    }
}

/** A live ChatViewModel over a [RelayKeyStore] (the handshake harness allocation, with a view-model scope). */
internal object RelayKeyChatScreen {
    /** Must run inside an open [SendHarnessScopes] on an unconfined Main dispatcher. */
    suspend fun chatViewModel(store: RelayKeyStore): ChatViewModel {
        val vm = HandshakeHarness.chatViewModelWith(store.proxy, HandshakeSeam(RelayKeyBinding.SELF))
        AutoSpendVm.setField(
            vm,
            "pendingMessages",
            MutableStateFlow<List<co.electriccoin.zcash.ui.screen.chat.model.ChatMessage>>(emptyList())
        )
        // The reply-reference markers the inbound text path reads (field initialisers do not run on this allocation).
        AutoSpendVm.setField(vm, "replyRefSentinel", '\u0001')
        AutoSpendVm.setField(vm, "replyRefPrefix", "\u0001RPL:")
        AutoSpendVm.setField(vm, "replyPreviewSep", '\u001f')
        return vm
    }

    /** Starts one of the ChatViewModel's init readers ("observeNostrInbound", "observeLocalMessages") by name. */
    fun start(
        vm: ChatViewModel,
        reader: String,
    ) {
        ChatViewModel::class.java
            .getDeclaredMethod(reader)
            .apply { isAccessible = true }
            .invoke(vm)
    }

    @Suppress("UNCHECKED_CAST")
    fun rows(vm: ChatViewModel): List<co.electriccoin.zcash.ui.screen.chat.model.ChatMessage> =
        (
            ChatViewModel::class.java
                .getDeclaredField("pendingMessages")
                .apply { isAccessible = true }
                .get(vm) as MutableStateFlow<List<co.electriccoin.zcash.ui.screen.chat.model.ChatMessage>>
        ).value
}
