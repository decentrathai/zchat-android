package co.electriccoin.zcash.ui.screen.chat.viewmodel

import androidx.lifecycle.viewModelScope
import cash.z.ecc.android.sdk.model.Proposal
import cash.z.ecc.android.sdk.model.UnifiedSpendingKey
import cash.z.ecc.android.sdk.model.WalletBalance
import cash.z.ecc.android.sdk.model.Zatoshi
import co.electriccoin.zcash.ui.common.datasource.AccountDataSource
import co.electriccoin.zcash.ui.common.datasource.ProposalDataSource
import co.electriccoin.zcash.ui.common.datasource.Zip321TransactionProposal
import co.electriccoin.zcash.ui.common.model.SubmitResult
import co.electriccoin.zcash.ui.common.model.WalletAccount
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
import co.electriccoin.zcash.ui.screen.chat.model.ChatListState
import co.electriccoin.zcash.ui.screen.chat.model.ChatMessage
import co.electriccoin.zcash.ui.screen.chat.model.ConversationMode
import co.electriccoin.zcash.ui.screen.chat.model.SendMessageState
import co.electriccoin.zcash.ui.screen.chat.model.ZBootMessage
import co.electriccoin.zcash.ui.screen.chat.model.ZMSGProtocol
import co.electriccoin.zcash.ui.screen.chat.usecase.CreateChunkedMessageProposalUseCase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import org.junit.Assert.assertFalse
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * ZC-285, ZC-286 (fix-all 2.13.0, train T5b) — a Reset-encryption chat ([ResetFixture]: a Shielded contact with a finished
 * key exchange, a Quantum Shield key, handshake markers and a live ratchet) whose owed-KEX marks, quoted-ZBOOT allowance,
 * paid-setup records, the contact's key and the standing handshake consent are kept in maps, as one store shared by
 * every ViewModel of a test (the app's singleton). The chain tip ([heights]) and the settlement seam ([seam]) are shared
 * too, so a ViewModel made after a "kill" sees what the earlier one left.
 *
 * - [consent]: the standing "Allow handshake transactions" consent; [consentWrites] counts every write of it.
 * - [refusedWrites]: the mark writes (setKexOwed, setKexOwedReason, setKexOwedAccount, setZbootQuoted) that report "not
 *   saved" when they record something; clearing always reports saved.
 * - [markWrittenAfterWipe]: set when an owed mark or the allowance was recorded after something was already wiped.
 *
 * The owed reason (setKexOwedReason / getKexOwedReason) is the new API of ZC-285; the proxy answers it by name, so this
 * file compiles before it exists. Every address and key is synthetic.
 *
 * ZC-534: [zbootOwed] is the durable "our quoted ZBOOT is still owed" mark (isZbootOwed / setZbootOwed /
 * getZbootOwedPeers, answered by name as well); as in the real store, withdrawing or consuming the allowance removes it.
 * [tunnelPeers] is what getTunnelModePeers lists (empty unless a test adds its contact), and
 * [failFirstZbootWithStillConfirming] / [rejectFirstZbootBeforeStore] are [ResetKexSeam]'s.
 *
 * ZC-536, ZC-590, ZC-626: [refusedClears] names the writes (setKexOwed, setZbootQuoted) whose CLEARING reports "not saved"
 * and changes nothing, as a store whose commit failed (empty unless a test adds a name).
 */
internal class ResetOwedChat(
    val peer: String,
    consent: Boolean = true,
    failFirstKexWithStillConfirming: Boolean = false,
    mode: ConversationMode = ConversationMode.VAULT,
    failFirstZbootWithStillConfirming: Boolean = false,
    rejectFirstZbootBeforeStore: Boolean = false,
) {
    val reset = ResetFixture(peer, mode)
    val heights = HeightFeed()
    val seam =
        ResetKexSeam(
            peer,
            failFirstKexWithStillConfirming,
            failFirstZbootWithStillConfirming = failFirstZbootWithStillConfirming,
            rejectFirstZbootBeforeStore = rejectFirstZbootBeforeStore,
        )

    @Volatile var consent: Boolean = consent
    val consentWrites = AtomicInteger(0)
    val kexOwed: MutableSet<String> = ConcurrentHashMap.newKeySet()
    val kexOwedAccount = ConcurrentHashMap<String, String>()
    val kexOwedReason = ConcurrentHashMap<String, String>()
    val zbootQuoted = ConcurrentHashMap<String, Boolean>()
    val zbootOwed: MutableSet<String> = ConcurrentHashMap.newKeySet()
    val tunnelPeers: MutableSet<String> = ConcurrentHashMap.newKeySet()
    val paidRecords = ConcurrentHashMap<String, String>()
    val peerKeys = ConcurrentHashMap<String, String>()
    val refusedWrites: MutableSet<String> = ConcurrentHashMap.newKeySet()
    val refusedClears: MutableSet<String> = ConcurrentHashMap.newKeySet()

    @Volatile var markWrittenAfterWipe = false

    init {
        reset.proxy.getE2EPeerPublicKey(peer)?.let { peerKeys[peer] = it }
    }

    /** The store every ViewModel of this chat reads: [ResetFixture.proxy] with the maps above answering their calls. */
    val store: ZchatPreferences =
        intercept(
            reset.proxy,
            mapOf(
                "hasConsentedToHandshakeSpend" to { _ -> this.consent },
                "setConsentedToHandshakeSpend" to { _ ->
                    consentWrites.incrementAndGet()
                    true
                },
                "isKexOwed" to { a -> a!![0] in kexOwed },
                "getKexOwedPeers" to { _ -> kexOwed.toSet() },
                "setKexOwed" to { a ->
                    val p = a!![0] as String
                    if (a[1] as Boolean) {
                        record("setKexOwed") { kexOwed += p }
                    } else if ("setKexOwed" in refusedClears) {
                        false
                    } else {
                        kexOwed -= p
                        kexOwedAccount.remove(p)
                        kexOwedReason.remove(p)
                        true
                    }
                },
                "getKexOwedAccount" to { a -> kexOwedAccount[a!![0] as String] },
                "setKexOwedAccount" to { a ->
                    val p = a!![0] as String
                    val value = a[1] as String?
                    if (value == null) {
                        kexOwedAccount.remove(p)
                        true
                    } else {
                        record("setKexOwedAccount") { kexOwedAccount[p] = value }
                    }
                },
                "getKexOwedReason" to { a -> kexOwedReason[a!![0] as String] },
                "setKexOwedReason" to { a ->
                    val p = a!![0] as String
                    val value = a[1] as String?
                    if (value == null) {
                        kexOwedReason.remove(p)
                        true
                    } else {
                        record("setKexOwedReason") { kexOwedReason[p] = value }
                    }
                },
                "isZbootQuoted" to { a -> zbootQuoted[a!![0] as String] == true },
                "setZbootQuoted" to { a ->
                    val p = a!![0] as String
                    if (a[1] as Boolean) {
                        record("setZbootQuoted") { zbootQuoted[p] = true }
                    } else if ("setZbootQuoted" in refusedClears) {
                        false
                    } else {
                        zbootQuoted.remove(p)
                        // ZC-534: an owed mark never outlives its allowance (the real store removes both in one write).
                        zbootOwed -= p
                        true
                    }
                },
                "restoreQuotedZbootAsOwed" to { a ->
                    val p = a!![0] as String
                    record("restoreQuotedZbootAsOwed") {
                        zbootQuoted[p] = true
                        zbootOwed += p
                    }
                },
                "isZbootOwed" to { a -> a!![0] in zbootOwed },
                "getZbootOwedPeers" to { _ -> zbootOwed.toSet() },
                "setZbootOwed" to { a ->
                    val p = a!![0] as String
                    if (a[1] as Boolean) {
                        record("setZbootOwed") { zbootOwed += p }
                    } else {
                        zbootOwed -= p
                        true
                    }
                },
                "getTunnelModePeers" to { _ -> tunnelPeers.toList() },
                "getHandshakePaidRecord" to { a -> paidRecords[a!![0] as String] },
                "setHandshakePaidRecord" to { a ->
                    val slot = a!![0] as String
                    val value = a[1] as String?
                    if (value == null) paidRecords.remove(slot) else paidRecords[slot] = value
                    true
                },
                "getE2EPeerPublicKey" to { a -> peerKeys[a!![0] as String] },
                "setE2EPeerPublicKey" to { a ->
                    val p = a!![0] as String
                    val value = a[1] as String?
                    if (value == null) peerKeys.remove(p) else peerKeys[p] = value
                    null
                },
                // ZC-634: the one write of a contact key (no claim marks are modelled here); this store always saves it.
                "storePeerKey" to { a ->
                    peerKeys[a!![0] as String] = a[1] as String
                    true
                },
                "isE2EKeyExchangeComplete" to { a ->
                    val p = a!![0] as String
                    reset.proxy.getE2EOurPublicKey(p) != null && peerKeys.containsKey(p)
                },
                "clearE2EKeys" to { a ->
                    peerKeys.remove(a!![0] as String)
                    reset.proxy.clearE2EKeys(a[0] as String)
                    null
                },
                "isE2EKeyChanged" to { _ -> false },
                "getPendingMessages" to { _ -> emptyList<ZchatPreferences.PendingMessageData>() },
            ),
        )

    private fun record(
        name: String,
        write: () -> Unit,
    ): Boolean {
        if (name in refusedWrites) return false
        if (reset.wipes.isNotEmpty() || reset.ratchetDeletes.isNotEmpty()) markWrittenAfterWipe = true
        write()
        return true
    }

    /**
     * A chat screen's ViewModel over this chat's store, chain tip and seam. Unless [accounts] is given, the selected
     * account is a ZChat (Zashi) account whose Ironwood is still confirming, so a proposal refused for funds waits for
     * the next block. [planCheck] is M2's first-message plan check (Fundable when null).
     */
    suspend fun viewModel(
        accounts: AccountDataSource? = null,
        planCheck: FirstMessagePlanApi.ScriptedCheck? = null,
    ): ChatViewModel {
        val vm = reset.viewModel(accounts as? SelectedAccountFeed)
        val selected =
            accounts ?: run {
                val base = syntheticZashiAccount()
                Zc101FakeAccountDataSource(
                    base.copy(
                        unified =
                            base.unified.copy(
                                ironwood = WalletBalance(Zatoshi(0), Zatoshi(IRONWOOD_CONFIRMING), Zatoshi(0)),
                            ),
                    ),
                )
            }
        AutoSpendVm.setField(vm, "zchatPreferences", store)
        AutoSpendVm.setField(vm, "seedKeyProvider", SeedDerivedE2EKeyProvider(store) { null })
        AutoSpendVm.setField(vm, "synchronizerProvider", heights.provider)
        AutoSpendVm.setField(vm, "accountDataSource", selected)
        AutoSpendVm.setField(
            vm,
            "createChunkedMessageProposal",
            CreateChunkedMessageProposalUseCase(
                keystoneProposalRepository = Zc101FakeKeystoneProposalRepository(),
                zashiProposalRepository =
                    ZashiProposalRepositoryImpl(
                        accountDataSource = selected,
                        proposalDataSource = seam,
                        zashiSpendingKeyDataSource = Zc101FakeZashiSpendingKeyDataSource(),
                    ).also { SendHarnessScopes.current().own(it) },
                accountDataSource = selected,
                navigationRouter = Zc101RecordingNavigationRouter(),
                biometricRepository = HandshakeHarness.ApprovingBiometricRepository,
            ),
        )
        AutoSpendVm.setField(vm, "pendingMessages", MutableStateFlow(emptyList<ChatMessage>()))
        AutoSpendVm.setField(vm, "messageQueue", mutableListOf<Any?>())
        AutoSpendVm.setField(vm, "_chatListState", MutableStateFlow<ChatListState>(ChatListState.Loading))
        AutoSpendVm.setField(vm, "_showCostDisclaimer", MutableStateFlow(false))
        AutoSpendVm.setField(vm, "_blockHeight", MutableStateFlow<Long?>(null))
        AutoSpendVm.setField(vm, "addressCache", NoOpAddressCache.proxy)
        if (planCheck != null) FirstMessagePlanApi.install(vm, planCheck.proxyOrNull())
        return vm
    }

    /** A new block: the chain tip moves and [vm] runs its per-block work (the owed-KEX retry among it). */
    fun tick(vm: ChatViewModel) {
        heights.advance()
        ChatViewModel::class.java
            .getDeclaredMethod("onChainTip", Long::class.javaObjectType)
            .apply { isAccessible = true }
            .invoke(vm, heights.height.value?.value)
    }

    /** What the chat screen runs when it opens: the owed-KEX re-arm. */
    fun openChat(vm: ChatViewModel) {
        vm.retryOwedKeyExchange(peer)
    }

    /** The contact's KEXACK to our new key, carrying [peerKeys]'s public key (first contact: no relay key in it). */
    fun kexAckFromContact(
        vm: ChatViewModel,
        keys: E2EKeyPair,
        txId: String,
    ) {
        val memo =
            ZMSGProtocol.createV4KEXAckMessage(
                ResetFixture.CONV_ID,
                peer,
                E2EEncryption.createKEXAckPayload(peer, keys.publicKey, keys.privateKey),
            )
        HandshakeHarness.handleKexMemo(vm, memo, txId)
    }

    /**
     * ZC-534 — the contact's own ZBOOT (its relay identity [announcedRelayKey], signed with [keys], the key its KEXACK
     * carried) as [vm]'s inbound handler receives it. @return whether it verified and was taken.
     */
    fun zbootFromContact(
        vm: ChatViewModel,
        keys: E2EKeyPair,
        announcedRelayKey: String,
        epoch: Long = 0L,
    ): Boolean {
        val relay = "wss://relay.synthetic.example"
        val signature =
            E2EEncryption.sign(
                keys.privateKey,
                ZBootMessage.signedDataFor(ResetFixture.CONV_ID, announcedRelayKey, relay, epoch),
            )
        val raw = ZBootMessage(ResetFixture.CONV_ID, announcedRelayKey, relay, signature, epoch = epoch).serialize()
        return try {
            ChatViewModel::class.java
                .getDeclaredMethod("routeIncomingBoot", String::class.java, String::class.java)
                .apply { isAccessible = true }
                .invoke(vm, peer, raw) as Boolean
        } catch (e: InvocationTargetException) {
            throw e.targetException
        }
    }

    companion object {
        const val IRONWOOD_CONFIRMING = 200_000L

        /** "kill": the end of the process. Every screen and every process-wide send in flight stops; memory is lost. */
        suspend fun kill(vararg vms: ChatViewModel) {
            for (vm in vms) {
                vm.viewModelScope.coroutineContext.job
                    .cancelAndJoin()
            }
            HandshakeHarness.cancelHeldAcceptSends()
            HandshakeHarness
                .heldAcceptScope()
                ?.coroutineContext
                ?.job
                ?.children
                ?.forEach { it.join() }
            FirstMessagePlanChat.resetOwedKexState()
        }
    }
}

/**
 * Records every value [vm]'s send state takes from now on (collected unconfined, so a state that is replaced at once is
 * still seen), so a test can assert that a question never appeared.
 */
internal class SendStateRecorder(
    vm: ChatViewModel,
) {
    val states: MutableList<SendMessageState> = Collections.synchronizedList(mutableListOf())
    private val job: Job

    init {
        @Suppress("UNCHECKED_CAST")
        val flow =
            ChatViewModel::class.java
                .getDeclaredField("_sendMessageState")
                .apply { isAccessible = true }
                .get(vm) as MutableStateFlow<SendMessageState>
        job = CoroutineScope(Dispatchers.Unconfined).launch { flow.collect { states += it } }
    }

    fun stop() = job.cancel()

    /** Fails when the standing handshake consent question (or a second paid-action question) was ever shown. */
    fun assertNoHandshakeConsentQuestion(why: String) {
        val asked = states.toList().filter { it is SendMessageState.NeedsHandshakeSpendConsent }
        assertFalse("$why: ${asked.firstOrNull()}", asked.isNotEmpty())
    }
}

/**
 * The settlement seam under the real repository. Each proposal is told apart by its first memo: our KEX, our ZBOOT, or
 * anything else. [failFirstKexWithStillConfirming] refuses the first KEX proposal as "funds still confirming", so that
 * attempt waits for the next block.
 *
 * ZC-534: [failFirstZbootWithStillConfirming] does the same to the first ZBOOT proposal (the ZBOOT right behind a KEX
 * meets the locked note), and [rejectFirstZbootBeforeStore] refuses the first ZBOOT proposal for good before anything is
 * built or stored (a proven nothing-sent that does not wait). [zbootProposals] counts every ZBOOT proposal asked for.
 */
internal class ResetKexSeam(
    private val peer: String,
    private val failFirstKexWithStillConfirming: Boolean = false,
    private val recording: Zc101RecordingProposalDataSource = Zc101RecordingProposalDataSource(),
    private val failFirstZbootWithStillConfirming: Boolean = false,
    private val rejectFirstZbootBeforeStore: Boolean = false,
) : ProposalDataSource by recording {
    val kexProposals = AtomicInteger(0)
    val zbootProposals = AtomicInteger(0)
    val kexAnswered = AtomicInteger(0)
    val zbootAnswered = AtomicInteger(0)
    val otherAnswered = AtomicInteger(0)
    val submitted: MutableList<String> = Collections.synchronizedList(mutableListOf())

    /** ZC-535: every KEX memo a proposal was asked for, as built. */
    val kexMemos: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private val kinds = Collections.synchronizedMap(java.util.IdentityHashMap<Proposal, String>())

    fun proposals(): Int = kexAnswered.get() + zbootAnswered.get() + otherAnswered.get()

    fun submittedOf(kind: String): Int = submitted.count { it == kind }

    override suspend fun createZip321Proposal(
        account: WalletAccount,
        zip321Uri: String,
        allowMultiPayment: Boolean,
    ): Zip321TransactionProposal {
        val memo = firstMemo(zip321Uri)
        val kind =
            when {
                ZMSGProtocol.isKEXMessage(memo) -> "KEX"
                memo.startsWith("ZBOOT|") -> "ZBOOT"
                else -> "OTHER"
            }
        when (kind) {
            "KEX" -> {
                kexMemos += memo
                val n = kexProposals.incrementAndGet()
                if (n == 1 && failFirstKexWithStillConfirming) throw HandshakeHarness.stillConfirming()
                kexAnswered.incrementAndGet()
            }
            "ZBOOT" -> {
                val n = zbootProposals.incrementAndGet()
                if (n == 1 && failFirstZbootWithStillConfirming) throw HandshakeHarness.stillConfirming()
                if (n == 1 && rejectFirstZbootBeforeStore) {
                    throw IllegalStateException("synthetic: the ZBOOT proposal was refused before anything was built or stored")
                }
                zbootAnswered.incrementAndGet()
            }
            else -> otherAnswered.incrementAndGet()
        }
        val proposal = syntheticZip321Proposal(peer, amount = 1_000L, memo = "synthetic-$kind")
        kinds[proposal.proposal] = kind
        return proposal
    }

    override suspend fun submitTransaction(
        proposal: Proposal,
        usk: UnifiedSpendingKey,
        onStored: co.electriccoin.zcash.ui.common.datasource.OnTransactionsStored,
    ): SubmitResult = submitTransaction(proposal, usk)

    override suspend fun submitTransaction(
        proposal: Proposal,
        usk: UnifiedSpendingKey,
    ): SubmitResult {
        submitted += kinds[proposal] ?: "UNKNOWN"
        return SubmitResult.Success(txIds = listOf("synthetic-zc285-txid-${submitted.size}"))
    }
}

/** [delegate] with the calls named in [handlers] answered by them instead. */
private fun intercept(
    delegate: ZchatPreferences,
    handlers: Map<String, (Array<Any?>?) -> Any?>,
): ZchatPreferences =
    Proxy.newProxyInstance(
        ZchatPreferences::class.java.classLoader,
        arrayOf(ZchatPreferences::class.java),
    ) { p, method, args ->
        val handler = handlers[method.name]
        when {
            handler != null -> handler(args)
            method.name == "hashCode" -> System.identityHashCode(p)
            method.name == "equals" -> p === args?.get(0)
            method.name == "toString" -> "ResetOwedChatStore"
            else ->
                try {
                    method.invoke(delegate, *(args ?: emptyArray()))
                } catch (e: InvocationTargetException) {
                    throw e.targetException
                }
        }
    } as ZchatPreferences
