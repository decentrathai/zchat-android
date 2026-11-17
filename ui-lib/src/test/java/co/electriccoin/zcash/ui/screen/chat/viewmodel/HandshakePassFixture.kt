package co.electriccoin.zcash.ui.screen.chat.viewmodel

import androidx.lifecycle.viewModelScope
import cash.z.ecc.android.sdk.Synchronizer
import cash.z.ecc.android.sdk.model.BlockHeight
import cash.z.ecc.android.sdk.model.TransactionId
import cash.z.ecc.android.sdk.model.Zatoshi
import co.electriccoin.zcash.ui.common.model.SynchronizerError
import co.electriccoin.zcash.ui.common.provider.SynchronizerProvider
import co.electriccoin.zcash.ui.common.repository.ReceiveTransaction
import co.electriccoin.zcash.ui.common.repository.SendTransaction
import co.electriccoin.zcash.ui.common.repository.Transaction
import co.electriccoin.zcash.ui.common.repository.TransactionRepository
import co.electriccoin.zcash.ui.screen.chat.crypto.E2EEncryption
import co.electriccoin.zcash.ui.screen.chat.crypto.E2EKeyPair
import co.electriccoin.zcash.ui.screen.chat.datasource.ConversationIdStoreFixture
import co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferences
import co.electriccoin.zcash.ui.screen.chat.model.AddressCache
import co.electriccoin.zcash.ui.screen.chat.model.ChatListState
import co.electriccoin.zcash.ui.screen.chat.model.ChatMessage
import co.electriccoin.zcash.ui.screen.chat.model.Contact
import co.electriccoin.zcash.ui.screen.chat.model.ContactBook
import co.electriccoin.zcash.ui.screen.chat.model.ZMSGProtocol
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.job
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.withTimeout
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * ZC-599 test support: the REAL history pass (convertToConversations) of a [HandshakeHarness] ViewModel, over
 * transactions a test builds. The handshake state lives in [prefs] ([HandshakePrefs]: keys, holds, the budget, the
 * processed marks, the answers paid), the conversation ids in the REAL store functions ([ids]), payments go through the
 * harness's settlement seam ([seam]). Heights: the chain tip is [TIP], above the upgrade height of the network table
 * (mainnet 3,428,143), so a transaction mined at [NEW] + n is "new" and one at [OLD] + n is "old". [pass] runs the pass
 * twice over the same list by default, as a complete history scan: a mined key exchange is acted on at a stable pass
 * (KR-2). Every address, key,
 * id and txid is synthetic.
 */
internal class HandshakePassFixture(
    /** The test's virtual clock (the Main dispatcher's): the pass's own delayed re-runs are advanced, never waited for. */
    private val scheduler: TestCoroutineScheduler,
    peer: String,
    firstContact: Boolean = true,
    consent: Boolean = true,
    private val saved: Set<String> = emptySet(),
) {
    val ids = ConversationIdStoreFixture()
    val prefs = HandshakePrefs(peer, consent = consent, firstContact = firstContact).also { it.selfAddresses += SELF }
    val seam = HandshakeSeam(peer)
    private val height = MutableStateFlow<BlockHeight?>(BlockHeight.new(TIP))
    private val txs = mutableListOf<Transaction>()
    private val memos = ConcurrentHashMap<String, String>()
    private val recipients = ConcurrentHashMap<String, String>()
    lateinit var vm: ChatViewModel

    /**
     * ZC-634 part B: calls answered before the stores below ([ClaimMarkMaps.handle]: the claim mark, the relay binding,
     * the processed ZBOOTs); [ClaimMarkMaps.UNHANDLED] hands a call on as before. Null: none.
     */
    @Volatile var extra: ((java.lang.reflect.Method, Array<Any?>) -> Any?)? = null

    suspend fun open(): ChatViewModel {
        vm = HandshakeHarness.chatViewModelWith(prefs.proxy, seam)
        PaymentHarness.setField(vm, "zchatPreferences", routed())
        PaymentHarness.setField(vm, "synchronizerProvider", provider)
        PaymentHarness.setField(vm, "contactBook", contacts())
        PaymentHarness.setField(vm, "transactionRepository", repository())
        PaymentHarness.setField(vm, "addressCache", PassMapCache())
        PaymentHarness.setField(vm, "decryptedTextCache", ConcurrentHashMap<String, String>())
        PaymentHarness.setField(vm, "decryptFailedPeers", ConcurrentHashMap.newKeySet<String>())
        PaymentHarness.setField(vm, "hiddenMessages", MutableStateFlow<Set<String>>(emptySet()))
        PaymentHarness.setField(vm, "peerStatuses", MutableStateFlow<Map<String, Any>>(emptyMap()))
        PaymentHarness.setField(vm, "unlockedMessages", MutableStateFlow<Map<String, String>>(emptyMap()))
        // What the constructor gives the pass (as PaymentHarness.paymentViewModel does).
        PaymentHarness.setField(vm, "_chatListState", MutableStateFlow<ChatListState>(ChatListState.Loading))
        PaymentHarness.setField(vm, "_showCostDisclaimer", MutableStateFlow(false))
        PaymentHarness.setField(vm, "_blockHeight", MutableStateFlow<Long?>(null))
        PaymentHarness.setField(vm, "pendingMessages", MutableStateFlow<List<ChatMessage>>(emptyList()))
        PaymentHarness.setField(vm, "paidRequestIds", MutableStateFlow(emptySet<String>()))
        PaymentHarness.setField(vm, "unsavedPaidMarks", ConcurrentHashMap.newKeySet<String>())
        PaymentHarness.setField(vm, "messageQueue", mutableListOf<Any>())
        PaymentHarness.setField(vm, "messageProcessors", ConcurrentHashMap<String, Any>())
        PaymentHarness.setField(vm, "replyRefSentinel", '\u0001')
        PaymentHarness.setField(vm, "replyRefPrefix", "\u0001RPL:")
        PaymentHarness.setField(vm, "replyPreviewSep", '\u001f')
        // The pass's delayed re-run bumps this counter (the screen's collector re-runs the pass; none runs here).
        PaymentHarness.setField(vm, "restoreRecheck", MutableStateFlow(0))
        return vm
    }

    /** ZC-1082: the addresses the user's contact book holds (getAllContacts); none by default. */
    val book: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** ZC-599 (6.3 n): the labels of transactions whose memo cannot be read (the repository throws for them). */
    val unreadable: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** ZC-1167 (ZC-757 (k) round 15): the labels of own sends whose outputs cannot be read (getAllRecipientAddresses throws). */
    val unreadableOutputs: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** ZC-1121: the labels of transactions the user hid (the pass's hiddenMsgIds); none by default. */
    val hidden: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** ZC-599 (6.3 o): moves the chain tip (the fully scanned height) to [height]. */
    fun tip(height: Long) {
        this.height.value = BlockHeight.new(height)
    }

    /** The synthetic txid of the transaction labelled [label] (as [received] and [sent] make it). */
    fun txid(label: String): String = txidOf(label)

    /** A received transaction carrying [memo], mined at [minedAt] (null: broadcast, not mined). */
    fun received(
        label: String,
        memo: String,
        minedAt: Long?,
    ) {
        val id = TransactionId.new(txidOf(label))
        memos[id.txIdString()] = memo
        txs +=
            ReceiveTransaction.Success(
                id = id,
                amount = Zatoshi(1_000),
                timestamp = timeOf(minedAt),
                memoCount = 1,
                transactionOutputs = emptyList(),
                overview = ScanPassHarness.overview(id, minedAt, timeOf(minedAt), sent = false, amount = 1_000),
                recipient = null,
            )
    }

    /** One of this wallet's own sends carrying [memo] to [to], mined at [minedAt]. */
    fun sent(
        label: String,
        memo: String,
        to: String,
        minedAt: Long?,
    ) {
        val id = TransactionId.new(txidOf(label))
        memos[id.txIdString()] = memo
        recipients[id.txIdString()] = to
        txs +=
            SendTransaction.Success(
                id = id,
                amount = Zatoshi(1_000),
                timestamp = timeOf(minedAt),
                memoCount = 1,
                fee = Zatoshi(10_000),
                transactionOutputs = emptyList(),
                overview = ScanPassHarness.overview(id, minedAt, timeOf(minedAt), sent = true, amount = 1_000),
                recipient = null,
            )
    }

    /**
     * ZC-1082: one of this wallet's own sends as a restored wallet lists it: every output by the text the wallet library
     * gives it ([outputs], in that order, e.g. the fee's receiver form first), and the history's recipient pick
     * ([recipient], TransactionRepositoryImpl.getRecipient; null: none).
     */
    fun sentRestored(
        label: String,
        memo: String,
        outputs: List<String>,
        recipient: String?,
        minedAt: Long?,
    ) {
        val id = TransactionId.new(txidOf(label))
        memos[id.txIdString()] = memo
        restoredOutputs[id.txIdString()] = outputs
        txs +=
            SendTransaction.Success(
                id = id,
                amount = Zatoshi(1_000),
                timestamp = timeOf(minedAt),
                memoCount = 1,
                fee = Zatoshi(10_000),
                transactionOutputs = emptyList(),
                overview = ScanPassHarness.overview(id, minedAt, timeOf(minedAt), sent = true, amount = 1_000),
                recipient = recipient?.let { unifiedWalletAddress(it) },
            )
    }

    /** ZC-1082: our own key exchange under [convId], made for [keyFor], as a restored wallet lists its [outputs]. */
    fun ownKexRestored(
        label: String,
        keyFor: String,
        outputs: List<String>,
        convId: String,
        minedAt: Long?,
    ) {
        val ours = prefs.ourKeyStore[keyFor]?.let { E2EKeyPair(it.first, it.second) } ?: E2EEncryption.generateKeyPair()
        prefs.ourKeyStore[keyFor] = ours.publicKey to ours.privateKey
        val payload = E2EEncryption.createKEXPayload(SELF, ours.publicKey, ours.privateKey)
        sentRestored(label, ZMSGProtocol.createV4KEXMessage(convId, SELF, payload), outputs, recipient = null, minedAt = minedAt)
    }

    private val restoredOutputs = ConcurrentHashMap<String, List<String>>()

    private fun unifiedWalletAddress(address: String): cash.z.ecc.android.sdk.model.WalletAddress =
        cash.z.ecc.android.sdk.model.WalletAddress.Unified::class.java.declaredConstructors
            .single { it.parameterCount == 2 }
            .apply { isAccessible = true }
            .newInstance(address, null) as cash.z.ecc.android.sdk.model.WalletAddress

    /** Our own key exchange to [to] under [convId], with our key pair for [to] (stored in [prefs]). */
    fun ownKex(
        label: String,
        to: String,
        convId: String,
        minedAt: Long?,
    ) {
        val ours = prefs.ourKeyStore[to]?.let { E2EKeyPair(it.first, it.second) } ?: E2EEncryption.generateKeyPair()
        prefs.ourKeyStore[to] = ours.publicKey to ours.privateKey
        val payload = E2EEncryption.createKEXPayload(SELF, ours.publicKey, ours.privateKey)
        sent(label, ZMSGProtocol.createV4KEXMessage(convId, SELF, payload), to, minedAt)
    }

    /**
     * The history pass, [times] times over the same list, then the ViewModel's own jobs. ZC-1129: with [settle] false the
     * jobs a run queued on the Main dispatcher (a StandardTestDispatcher the test set) are not run: they wait for the test.
     */
    suspend fun pass(
        times: Int = 2,
        settle: Boolean = true,
    ) {
        val method =
            ChatViewModel::class.java.declaredMethods
                .single { it.name == "convertToConversations" && it.parameterCount == 7 }
                .apply { isAccessible = true }
        val sorted = txs.sortedBy { it.timestamp ?: Instant.MIN }
        repeat(times) {
            val result =
                kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn<Any?> { continuation ->
                    try {
                        val pending = PaymentHarness.pendingMessages(vm)
                        val hiddenIds = hidden.mapTo(HashSet()) { txidOf(it) }
                        method.invoke(vm, sorted, SELF, hiddenIds, pending, emptySet<String>(), true, continuation)
                    } catch (e: InvocationTargetException) {
                        throw e.targetException
                    }
                }
            // QA2 k1: the conversations the last run produced (null while it was cut off), for tests that read the rows.
            @Suppress("UNCHECKED_CAST")
            lastConversations = result as? List<co.electriccoin.zcash.ui.screen.chat.model.Conversation>
            if (settle) idle()
        }
    }

    /** QA2 k1: what the newest [pass] run returned (the chat list's conversations); null before the first run. */
    @Volatile var lastConversations: List<co.electriccoin.zcash.ui.screen.chat.model.Conversation>? = null

    /** Runs the ViewModel's jobs to their end, advancing the virtual clock (the pass re-runs itself after a delay). */
    suspend fun idle() {
        withTimeout(HandshakeHarness.TIMEOUT_MS) {
            val job = vm.viewModelScope.coroutineContext.job
            while (job.children.any { it.isActive }) {
                scheduler.advanceUntilIdle()
                kotlinx.coroutines.delay(5)
            }
        }
    }

    private fun timeOf(minedAt: Long?): Instant = Instant.ofEpochSecond(1_700_000_000L + ((minedAt ?: (TIP + 1)) - OLD) * 75L)

    private fun txidOf(label: String): String =
        java.security.MessageDigest
            .getInstance("SHA-256")
            .digest(label.toByteArray())
            .joinToString("") { "%02x".format(it) }

    private val synchronizer: Synchronizer =
        Proxy.newProxyInstance(Synchronizer::class.java.classLoader, arrayOf(Synchronizer::class.java)) { p, method, args ->
            when (method.name) {
                "getNetworkHeight", "getFullyScannedHeight" -> height
                "toString" -> "HandshakePassSynchronizer"
                "hashCode" -> System.identityHashCode(p)
                "equals" -> p === args?.get(0)
                else -> throw UnsupportedOperationException("unexpected Synchronizer call: ${method.name}")
            }
        } as Synchronizer

    private val provider: SynchronizerProvider =
        object : SynchronizerProvider {
            override val error = MutableStateFlow<SynchronizerError?>(null)
            override val synchronizer = MutableStateFlow<Synchronizer?>(this@HandshakePassFixture.synchronizer)

            override suspend fun getSynchronizer(): Synchronizer = this@HandshakePassFixture.synchronizer

            override fun resetSynchronizer() = Unit
        }

    private fun routed(): ZchatPreferences =
        Proxy.newProxyInstance(ZchatPreferences::class.java.classLoader, arrayOf(ZchatPreferences::class.java)) { p, method, args ->
            val a = args ?: emptyArray()
            val hook = extra
            if (hook != null) {
                val answered = hook(method, a)
                if (answered !== ClaimMarkMaps.UNHANDLED) return@newProxyInstance answered
            }
            when (method.name) {
                "hashCode" -> System.identityHashCode(p)
                "equals" -> p === a.getOrNull(0)
                "toString" -> "HandshakePassPrefs"
                "getOrPutPendingTxFirstSeenMillis", "getOrPutMessageExpiryAnchorMillis" -> a[1]
                // Every address is its own canonical one here (HandshakePrefs does not answer it).
                "resolvePeerAddress" -> a[0]
                in PASS_CONV_ID_CALLS ->
                    try {
                        method.invoke(ids.store, *a)
                    } catch (e: InvocationTargetException) {
                        throw e.targetException
                    }
                else -> {
                    // HandshakePrefs answers a call it does not know with null (and records it), which its own proxy
                    // cannot hand back for a primitive: the pass's other reads get the type's empty value instead.
                    val value =
                        try {
                            method.invoke(prefs.proxy, *a)
                        } catch (e: InvocationTargetException) {
                            val unknownPrimitive = e.targetException is NullPointerException && method.returnType.isPrimitive
                            if (unknownPrimitive) null else throw e.targetException
                        }
                    value ?: passEmptyValue(method.returnType)
                }
            }
        } as ZchatPreferences

    private fun contacts(): ContactBook =
        Proxy.newProxyInstance(ContactBook::class.java.classLoader, arrayOf(ContactBook::class.java)) { p, method, args ->
            when (method.name) {
                "hasContact" -> args!![0] in saved
                "getContact" -> null
                "getAllContacts" -> book.map { Contact(it, "Saved contact") }
                "hashCode" -> System.identityHashCode(p)
                "equals" -> p === args?.get(0)
                "toString" -> "HandshakePassContacts"
                else -> passEmptyValue(method.returnType)
            }
        } as ContactBook

    private fun repository(): TransactionRepository =
        Proxy.newProxyInstance(
            TransactionRepository::class.java.classLoader,
            arrayOf(TransactionRepository::class.java),
        ) { p, method, args ->
            when (method.name) {
                "getMemos" -> {
                    val id = (args!![0] as Transaction).id.txIdString()
                    if (unreadable.any { txidOf(it) == id }) throw IllegalStateException("synthetic unreadable memo")
                    listOfNotNull(memos[id])
                }
                "getAllRecipientAddresses" -> {
                    val id = (args!![0] as Transaction).id.txIdString()
                    if (unreadableOutputs.any { txidOf(it) == id }) throw IllegalStateException("synthetic unreadable outputs")
                    restoredOutputs[id] ?: listOfNotNull(recipients[id])
                }
                "hashCode" -> System.identityHashCode(p)
                "equals" -> p === args?.get(0)
                "toString" -> "HandshakePassRepository"
                else -> error("unexpected TransactionRepository.${method.name}")
            }
        } as TransactionRepository

    companion object {
        const val SELF = HandshakeHarness.SELF

        /** The chain tip: fully scanned. */
        const val TIP = 3_500_000L

        /** A height above the upgrade height (3,428,143): "new". */
        const val NEW = 3_499_000L

        /** A height at or below the upgrade height: "old". */
        const val OLD = 3_400_000L
    }
}

private val PASS_CONV_ID_CALLS =
    setOf(
        "getConversationId",
        "setConversationId",
        "getOrCreateConversationId",
        "getOrCreateConversationIdForMessage",
        "needsConversationInit",
        "markConversationInitSubmitted",
        "getPeerByConversationId",
        "setConversationMapping",
        "getAllConversationMappings",
        "getAllPeerToConvIdMappings",
        "removeConversationMapping",
        "ownConversationId",
        "isOwnConversationId",
        "ownConversationIdForStart",
        "restoreOwnConversationId",
    )

private fun passEmptyValue(type: Class<*>): Any? =
    when (type) {
        java.lang.Boolean.TYPE -> false
        java.lang.Integer.TYPE -> 0
        java.lang.Long.TYPE -> 0L
        java.lang.Float.TYPE -> 0f
        java.lang.Double.TYPE -> 0.0
        List::class.java -> emptyList<Any>()
        Set::class.java -> emptySet<Any>()
        Map::class.java -> emptyMap<Any, Any>()
        else -> null
    }

/** An in-memory hash cache. */
private class PassMapCache : AddressCache {
    private val byHash = ConcurrentHashMap<String, String>()
    private val partners = ConcurrentHashMap.newKeySet<String>()

    override fun cacheAddress(
        hash: String,
        address: String,
    ) {
        byHash[hash] = address
    }

    override fun cacheAddressValidated(
        hash: String,
        address: String,
    ) {
        byHash[hash] = address
    }

    override fun getAddress(hash: String): String? = byHash[hash]

    override fun hasAddress(hash: String): Boolean = byHash.containsKey(hash)

    override fun getAllCachedAddresses(): Map<String, String> = byHash.toMap()

    override fun addConversationPartner(address: String) {
        partners += address
    }

    override fun getConversationPartners(): Set<String> = partners.toSet()

    override fun isConversationPartner(address: String): Boolean = address in partners

    override fun findConversationPartnerByHash(hash: String): String? = byHash[hash]?.takeIf { it in partners }
}
