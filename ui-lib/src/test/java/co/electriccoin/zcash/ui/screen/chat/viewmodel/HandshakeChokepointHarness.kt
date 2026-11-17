package co.electriccoin.zcash.ui.screen.chat.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cash.z.ecc.android.sdk.Synchronizer
import cash.z.ecc.android.sdk.model.BlockHeight
import cash.z.ecc.android.sdk.model.Memo
import cash.z.ecc.android.sdk.model.Proposal
import cash.z.ecc.android.sdk.model.UnifiedSpendingKey
import cash.z.ecc.android.sdk.model.WalletAddress
import cash.z.ecc.android.sdk.model.WalletBalance
import cash.z.ecc.android.sdk.model.Zatoshi
import co.electriccoin.zcash.ui.common.datasource.AccountDataSource
import co.electriccoin.zcash.ui.common.datasource.InsufficientFundsException
import co.electriccoin.zcash.ui.common.datasource.ProposalDataSource
import co.electriccoin.zcash.ui.common.datasource.Zip321TransactionProposal
import co.electriccoin.zcash.ui.common.model.FeeProbe
import co.electriccoin.zcash.ui.common.model.SubmitResult
import co.electriccoin.zcash.ui.common.model.SweepProposalBytes
import co.electriccoin.zcash.ui.common.model.SynchronizerError
import co.electriccoin.zcash.ui.common.model.WalletAccount
import co.electriccoin.zcash.ui.common.provider.SynchronizerProvider
import co.electriccoin.zcash.ui.common.repository.BiometricRepository
import co.electriccoin.zcash.ui.common.repository.BiometricRequest
import co.electriccoin.zcash.ui.common.repository.BiometricResult
import co.electriccoin.zcash.ui.common.repository.ZashiProposalRepositoryImpl
import co.electriccoin.zcash.ui.common.usecase.Zc101FakeAccountDataSource
import co.electriccoin.zcash.ui.common.usecase.Zc101FakeKeystoneProposalRepository
import co.electriccoin.zcash.ui.common.usecase.Zc101FakeZashiSpendingKeyDataSource
import co.electriccoin.zcash.ui.common.usecase.Zc101RecordingNavigationRouter
import co.electriccoin.zcash.ui.common.usecase.Zc101RecordingProposalDataSource
import co.electriccoin.zcash.ui.common.usecase.syntheticZashiAccount
import co.electriccoin.zcash.ui.common.usecase.syntheticZip321Proposal
import co.electriccoin.zcash.ui.screen.chat.crypto.E2EEncryption
import co.electriccoin.zcash.ui.screen.chat.crypto.E2EKeyPair
import co.electriccoin.zcash.ui.screen.chat.crypto.SeedDerivedE2EKeyProvider
import co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferences
import co.electriccoin.zcash.ui.screen.chat.model.ContactBook
import co.electriccoin.zcash.ui.screen.chat.model.ConversationMode
import co.electriccoin.zcash.ui.screen.chat.model.SendMessageState
import co.electriccoin.zcash.ui.screen.chat.model.ZMSGProtocol
import co.electriccoin.zcash.ui.screen.chat.usecase.CreateChunkedMessageProposalUseCase
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeout
import java.lang.reflect.Proxy
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Shared fixture for the handshake-chokepoint regression tests (KEXACK single-flight, back-out, same-key
 * replay, consent recheck, queued-retry marker recheck, time-lock consent).
 *
 * It drives the REAL ChatViewModel call sites (`handleKEXMessage`, `retryKexAckIfResponder`,
 * `sendKEXMessage`, the time-lock senders) through the real [CreateChunkedMessageProposalUseCase] and the
 * real [ZashiProposalRepositoryImpl] with its detached submit scope. Only three things are faked: the
 * preferences store ([HandshakePrefs], one instance shared between ViewModels exactly like the app's
 * singleton), the SDK settlement seam ([HandshakeSeam]) and the chain tip ([HeightFeed]).
 *
 * The ViewModel is allocated without its init-heavy constructor (the RouteBootPubkeyReuseTest idiom), so
 * field initialisers do not run; [handshakeViewModel] sets the fields these paths read. All keys are
 * generated per run and every address is synthetic.
 */
internal object HandshakeHarness {
    const val SELF = "usynthetichandshakeself00000"
    const val CONV_ID = "HSKE2345"
    const val TIMEOUT_MS = 10_000L
    const val START_HEIGHT = 3_500_000L

    suspend fun handshakeViewModel(
        prefs: HandshakePrefs,
        seam: HandshakeSeam,
        heights: HeightFeed = HeightFeed(),
        ironwoodPending: Long = 0L,
    ): ChatViewModel = chatViewModelWith(prefs.proxy, seam, heights, ironwoodPending)

    /** [handshakeViewModel] over any preferences fake (the group-key tests bring their own). */
    suspend fun chatViewModelWith(
        prefs: ZchatPreferences,
        seam: HandshakeSeam,
        heights: HeightFeed = HeightFeed(),
        ironwoodPending: Long = 0L,
        // C5: the selected account, when a test changes it (sealed Orchard, Ironwood confirming, Keystone). The
        // ViewModel then reads it too; without one it keeps reading none, as before.
        accountSource: AccountDataSource? = null,
    ): ChatViewModel {
        val owner = SendHarnessScopes.current()
        val base = syntheticZashiAccount()
        val account =
            if (ironwoodPending > 0) {
                base.copy(
                    unified =
                        base.unified.copy(
                            ironwood = WalletBalance(Zatoshi(0), Zatoshi(ironwoodPending), Zatoshi(0))
                        )
                )
            } else {
                base
            }
        val accounts = accountSource ?: Zc101FakeAccountDataSource(account)
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
                biometricRepository = ApprovingBiometricRepository
            )

        val vm = allocateChatViewModel()
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
        setField(vm, "zchatPreferences", prefs)
        setField(vm, "noteCopy", TestChatNoteCopy)
        setField(vm, "createChunkedMessageProposal", useCase)
        setField(vm, "convIdMutex", Mutex())
        setField(vm, "synchronizerProvider", heights.provider)
        setField(vm, "_currentUserAddress", MutableStateFlow<String?>(SELF))
        setField(vm, "_sendMessageState", MutableStateFlow<SendMessageState>(SendMessageState.Idle))
        // Per-instance handshake guards exist only while they are per-instance; a constructor-free
        // allocation leaves them null, so give them the values their initialisers would.
        setFieldIfPresent(vm, "kexAckedKeys") { mutableMapOf<String, String>() }
        setFieldIfPresent(vm, "kexAckInFlight") { ConcurrentHashMap.newKeySet<String>() }
        // R2-2 (a): a stranger's first KEX makes our key for them through the seed provider; no seed here,
        // so it falls back to a random key written through these same preferences.
        setField(vm, "seedKeyProvider", SeedDerivedE2EKeyProvider(prefs) { null })
        setField(vm, "contactBook", NoSavedContacts.book)
        if (accountSource != null) setField(vm, "accountDataSource", accountSource)
        // M2 (C-M2-2): the first-message plan check answers Fundable here, so these paths keep their behaviour; a test of
        // the gate sets its own. Left null, the gate refuses (fail closed).
        FirstMessagePlanApi.installFundable(vm)
        // C5: the process-wide scope held GROUP_ACCEPT sends run in (when this build has one) belongs to the test.
        heldAcceptScopeField()?.set(null, owner.heldAcceptScope)
        return vm
    }

    /** C5: the static field holding ChatViewModel's process-wide held GROUP_ACCEPT scope, when this build has it. */
    private fun heldAcceptScopeField(): java.lang.reflect.Field? =
        ChatViewModel::class.java.declaredFields
            .firstOrNull { it.name == "heldGroupAcceptScope" && isStatic(it) }
            ?.apply { isAccessible = true }

    private fun isStatic(field: java.lang.reflect.Field): Boolean =
        java.lang.reflect.Modifier
            .isStatic(field.modifiers)

    /** C5: the scope held GROUP_ACCEPT sends run in, or null when this build runs them in the screen's own scope. */
    fun heldAcceptScope(): CoroutineScope? = heldAcceptScopeField()?.get(null) as? CoroutineScope

    /**
     * C5: cancel every held GROUP_ACCEPT send in flight (what the end of the process scope would do). The scope
     * itself stays usable.
     */
    fun cancelHeldAcceptSends() {
        heldAcceptScope()
            ?.coroutineContext
            ?.job
            ?.children
            ?.forEach { it.cancel() }
    }

    fun sendMessageState(vm: ChatViewModel): SendMessageState {
        @Suppress("UNCHECKED_CAST")
        val flow =
            ChatViewModel::class.java
                .getDeclaredField("_sendMessageState")
                .apply { isAccessible = true }
                .get(vm) as MutableStateFlow<SendMessageState>
        return flow.value
    }

    /** The real inbound handler, as convertToConversations / the NOSTR bridge call it. */
    fun handleKexMemo(vm: ChatViewModel, memo: String, txId: String) {
        ChatViewModel::class.java
            .getDeclaredMethod("handleKEXMessage", String::class.java, String::class.java, String::class.java)
            .apply { isAccessible = true }
            .invoke(vm, memo, SELF, txId)
    }

    /** A valid, signed first-contact KEX from [peer] carrying [peerKeys]'s public key. */
    fun kexMemoFrom(peer: String, peerKeys: E2EKeyPair): String =
        ZMSGProtocol.createV4KEXMessage(
            CONV_ID,
            peer,
            E2EEncryption.createKEXPayload(peer, peerKeys.publicKey, peerKeys.privateKey)
        )

    suspend fun awaitIdle(vararg vms: ChatViewModel) {
        withTimeout(TIMEOUT_MS) {
            for (vm in vms) {
                vm.viewModelScope.coroutineContext.job.children
                    .forEach { it.join() }
            }
            // C5: and the held GROUP_ACCEPT sends those screens started in the process-wide scope.
            heldAcceptScope()
                ?.coroutineContext
                ?.job
                ?.children
                ?.forEach { it.join() }
        }
    }

    suspend fun awaitTrue(condition: () -> Boolean) {
        withTimeout(TIMEOUT_MS) {
            while (!condition()) delay(5)
        }
    }

    /** What a "funds still confirming" shortfall looks like when it reaches the use case. */
    fun stillConfirming(): InsufficientFundsException =
        InsufficientFundsException("Insufficient balance", have = Zatoshi(0), need = Zatoshi(17_000))

    private fun allocateChatViewModel(): ChatViewModel {
        val unsafeClass = Class.forName("sun.misc.Unsafe")
        val unsafe = unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)
        val allocate = unsafeClass.getMethod("allocateInstance", Class::class.java)
        return allocate.invoke(unsafe, ChatViewModel::class.java) as ChatViewModel
    }

    private fun setField(vm: ChatViewModel, name: String, value: Any) {
        ChatViewModel::class.java
            .getDeclaredField(name)
            .apply { isAccessible = true }
            .set(vm, value)
    }

    private fun setFieldIfPresent(vm: ChatViewModel, name: String, value: () -> Any) {
        val field = ChatViewModel::class.java.declaredFields.firstOrNull { it.name == name } ?: return
        if (java.lang.reflect.Modifier
                .isStatic(field.modifiers)
        ) {
            return
        }
        field.isAccessible = true
        field.set(vm, value())
    }

    /** R2-2 (a): an address book with no saved contacts (the handshake paths only ask whether one is saved). */
    object NoSavedContacts {
        val book: ContactBook =
            Proxy.newProxyInstance(
                ContactBook::class.java.classLoader,
                arrayOf(ContactBook::class.java),
            ) { p, method, args ->
                when (method.name) {
                    "hasContact" -> false
                    "getContact" -> null
                    "toString" -> "NoSavedContacts"
                    "hashCode" -> System.identityHashCode(p)
                    "equals" -> p === args?.get(0)
                    else -> throw UnsupportedOperationException("unexpected ContactBook call: ${method.name}")
                }
            } as ContactBook
    }

    /** Approves every biometric request at once (direct-submit sends never wait on a prompt). */
    object ApprovingBiometricRepository : BiometricRepository {
        override val allowedAuthenticators: Int = 0

        override fun onBiometricResult(result: BiometricResult) = Unit

        override suspend fun requestBiometrics(request: BiometricRequest) = Unit
    }
}

/**
 * A map-backed preferences store answering exactly the calls the handshake paths make. Any other call
 * is recorded in [unexpectedCalls] (tests assert it stays empty), so an unanticipated read can never
 * silently end a path early and make a "nothing was paid" assertion pass for the wrong reason.
 */
internal class HandshakePrefs(
    private val peer: String,
    consent: Boolean = true,
    /**
     * R2-2 (a): [peer] has no chat with us yet. The KEX convId is unmapped (the sender is recovered from the
     * payload), we hold no key of ours for them and E2E is not enabled for them, so the handler generates our
     * key through the seed provider exactly as a stranger's first KEX does.
     */
    private val firstContact: Boolean = false,
) {
    val ourKeys: E2EKeyPair = E2EEncryption.generateKeyPair()

    /** Our own addresses ([ZchatPreferences.isSelfAddress]). */
    val selfAddresses: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** Our E2E key pairs per peer (public to private); [peer] holds [ourKeys] unless [firstContact]. */
    val ourKeyStore = ConcurrentHashMap<String, Pair<String, String>>()
    val e2eEnabled: MutableSet<String> = ConcurrentHashMap.newKeySet()
    val convIdToPeer = ConcurrentHashMap<String, String>()
    val peerToConvId = ConcurrentHashMap<String, String>()

    /** ZC-1352: the contacts whose automatic setup the user withdrew by deleting the first message. */
    val setupWithdrawn: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** R2-2 (a): first-contact KEX keys waiting for the user's Accept, per peer. */
    val awaitingAccept = ConcurrentHashMap<String, String>()

    init {
        if (!firstContact) {
            ourKeyStore[peer] = ourKeys.publicKey to ourKeys.privateKey
            e2eEnabled += peer
            convIdToPeer[HandshakeHarness.CONV_ID] = peer
        }
    }

    @Volatile var consent: Boolean = consent

    @Volatile var ownBootSent: Boolean = false

    val e2ePeerKey = ConcurrentHashMap<String, String>()
    val receivedKex = ConcurrentHashMap<String, String>()

    /** ZC-599: the id of the received key exchange, kept beside [receivedKex]; and the asked record. */
    val receivedKexConvId = ConcurrentHashMap<String, String>()
    val askedKey = ConcurrentHashMap<String, String>()

    /** ZC-634: the claim marks (the key each names) and the ZBOOT each held back, in the key store beside the key. */
    val claims = ConcurrentHashMap<String, String>()
    val heldBoots = ConcurrentHashMap<String, String>()
    val sentKexAck = ConcurrentHashMap<String, String>()
    val sentKexAckWrites: MutableList<String?> = Collections.synchronizedList(mutableListOf())
    val processedKexTx: MutableSet<String> = ConcurrentHashMap.newKeySet()
    val budgetConsumed = AtomicInteger(0)
    val ownBootSentWrites: MutableList<Boolean> = Collections.synchronizedList(mutableListOf())
    val paidRecords = ConcurrentHashMap<String, String>()

    /** ZC-1096: the chat's mode, for every contact (Shielded unless a test changes it). */
    @Volatile var mode: ConversationMode = ConversationMode.VAULT

    /** ZC1013-X2: the stored "Not now" marks of the automatic-setup question (they outlive the process). */
    val declinedSetups: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** ZC1013-X4: the stored "Not now" marks cannot be read (the read throws). */
    @Volatile var declinedReadFails: Boolean = false

    /**
     * QA2 k1b2 (ZC-1842, [test-change-ok: ZC-1842]): the durable owed-KEX marks ([ZchatPreferences.isKexOwed]) with their
     * reasons, and the key the user adopted with "Accept the new key" ([ZchatPreferences.getAcceptedKey]); empty unless a
     * test or the code under test writes them. Until k1b2 these reads answered fixed false/null, and
     * isE2EKeyExchangeComplete answered false while both keys were held: a state no phone can be in.
     */
    val kexOwed: MutableSet<String> = ConcurrentHashMap.newKeySet()
    val kexOwedReasons = ConcurrentHashMap<String, String>()
    val acceptedKeys = ConcurrentHashMap<String, String>()
    val unexpectedCalls: MutableList<String> = Collections.synchronizedList(mutableListOf())

    @Suppress("CyclomaticComplexMethod", "LongMethod")
    val proxy: ZchatPreferences =
        Proxy.newProxyInstance(
            ZchatPreferences::class.java.classLoader,
            arrayOf(ZchatPreferences::class.java),
        ) { p, method, args ->
            fun arg(i: Int) = args!![i]

            fun forPeer(): Boolean = arg(0) == peer
            when (method.name) {
                "getE2EOurPublicKey" -> ourKeyStore[arg(0) as String]?.first
                "getE2EPrivateKey" -> ourKeyStore[arg(0) as String]?.second
                "setE2EOurKeys" -> {
                    ourKeyStore[arg(0) as String] = (arg(1) as String) to (arg(2) as String)
                    null
                }
                "setE2EKeyVersion", "setE2EKeyProvenance" -> null
                "getOrCreateConversationId" -> HandshakeHarness.CONV_ID to false
                // R2-6 (c): the per-message call (an existing conversation whose INIT went out).
                "getOrCreateConversationIdForMessage" -> HandshakeHarness.CONV_ID to false
                // FC-ID (ZC-892): our KEX is built only under an id this phone made for the address; this fixture's id is that id.
                "ownConversationId", "ownConversationIdForStart" -> HandshakeHarness.CONV_ID
                "getPeerByConversationId" -> convIdToPeer[arg(0) as String]
                "getConversationId" -> peerToConvId[arg(0) as String]
                "setConversationId" -> {
                    peerToConvId[arg(0) as String] = arg(1) as String
                    convIdToPeer[arg(1) as String] = arg(0) as String
                    null
                }
                "isSelfAddress" -> arg(0) in selfAddresses
                "getInboundKexAwaitingAccept" -> awaitingAccept[arg(0) as String]
                "setInboundKexAwaitingAccept" -> {
                    val value = arg(1) as String?
                    if (value == null) awaitingAccept.remove(arg(0) as String) else awaitingAccept[arg(0) as String] = value
                    // GPT-6 F3: the hold reports whether it was saved; this map always saves it.
                    true
                }
                "hasConsentedToHandshakeSpend" -> this.consent
                "setConsentedToHandshakeSpend" -> {
                    this.consent = true
                    // R5-2: a working store reports its write.
                    true
                }
                "clearConsentedToHandshakeSpend" -> {
                    this.consent = false
                    // R4-1: a working store reports its write.
                    true
                }
                "getTunnelModePeers" -> emptyList<String>()
                "getE2EPeerPublicKey" -> e2ePeerKey[arg(0) as String]
                "setE2EPeerPublicKey" -> {
                    e2ePeerKey[arg(0) as String] = arg(1) as String
                    null
                }
                "getReceivedKexPubkey" -> receivedKex[arg(0) as String]
                "setReceivedKexPubkey" -> {
                    receivedKex[arg(0) as String] = arg(1) as String
                    null
                }
                // ZC-599: the kept id of a received key exchange, and the asked record.
                "getReceivedKexConvId" -> receivedKexConvId[arg(0) as String]
                "setReceivedKexConvId" -> {
                    val value = arg(1) as String?
                    if (value == null) receivedKexConvId.remove(arg(0) as String) else receivedKexConvId[arg(0) as String] = value
                    null
                }
                "getKexAskedKey" -> askedKey[arg(0) as String]
                // W-72 (SPEC_F, SPEC_DECISIONS D2, D4): no pre-update mark, no probe, no reply id, the history read.
                "getLegacyKeyMark", "getHeldLegacyBoot", "getLegacyProbe", "getReplyConversationId" -> null
                "isLegacyHistoryRead" -> true
                // FC-2B (ZC-893): our own start removes a key this phone never asked for, with the peer state it brought.
                "clearE2EPeerState" -> {
                    e2ePeerKey.remove(arg(0) as String)
                    askedKey.remove(arg(0) as String)
                    claims.remove(arg(0) as String)
                    heldBoots.remove(arg(0) as String)
                    e2eEnabled.remove(arg(0) as String)
                    null
                }
                "setKexAskedKey" -> {
                    askedKey[arg(0) as String] = arg(1) as String
                    null
                }
                // ZC-634: the one write of a contact key, with its claim mark and asked record; this store always saves it.
                "storePeerKey" -> {
                    val at = arg(0) as String
                    e2ePeerKey[at] = arg(1) as String
                    if (arg(2) as Boolean) {
                        claims[at] = arg(1) as String
                    } else {
                        claims.remove(at)
                        heldBoots.remove(at)
                    }
                    if (arg(3) as Boolean) askedKey[at] = arg(1) as String
                    true
                }
                "getKeyClaim" -> claims[arg(0) as String]
                "markKeyClaim" ->
                    if (e2ePeerKey[arg(0) as String] == arg(1)) {
                        claims[arg(0) as String] = arg(1) as String
                        true
                    } else {
                        false
                    }
                "getHeldClaimBoot" -> heldBoots[arg(0) as String]
                "holdClaimedBoot" ->
                    if (claims.containsKey(arg(0) as String)) {
                        heldBoots[arg(0) as String] = arg(1) as String
                        true
                    } else {
                        false
                    }
                "releaseKeyClaim" -> {
                    val held = heldBoots.remove(arg(0) as String)
                    if (claims.remove(arg(0) as String) != null) held.orEmpty() else null
                }
                "getSentKexAckPubkey" -> sentKexAck[arg(0) as String]
                "setSentKexAckPubkey" -> {
                    val value = arg(1) as String?
                    sentKexAckWrites.add(value)
                    if (value == null) sentKexAck.remove(arg(0) as String) else sentKexAck[arg(0) as String] = value
                    // R5-2: a working store reports its write.
                    true
                }
                "getPeerNostrPubkey" -> null
                "getSentNostrBootPubkey" -> null
                // R10B-1: no first-message question quoted a ZBOOT for this contact (the per-contact allowance).
                "isZbootQuoted" -> false
                "setZbootQuoted" -> true
                // ZC-1842: as the real store answers it, from the keys this store holds (our private key, the contact's key).
                "isE2EKeyExchangeComplete" -> ourKeyStore[arg(0) as String]?.second != null && e2ePeerKey[arg(0) as String] != null
                "hasProcessedKexTx" -> arg(0) in processedKexTx
                "markKexTxProcessed" -> {
                    processedKexTx.add(arg(0) as String)
                    null
                }
                "setE2EKexTxId" -> null
                "isE2EEnabled" -> if (firstContact) arg(0) in e2eEnabled else true
                "setE2EEnabled" -> {
                    if (arg(1) as Boolean) e2eEnabled += arg(0) as String else e2eEnabled -= arg(0) as String
                    null
                }
                "tryConsumeKexAckBudget" -> {
                    budgetConsumed.incrementAndGet()
                    true
                }
                // ZC-1352: unset unless a test sets it; a clear of the mark is accepted.
                "isAutomaticSetupWithdrawn" -> args!![0] in setupWithdrawn
                "setAutomaticSetupWithdrawn" -> {
                    if (args!![1] as Boolean) setupWithdrawn += args[0] as String else setupWithdrawn -= args[0] as String
                    true
                }
                "isE2EKeyChanged" -> false
                // ZC-660: the answer reads whether our own key exchange is owed and which key an accept adopted. ZC-1842: from
                // this store's maps (empty unless a test or the code under test writes them).
                "isKexOwed" -> arg(0) in kexOwed
                "setKexOwed" -> {
                    if (arg(1) as Boolean) {
                        kexOwed += arg(0) as String
                    } else {
                        kexOwed -= arg(0) as String
                        kexOwedReasons.remove(arg(0) as String)
                    }
                    true
                }
                "getKexOwedReason" -> kexOwedReasons[arg(0) as String]
                "getAcceptedKey" -> acceptedKeys[arg(0) as String]
                "getConversationMode" -> mode
                // P2b: the KEX re-reads its paid-setup record before each attempt.
                "getHandshakePaidRecord" -> paidRecords[arg(0) as String]
                "setHandshakePaidRecord" -> {
                    val value = arg(1) as String?
                    if (value == null) paidRecords.remove(arg(0) as String) else paidRecords[arg(0) as String] = value
                    // R4-1: a working store reports its write.
                    true
                }
                // C5: the consent question drops another wallet's queued setups after a phrase change; one wallet here.
                "walletScopeGeneration" -> 0L
                // ZC1013-X3: the contacts with a conversation (the peer and every one the handler mapped).
                "getAllConversationPeerAddresses" ->
                    (convIdToPeer.values + peerToConvId.keys + receivedKex.keys).toSet()
                // ZC1013-X2: the stored "Not now" marks; a working store reports its write.
                "getDeclinedAutomaticSetups" -> {
                    if (declinedReadFails) throw IllegalStateException("synthetic store fault")
                    declinedSetups.toSet()
                }
                "setDeclinedAutomaticSetups" -> {
                    @Suppress("UNCHECKED_CAST")
                    val marks = arg(0) as Set<String>
                    declinedSetups.clear()
                    declinedSetups += marks
                    true
                }
                "isOwnBootSent" -> ownBootSent
                "setOwnBootSent" -> {
                    val sent = arg(1) as Boolean
                    ownBootSentWrites.add(sent)
                    ownBootSent = sent
                    // R5-2: a working store reports its write.
                    true
                }
                "toString" -> "HandshakePrefs"
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
 * The settlement seam under the real repository. Counts every proposal the use case asks for and every
 * broadcast. [failFirstProposalWith] makes the first proposal request throw (a "still confirming"
 * shortfall); [holdFirstSubmit] keeps the first broadcast open until [release].
 *
 * R4-1: [onFirstAttempt] runs inside the FIRST proposal request — after the caller's consent gate has
 * already let the attempt through — so a test can withdraw handshake-spend consent exactly in the window
 * where a move of the old balance clears it while the attempt is on the wire.
 */
internal class HandshakeSeam(
    private val peer: String,
    private val failFirstProposalWith: Exception? = null,
    private val holdFirstSubmit: Boolean = false,
    private val firstSubmitOutcome: SubmitResult = SubmitResult.Success(txIds = listOf("synthetic-first-txid")),
    private val onFirstAttempt: (() -> Unit)? = null,
    private val recording: Zc101RecordingProposalDataSource = Zc101RecordingProposalDataSource(),
    // C5: runs as each submit reaches the SDK, before it is counted (a test reads the stores at that moment).
    private val onSubmit: (() -> Unit)? = null,
) : ProposalDataSource by recording {
    val proposalRequests = AtomicInteger(0)
    val submits = AtomicInteger(0)
    val broadcasts: MutableList<String> = Collections.synchronizedList(mutableListOf())
    val firstSubmitEntered = CompletableDeferred<Unit>()
    val firstSubmitDone = CompletableDeferred<Unit>()
    val release = CompletableDeferred<Unit>()

    // SR-A (fix-all 2.13.0): the fee probe behind the chat's exact price questions ([feeProbe]; null keeps the
    // delegate's answer, which throws) and the network fee every proposal then carries ([proposalFee]; null keeps the
    // synthetic zero-step proposal). [probes] counts the price asks, [proposalUris] the ZIP 321 requests proposed.
    @Volatile var feeProbe: (() -> FeeProbe)? = null

    @Volatile var proposalFee: Long? = null
    val probes = AtomicInteger(0)
    val proposalUris: MutableList<String> = Collections.synchronizedList(mutableListOf())

    override suspend fun probeZip321Fee(
        account: WalletAccount,
        zip321Uri: String,
    ): FeeProbe {
        probes.incrementAndGet()
        return feeProbe?.invoke() ?: recording.probeZip321Fee(account, zip321Uri)
    }

    override suspend fun createZip321Proposal(
        account: WalletAccount,
        zip321Uri: String,
        allowMultiPayment: Boolean
    ): Zip321TransactionProposal {
        val n = proposalRequests.incrementAndGet()
        proposalUris += zip321Uri
        if (n == 1) onFirstAttempt?.invoke()
        if (n == 1 && failFirstProposalWith != null) throw failFirstProposalWith
        proposalFee?.let { fee ->
            return Zip321TransactionProposal(
                destination = WalletAddress.Unified.new(peer),
                amount = Zatoshi(1_000L),
                memo = Memo("synthetic-handshake-memo"),
                proposal = SweepProposalBytes.proposal(listOf(SweepProposalBytes.ironwood(1_000_000L)), fee),
            )
        }
        return syntheticZip321Proposal(peer, amount = 1_000L, memo = "synthetic-handshake-memo")
    }

    // F4: the 1:1 chat senders now call this overload (to record the stored ids before the submit). This fake
    // reports no stored ids: it settles through its own two-argument submit below, exactly as before F4.
    override suspend fun submitTransaction(
        proposal: Proposal,
        usk: UnifiedSpendingKey,
        onStored: co.electriccoin.zcash.ui.common.datasource.OnTransactionsStored,
    ): SubmitResult = submitTransaction(proposal, usk)

    override suspend fun submitTransaction(proposal: Proposal, usk: UnifiedSpendingKey): SubmitResult {
        onSubmit?.invoke()
        val n = submits.incrementAndGet()
        if (n == 1) {
            firstSubmitEntered.complete(Unit)
            if (holdFirstSubmit) release.await()
            val outcome = firstSubmitOutcome
            if (outcome is SubmitResult.Success) broadcasts += "synthetic-txid-$n"
            firstSubmitDone.complete(Unit)
            return outcome
        }
        broadcasts += "synthetic-txid-$n"
        return SubmitResult.Success(txIds = listOf("synthetic-txid-$n"))
    }
}

/** A controllable chain tip for the block-aware retry's waitForNextBlock. */
internal class HeightFeed {
    val height = MutableStateFlow<BlockHeight?>(BlockHeight.new(HandshakeHarness.START_HEIGHT))

    /** True once the retry loop is suspended waiting for the next block. */
    fun isWaiting(): Boolean = height.subscriptionCount.value > 0

    fun advance() {
        height.value = BlockHeight.new((height.value?.value ?: HandshakeHarness.START_HEIGHT) + 1)
    }

    private val synchronizer: Synchronizer =
        Proxy.newProxyInstance(
            Synchronizer::class.java.classLoader,
            arrayOf(Synchronizer::class.java),
        ) { p, method, args ->
            when (method.name) {
                "getNetworkHeight" -> height
                "toString" -> "HeightFeedSynchronizer"
                "hashCode" -> System.identityHashCode(p)
                "equals" -> p === args?.get(0)
                else -> throw UnsupportedOperationException("unexpected Synchronizer call: ${method.name}")
            }
        } as Synchronizer

    val provider: SynchronizerProvider =
        object : SynchronizerProvider {
            override val error = MutableStateFlow<SynchronizerError?>(null)
            override val synchronizer = MutableStateFlow<Synchronizer?>(this@HeightFeed.synchronizer)

            override suspend fun getSynchronizer(): Synchronizer = this@HeightFeed.synchronizer

            override fun resetSynchronizer() = Unit
        }
}

/**
 * Owns every job created by one send test, including the repository's detached submit scope.
 * The active owner only routes fixture construction; cancellation uses this instance's job set.
 * These Main-dispatcher tests are sequential. Reject overlap instead of cancelling another test.
 */
internal class SendHarnessScopes {
    private val jobs = linkedSetOf<Job>()
    private var closing = false

    fun open() {
        synchronized(lock) {
            check(active == null) { "Send fixture tests must not overlap" }
            check(jobs.isEmpty() && !closing) { "Send fixture owner cannot be reused" }
            active = this
        }
    }

    fun own(vm: ViewModel) = own(vm.viewModelScope.coroutineContext.job)

    /**
     * C5: this test's stand-in for ChatViewModel's process-wide held GROUP_ACCEPT scope. It runs on the test's
     * Main dispatcher, like a screen's own scope, and is cancelled with the test's other jobs.
     */
    val heldAcceptScope: CoroutineScope by lazy {
        CoroutineScope(SupervisorJob() + Dispatchers.Main).also { own(it.coroutineContext.job) }
    }

    fun own(repository: ZashiProposalRepositoryImpl) {
        val scope =
            ZashiProposalRepositoryImpl::class.java
                .getDeclaredField("scope")
                .apply { isAccessible = true }
                .get(repository) as CoroutineScope
        own(scope.coroutineContext.job)
    }

    private fun own(job: Job) {
        synchronized(lock) {
            check(active === this && !closing) { "Send fixture created outside its test lifetime" }
            jobs += job
        }
    }

    /** Must finish before Dispatchers.resetMain; held SDK seams are cancelled, never released to send. */
    fun close() {
        val owned =
            synchronized(lock) {
                check(active === this) { "Closing a different send fixture owner" }
                closing = true
                jobs.toList()
            }
        try {
            // Cancel ALL producers first: joining a ViewModel before cancelling its detached
            // repository can otherwise wait forever on the held settlement it is awaiting.
            owned.forEach { it.cancel() }
            runBlocking {
                withTimeout(HandshakeHarness.TIMEOUT_MS) { owned.joinAll() }
            }
        } finally {
            synchronized(lock) {
                jobs.clear()
                active = null
            }
        }
    }

    companion object {
        private val lock = Any()
        private var active: SendHarnessScopes? = null

        fun current(): SendHarnessScopes =
            synchronized(lock) { checkNotNull(active) { "Open a send fixture owner in @Before" } }
    }
}
