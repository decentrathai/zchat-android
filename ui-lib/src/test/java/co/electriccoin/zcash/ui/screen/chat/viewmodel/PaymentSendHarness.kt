package co.electriccoin.zcash.ui.screen.chat.viewmodel

import androidx.lifecycle.ViewModel
import cash.z.ecc.android.sdk.model.Memo
import cash.z.ecc.android.sdk.model.Proposal
import cash.z.ecc.android.sdk.model.UnifiedSpendingKey
import cash.z.ecc.android.sdk.model.WalletAddress
import cash.z.ecc.android.sdk.model.Zatoshi
import cash.z.ecc.sdk.extension.toCanonicalZecString
import co.electriccoin.zcash.ui.common.datasource.ProposalDataSource
import co.electriccoin.zcash.ui.common.datasource.Zip321TransactionProposal
import co.electriccoin.zcash.ui.common.model.FeeProbe
import co.electriccoin.zcash.ui.common.model.SubmitResult
import co.electriccoin.zcash.ui.common.model.SweepProposalBytes
import co.electriccoin.zcash.ui.common.model.WalletAccount
import co.electriccoin.zcash.ui.common.repository.ZashiProposalRepositoryImpl
import co.electriccoin.zcash.ui.common.usecase.Zc101FakeAccountDataSource
import co.electriccoin.zcash.ui.common.usecase.Zc101FakeKeystoneProposalRepository
import co.electriccoin.zcash.ui.common.usecase.Zc101FakeZashiSpendingKeyDataSource
import co.electriccoin.zcash.ui.common.usecase.Zc101RecordingNavigationRouter
import co.electriccoin.zcash.ui.common.usecase.Zc101RecordingProposalDataSource
import co.electriccoin.zcash.ui.common.usecase.syntheticZashiAccount
import co.electriccoin.zcash.ui.common.usecase.syntheticZip321Proposal
import co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferences
import co.electriccoin.zcash.ui.screen.chat.model.AddressCache
import co.electriccoin.zcash.ui.screen.chat.model.ChatListState
import co.electriccoin.zcash.ui.screen.chat.model.ChatMessage
import co.electriccoin.zcash.ui.screen.chat.model.ChatNoteCopy
import co.electriccoin.zcash.ui.screen.chat.model.ConversationMode
import co.electriccoin.zcash.ui.screen.chat.model.SendMessageState
import co.electriccoin.zcash.ui.screen.chat.model.SysNotes
import co.electriccoin.zcash.ui.screen.chat.usecase.CreateChunkedMessageProposalUseCase
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeoutOrNull
import java.lang.reflect.Proxy
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Shared fixture for the batch-A4 payment and send-result regression tests (fulfil back-out, NOSTR payment
 * request persistence, send-result wording, retry amount).
 *
 * Like [HandshakeHarness], it drives the REAL ChatViewModel send entry points through the real
 * [CreateChunkedMessageProposalUseCase] and the real [ZashiProposalRepositoryImpl] (with its detached
 * submit scope). Only the preferences store ([PaymentPrefs], one instance shared between ViewModels like
 * the app singleton) and the settlement seam ([PaymentSeam]) are faked. The ViewModel is allocated
 * without its init-heavy constructor; [paymentViewModel] sets the fields these paths read, with the
 * values their initialisers would give. All addresses are synthetic.
 */
internal object PaymentHarness {
    const val SELF = "usyntheticpaymentself0000001"
    const val CONV_ID = "PAYA2345"
    const val TIMEOUT_MS = 10_000L

    suspend fun paymentViewModel(
        prefs: PaymentPrefs,
        seam: PaymentSeam,
    ): ChatViewModel {
        val owner = SendHarnessScopes.current()
        val accounts = Zc101FakeAccountDataSource(syntheticZashiAccount())
        val useCase =
            CreateChunkedMessageProposalUseCase(
                keystoneProposalRepository = Zc101FakeKeystoneProposalRepository(),
                zashiProposalRepository =
                    ZashiProposalRepositoryImpl(
                        accountDataSource = accounts,
                        proposalDataSource = seam,
                        zashiSpendingKeyDataSource = Zc101FakeZashiSpendingKeyDataSource()
                    ).also(owner::own),
                accountDataSource = accounts,
                navigationRouter = Zc101RecordingNavigationRouter(),
                biometricRepository = HandshakeHarness.ApprovingBiometricRepository
            )
        val vm = allocate()
        val impl =
            Class
                .forName("androidx.lifecycle.viewmodel.internal.ViewModelImpl")
                .getDeclaredConstructor()
                .newInstance()
        ViewModel::class.java
            .getDeclaredField("impl")
            .apply { isAccessible = true }
            .set(vm, impl)
        owner.own(vm)
        set(vm, "zchatPreferences", prefs.proxy)
        set(vm, "noteCopy", TestChatNoteCopy)
        set(vm, "createChunkedMessageProposal", useCase)
        set(vm, "accountDataSource", accounts)
        set(vm, "addressCache", NoOpAddressCache.proxy)
        set(vm, "convIdMutex", Mutex())
        set(vm, "_currentUserAddress", MutableStateFlow<String?>(SELF))
        set(vm, "_sendMessageState", MutableStateFlow<SendMessageState>(SendMessageState.Idle))
        set(vm, "_chatListState", MutableStateFlow<ChatListState>(ChatListState.Loading))
        set(vm, "_showCostDisclaimer", MutableStateFlow(false))
        set(vm, "_blockHeight", MutableStateFlow<Long?>(null))
        set(vm, "pendingMessages", MutableStateFlow<List<ChatMessage>>(emptyList()))
        // The constructor loads the durable paid set into this flow (init block); mirror it.
        set(vm, "paidRequestIds", MutableStateFlow(prefs.getPaidRequestIdsDirect()))
        // C3 (B2 review item 8): the scan-back's unsaved paid marks (field initialiser).
        set(vm, "unsavedPaidMarks", ConcurrentHashMap.newKeySet<String>())
        set(vm, "messageQueue", mutableListOf<Any>())
        set(vm, "messageProcessors", ConcurrentHashMap<String, Any>())
        // Reply-ref markers (field initialisers), read by tagReply / untagReply.
        set(vm, "replyRefSentinel", '\u0001')
        set(vm, "replyRefPrefix", "\u0001RPL:")
        set(vm, "replyPreviewSep", '\u001f')
        // M2 (C-M2-2): the first-message plan check answers Fundable here; left null, the send gate refuses (fail closed).
        FirstMessagePlanApi.installFundable(vm)
        return vm
    }

    fun sendMessageState(vm: ChatViewModel): SendMessageState = HandshakeHarness.sendMessageState(vm)

    fun setSendMessageState(
        vm: ChatViewModel,
        state: SendMessageState,
    ) {
        @Suppress("UNCHECKED_CAST")
        (get(vm, "_sendMessageState") as MutableStateFlow<SendMessageState>).value = state
    }

    /** The chain tip the ViewModel last saw (networkHeight). */
    fun setBlockHeight(
        vm: ChatViewModel,
        height: Long?,
    ) {
        @Suppress("UNCHECKED_CAST")
        (get(vm, "_blockHeight") as MutableStateFlow<Long?>).value = height
    }

    /** Sets a constructor-injected dependency the allocation skipped. */
    fun setField(
        vm: ChatViewModel,
        name: String,
        value: Any,
    ) = set(vm, name, value)

    @Suppress("UNCHECKED_CAST")
    fun pendingMessages(vm: ChatViewModel): List<ChatMessage> = (get(vm, "pendingMessages") as MutableStateFlow<List<ChatMessage>>).value

    /** The loader a fresh ChatViewModel runs on start (restart or chat reopen). */
    fun loadPendingMessagesFromPrefs(vm: ChatViewModel) {
        ChatViewModel::class.java
            .getDeclaredMethod("loadPendingMessagesFromPrefs")
            .apply { isAccessible = true }
            .invoke(vm)
    }

    /** Polls [condition] for up to [timeoutMs]; returns its last value instead of throwing on timeout. */
    suspend fun eventually(
        timeoutMs: Long = 2_000L,
        condition: () -> Boolean,
    ): Boolean =
        withTimeoutOrNull(timeoutMs) {
            while (!condition()) delay(5)
            true
        } ?: condition()

    /** The `amount=` of the first (value) output of a ZIP-321 URI the use case built. */
    fun firstOutputAmount(zip321Uri: String): String? = Regex("""\?amount=([0-9.]+)""").find(zip321Uri)?.groupValues?.get(1)

    /** The URI spelling of [zatoshi], exactly as the use case writes it. */
    fun zec(zatoshi: Long): String = Zatoshi(zatoshi).toCanonicalZecString()

    private fun allocate(): ChatViewModel {
        val unsafeClass = Class.forName("sun.misc.Unsafe")
        val unsafe = unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)
        val allocate = unsafeClass.getMethod("allocateInstance", Class::class.java)
        return allocate.invoke(unsafe, ChatViewModel::class.java) as ChatViewModel
    }

    private fun set(
        vm: ChatViewModel,
        name: String,
        value: Any,
    ) {
        ChatViewModel::class.java
            .getDeclaredField(name)
            .apply { isAccessible = true }
            .set(vm, value)
    }

    private fun get(
        vm: ChatViewModel,
        name: String,
    ): Any? =
        ChatViewModel::class.java
            .getDeclaredField(name)
            .apply { isAccessible = true }
            .get(vm)
}

/** An [AddressCache] that remembers nothing (the send paths only record conversation partners). */
internal object NoOpAddressCache {
    val proxy: AddressCache =
        Proxy.newProxyInstance(
            AddressCache::class.java.classLoader,
            arrayOf(AddressCache::class.java),
        ) { p, method, args ->
            when (method.name) {
                "getAddress" -> null
                "hasAddress" -> false
                "getAllCachedAddresses" -> emptyMap<String, String>()
                "getConversationPartners" -> emptySet<String>()
                "hashCode" -> System.identityHashCode(p)
                "equals" -> p === args?.get(0)
                "toString" -> "NoOpAddressCache"
                else -> null
            }
        } as AddressCache
}

/**
 * A map-backed preferences store answering exactly the calls the payment and send paths make. Any other
 * call is recorded in [unexpectedCalls] (tests assert it stays empty), so an unanticipated read can never
 * silently end a path early and make an assertion pass for the wrong reason.
 */
internal class PaymentPrefs(
    private val peer: String,
    @Volatile var mode: ConversationMode = ConversationMode.VAULT,
    @Volatile var peerNostrPubkey: String? = null,
) {
    /** Whether the user has accepted the message-cost notice (the app stores it once, in the settings store). */
    @Volatile var costAcknowledged: Boolean = true

    /** ZC-250: the contact's "security key changed" flag (isE2EKeyChanged / setE2EKeyChanged); unset by default. */
    @Volatile var keyChanged: Boolean = false

    val paid: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** C3 (B2 review item 8): each queued entry makes one markRequestPaid write fail (nothing reaches [paid]). */
    val markRequestPaidFailures: java.util.Queue<Unit> = java.util.concurrent.ConcurrentLinkedQueue()
    val pending = ConcurrentHashMap<String, ZchatPreferences.PendingMessageData>()

    /** Round 8 P2: the fulfil started records (ZchatPreferences.markFulfilStarted), by request id. */
    val started = ConcurrentHashMap<String, ZchatPreferences.FulfilStarted>()

    /**
     * Round 8 D9: the note the first message on the Zcash-only branch writes (SysNotes.zcashOnlyNoteId), which the
     * store holds with the rows. Kept apart from [pending], which these tests read as the message rows; the view model
     * still reads both (getPendingMessages), as it reads the one real store.
     */
    val notes = ConcurrentHashMap<String, ZchatPreferences.PendingMessageData>()
    val unexpectedCalls: MutableList<String> = Collections.synchronizedList(mutableListOf())

    fun getPaidRequestIdsDirect(): Set<String> = paid.toSet()

    @Suppress("CyclomaticComplexMethod")
    val proxy: ZchatPreferences =
        Proxy.newProxyInstance(
            ZchatPreferences::class.java.classLoader,
            arrayOf(ZchatPreferences::class.java),
        ) { p, method, args ->
            fun arg(i: Int) = args!![i]
            when (method.name) {
                // W-72 (SPEC_F R4, R6): no pre-update mark, no probe, no reply id (the send path asks).
                "getLegacyKeyMark", "getLegacyProbe", "getReplyConversationId" -> null
                // W-72 (SPEC_DECISIONS D2): the history pass asks whether this key store read its history (read).
                "isLegacyHistoryRead" -> true
                // W-122 (B1a): no mode switch recorded, no INIT owed on chain, no stored relay-bound peers.
                "getModeSwitchRecord", "getConversationIdOwedInitOnChain" -> null
                "markConversationInitOwedOnChain" -> Unit
                "getPeersWithNostrPubkey" -> emptySet<String>()
                // ZC-1352: no first message was deleted here; a clear of the mark is accepted.
                "isAutomaticSetupWithdrawn" -> false
                "setAutomaticSetupWithdrawn" -> true
                "isE2EKeyChanged" -> keyChanged
                "setE2EKeyChanged" -> {
                    if (arg(0) == peer) keyChanged = arg(1) as Boolean
                    null
                }
                "hasAcknowledgedMessageCost" -> costAcknowledged
                "setAcknowledgedMessageCost" -> {
                    costAcknowledged = true
                    null
                }
                "getPaidRequestIds" -> paid.toSet()
                "markRequestPaid" -> {
                    // B2: the marker reports whether it reached the disk; this store stands in for a working one,
                    // unless a test queued failed writes in [markRequestPaidFailures] (C3, B2 review item 8).
                    val saved = markRequestPaidFailures.poll() == null
                    if (saved) paid.add(arg(0) as String)
                    saved
                }
                "unmarkRequestPaid" -> {
                    // C3 (B2 review item 5): a marker written before a submit that proved nothing was sent.
                    paid.remove(arg(0) as String)
                    null
                }
                "markFulfilStarted" -> {
                    // Round 8 P2: the paid marker and the started record in one write; a queued failure fails both.
                    val saved = markRequestPaidFailures.poll() == null
                    if (saved) {
                        val id = arg(0) as String
                        paid.add(id)
                        started[id] =
                            ZchatPreferences.FulfilStarted(
                                attemptHeight = arg(1) as Long,
                                peerHash = ZchatPreferences.fulfilAddressHash(arg(2) as String),
                                amountZatoshi = arg(3) as Long,
                                selfHash = ZchatPreferences.fulfilAddressHash(arg(4) as String),
                                startedAtMillis = arg(5) as Long,
                            )
                    }
                    saved
                }
                "clearFulfilStarted" -> {
                    started.remove(arg(0) as String)
                    null
                }
                "getFulfilStarted" -> started.toMap()
                "addPendingMessage", "commitPendingMessage" -> {
                    val row = arg(0) as ZchatPreferences.PendingMessageData
                    if (row.id == SysNotes.zcashOnlyNoteId(peer)) notes[row.id] = row else pending[row.id] = row
                    null
                }
                "removePendingMessage" -> {
                    pending.remove(arg(0) as String)
                    notes.remove(arg(0) as String)
                    null
                }
                "removePendingMessages" -> {
                    @Suppress("UNCHECKED_CAST")
                    (arg(0) as Set<String>).forEach {
                        pending.remove(it)
                        notes.remove(it)
                    }
                    null
                }
                "getPendingMessages" -> (pending.values + notes.values).sortedBy { it.timestampMillis }
                "getConversationMode" -> mode
                "getPeerNostrPubkey" -> if (arg(0) == peer) peerNostrPubkey else null
                "getPeerNostrRelay" -> null
                // A keyed-but-not-E2E peer: no first-contact KEX is sent and the message goes out unencrypted,
                // so the send reaches the chokepoint without the ratchet.
                "getE2EOurPublicKey" -> if (arg(0) == peer) "synthetic-our-public-key" else null
                "getE2EPeerPublicKey" -> null
                "getE2EPrivateKey" -> null
                "isE2EEnabled" -> false
                "getOrCreateConversationId" -> PaymentHarness.CONV_ID to false
                // R2-6 (c): the per-message call (an existing conversation whose INIT went out).
                "getOrCreateConversationIdForMessage" -> PaymentHarness.CONV_ID to false
                // FC-ID (ZC-892): our KEX is built only under an id this phone made for the address; this fixture's id is that id.
                "ownConversationId", "ownConversationIdForStart" -> PaymentHarness.CONV_ID
                "getNostrReactions" -> emptyList<ZchatPreferences.PersistedReaction>()
                "addNostrReaction" -> null
                "isFileViewed" -> false
                // R2-5 (c): the durable record of expired messages the history pass reads (none here).
                "getExpiredMessageIds" -> emptySet<String>()
                // ZC-787: every history pass snapshots the wallet scope and enrolls the contacts whose quoted ZBOOT is owed
                // (beginZbootHistoryPass, ZC-703). The interface's own defaults: no wallet switch, nothing owed here.
                "walletScopeGeneration" -> 0L
                "getZbootOwedPeers" -> emptySet<String>()
                // ZC-704 (KI-25 R19): every history pass lists the stored groups, before its loop and at its end, for
                // the admin changes kept for a confirmation. The interface's own answer for a phone with no group.
                "getAllGroupIds" -> emptySet<String>()
                // Round 8 D9: the first message on the Zcash-only branch writes a note naming the contact.
                "getDisplayName" -> "Synthetic Contact"
                // TX-1: that note names the contact like the chat header (peerLabel): nickname first (none here).
                "getNickname" -> null
                "toString" -> "PaymentPrefs"
                "hashCode" -> System.identityHashCode(p)
                "equals" -> p === args?.get(0)
                else -> {
                    unexpectedCalls.add(method.name)
                    null
                }
            }
        } as ZchatPreferences
}

/**
 * The settlement seam under the real repository. Records every ZIP-321 URI the use case proposes and every
 * broadcast. [failFirstProposalWith] makes the first proposal throw (nothing is sent); [holdFirstSubmit]
 * keeps the first broadcast open until [release]; [firstSubmitOutcome] is what the first broadcast reports.
 */
internal class PaymentSeam(
    private val peer: String,
    private val failFirstProposalWith: Exception? = null,
    private val holdFirstSubmit: Boolean = false,
    private val firstSubmitOutcome: SubmitResult = SubmitResult.Success(txIds = listOf("synthetic-first-txid")),
    private val recording: Zc101RecordingProposalDataSource = Zc101RecordingProposalDataSource(),
) : ProposalDataSource by recording {
    val proposalUris: MutableList<String> = Collections.synchronizedList(mutableListOf())
    val submits = AtomicInteger(0)
    val broadcasts: MutableList<String> = Collections.synchronizedList(mutableListOf())
    val firstSubmitEntered = CompletableDeferred<Unit>()
    val firstSubmitDone = CompletableDeferred<Unit>()
    val release = CompletableDeferred<Unit>()

    // D-6 (fix-all 2.13.0): the probing seam behind the payment confirms' exact price ([feeProbe]; null keeps the
    // delegate's answer) and the network fee every proposal then carries ([proposalFee]; null keeps the synthetic
    // zero-step proposal). [probes] counts the price asks and [probeUris] holds each probe's ZIP 321 request.
    @Volatile var feeProbe: (() -> FeeProbe)? = null

    @Volatile var proposalFee: Long? = null
    val probes = AtomicInteger(0)
    val probeUris: MutableList<String> = Collections.synchronizedList(mutableListOf())

    override suspend fun probeZip321Fee(
        account: WalletAccount,
        zip321Uri: String,
    ): FeeProbe {
        probes.incrementAndGet()
        probeUris += zip321Uri
        return feeProbe?.invoke() ?: recording.probeZip321Fee(account, zip321Uri)
    }

    override suspend fun createZip321Proposal(
        account: WalletAccount,
        zip321Uri: String,
        allowMultiPayment: Boolean
    ): Zip321TransactionProposal {
        proposalUris += zip321Uri
        if (proposalUris.size == 1 && failFirstProposalWith != null) throw failFirstProposalWith
        proposalFee?.let { fee ->
            return Zip321TransactionProposal(
                destination = WalletAddress.Unified.new(peer),
                amount = Zatoshi(1_000L),
                memo = Memo("synthetic-payment-memo"),
                proposal = SweepProposalBytes.proposal(listOf(SweepProposalBytes.ironwood(1_000_000L)), fee),
            )
        }
        return syntheticZip321Proposal(peer, amount = 1_000L, memo = "synthetic-payment-memo")
    }

    // F4: the 1:1 chat senders now call this overload (to record the stored ids before the submit). This fake
    // reports no stored ids: it settles through its own two-argument submit below, exactly as before F4.
    override suspend fun submitTransaction(
        proposal: Proposal,
        usk: UnifiedSpendingKey,
        onStored: co.electriccoin.zcash.ui.common.datasource.OnTransactionsStored,
    ): SubmitResult = submitTransaction(proposal, usk)

    override suspend fun submitTransaction(
        proposal: Proposal,
        usk: UnifiedSpendingKey
    ): SubmitResult {
        val n = submits.incrementAndGet()
        if (n == 1) {
            firstSubmitEntered.complete(Unit)
            if (holdFirstSubmit) release.await()
            broadcasts += "synthetic-txid-$n"
            firstSubmitDone.complete(Unit)
            return firstSubmitOutcome
        }
        broadcasts += "synthetic-txid-$n"
        return SubmitResult.Success(txIds = listOf("synthetic-txid-$n"))
    }
}

/**
 * R5-4: the words of an in-chat system note, for a view model these harnesses allocate WITHOUT its
 * constructor. A JVM unit test has no Android resources, so the fake records the resource and its
 * arguments instead of resolving them; no test asserts on a note's wording.
 */
internal val TestChatNoteCopy: ChatNoteCopy =
    ChatNoteCopy { id, args -> "note#" + id + "(" + args.joinToString(",") + ")" }
