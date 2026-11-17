package co.electriccoin.zcash.ui.screen.chat.viewmodel

import cash.z.ecc.android.bip39.Mnemonics
import cash.z.ecc.android.bip39.toSeed
import cash.z.ecc.android.sdk.model.Proposal
import cash.z.ecc.android.sdk.model.UnifiedSpendingKey
import co.electriccoin.zcash.ui.common.datasource.AccountDataSource
import co.electriccoin.zcash.ui.common.datasource.ProposalDataSource
import co.electriccoin.zcash.ui.common.datasource.Zip321TransactionProposal
import co.electriccoin.zcash.ui.common.model.SubmitResult
import co.electriccoin.zcash.ui.common.model.WalletAccount
import co.electriccoin.zcash.ui.common.repository.Transaction
import co.electriccoin.zcash.ui.common.repository.TransactionRepository
import co.electriccoin.zcash.ui.common.repository.ZashiProposalRepositoryImpl
import co.electriccoin.zcash.ui.common.usecase.Zc101FakeKeystoneProposalRepository
import co.electriccoin.zcash.ui.common.usecase.Zc101FakeZashiSpendingKeyDataSource
import co.electriccoin.zcash.ui.common.usecase.Zc101RecordingNavigationRouter
import co.electriccoin.zcash.ui.common.usecase.Zc101RecordingProposalDataSource
import co.electriccoin.zcash.ui.common.usecase.syntheticZip321Proposal
import co.electriccoin.zcash.ui.nostr.NOSTRIdentity
import co.electriccoin.zcash.ui.nostr.NostrChatBridge
import co.electriccoin.zcash.ui.screen.chat.crypto.E2EEncryption
import co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferences
import co.electriccoin.zcash.ui.screen.chat.model.ZBootMessage
import co.electriccoin.zcash.ui.screen.chat.model.ZMSGProtocol
import co.electriccoin.zcash.ui.screen.chat.usecase.CreateChunkedMessageProposalUseCase
import kotlinx.coroutines.flow.MutableStateFlow
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Modifier
import java.time.Instant
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn

/**
 * Shared by the T6a fix-up regressions of the automatic relay announcements ("ZBOOT": ZC-785, ZC-773, ZC-789). It
 * drives the REAL history conversion pass, the real per-block retry and the real senders of a [ChatViewModel] built by
 * the existing fixtures ([ResetOwedChat], [KeyChangeReconnectChat]); nothing here stands in for product code. Every
 * address, key and memo is synthetic.
 */
internal object ZbootSetup {
    /** A relay identity our wallet used before its current one (it is never the fixture seed's identity). */
    const val EARLIER_IDENTITY = "7e5f0c4d2a1b9e8f7a6b5c4d3e2f1a0b9c8d7e6f5a4b3c2d1e0f9a8b7c6d5e4f"

    /** Our own ZBOOT carrying [identity] in [ResetFixture]'s conversation, as a history pass reads it from the chain. */
    fun boot(identity: String): String =
        ZBootMessage(
            ResetFixture.CONV_ID,
            identity,
            "wss://relay.synthetic.example",
            "synthetic-own-history-signature",
            epoch = 0L,
        ).serialize()

    /** Our current relay identity, derived here from the fixture seed and the store's rotation index (never registered). */
    suspend fun currentIdentity(vm: ChatViewModel): String {
        val preferences =
            ChatViewModel::class.java
                .getDeclaredField("zchatPreferences")
                .apply { isAccessible = true }
                .get(vm) as ZchatPreferences
        val wallet = FixtureSeedWalletProvider.requirePersistableWallet()
        val seed = Mnemonics.MnemonicCode(wallet.seedPhrase.joinToString()).toSeed()
        try {
            return NOSTRIdentity
                .fromSeed(seed, preferences.getNostrRotationIndex())
                .publicKey
                .joinToString("") { "%02x".format(it) }
        } finally {
            seed.fill(0)
        }
    }

    /**
     * One real history conversion pass of [vm] over our own sent transactions to [recipient], one per entry of [memos],
     * oldest first. The SDK names no recipient for them, so a bare ZBOOT is attributed by its conversation id, as in
     * AMinedZbootOfAnEarlierRelayIdentityDoesNotCancelTheOwedOneTest. [beforeMemo] runs before the n-th memo is read
     * (a test parks the pass there); the reads named in [failingReads] throw, which the pass counts as a memo it could
     * not read; [scanComplete] is what the wallet says about its history scan. The pass leaves its own synchronizer
     * behind, so a test that ticks blocks afterwards sets its height feed again.
     */
    @Suppress("UNCHECKED_CAST", "LongParameterList")
    suspend fun historyPass(
        vm: ChatViewModel,
        recipient: String,
        memos: List<String>,
        mined: Boolean = true,
        failingReads: Set<Int> = emptySet(),
        scanComplete: Boolean = true,
        beforeMemo: suspend (Int) -> Unit = {},
    ) {
        AutoSpendVm.setField(vm, "restoreRecheck", MutableStateFlow(0))
        val txs =
            memos.mapIndexed { index, memo ->
                PendingReconcileHarness.SentTx(
                    number = 785 + index,
                    recipient = recipient,
                    memo = memo,
                    minedHeight = if (mined) 3_000_100L + index else null,
                    blockTime = if (mined) Instant.ofEpochSecond(1_700_000_000L + index) else null,
                )
            }
        val installer =
            PendingReconcileHarness::class.java
                .getDeclaredMethod("install", ChatViewModel::class.java, List::class.java)
                .apply { isAccessible = true }
        val transactions = unwrapped { installer.invoke(PendingReconcileHarness, vm, txs) } as List<Transaction>
        val repositoryField = ChatViewModel::class.java.getDeclaredField("transactionRepository").apply { isAccessible = true }
        val repository = repositoryField.get(vm) as TransactionRepository
        val reads = AtomicInteger(0)
        val observed =
            object : TransactionRepository by repository {
                override suspend fun getAllRecipientAddresses(transaction: Transaction): List<String> = emptyList()

                override suspend fun getMemos(transaction: Transaction): List<String> {
                    val index = reads.getAndIncrement()
                    beforeMemo(index)
                    if (index in failingReads) throw IllegalStateException("synthetic: this memo could not be read")
                    return repository.getMemos(transaction)
                }
            }
        AutoSpendVm.setField(vm, "transactionRepository", observed)
        val conversion =
            ChatViewModel::class.java.declaredMethods
                .single { it.name == "convertToConversations" && it.parameterCount == 7 }
                .apply { isAccessible = true }
        suspendCoroutineUninterceptedOrReturn<Any?> { continuation ->
            unwrapped {
                conversion.invoke(
                    vm,
                    transactions,
                    HandshakeHarness.SELF,
                    emptySet<String>(),
                    emptyList<Any>(),
                    emptySet<String>(),
                    scanComplete,
                    continuation,
                )
            }
        }
    }

    /** Whether the process-wide "the history was read" gate of the automatic ZBOOTs is open. */
    fun historyRead(): Boolean =
        ChatViewModel::class.java
            .getDeclaredField("processHistoryRead")
            .apply { isAccessible = true }
            .get(null)
            .let { (it as AtomicBoolean).get() }

    /** A second Tunnel contact in [chat]'s store: keys both ways, no relay key, our relay identity never delivered. */
    fun addTunnelContact(
        chat: ResetOwedChat,
        other: String,
    ) {
        val ours = E2EEncryption.generateKeyPair()
        chat.store.setE2EOurKeys(other, ours.publicKey, ours.privateKey)
        chat.peerKeys[other] = E2EEncryption.generateKeyPair().publicKey
        chat.tunnelPeers += other
    }

    /** Puts [seam] under [vm]'s real use case and repository (the selected account stays the one the fixture set). */
    fun installSeam(
        vm: ChatViewModel,
        seam: ProposalDataSource,
    ) {
        val accounts =
            ChatViewModel::class.java
                .getDeclaredField("accountDataSource")
                .apply { isAccessible = true }
                .get(vm) as AccountDataSource
        val repository =
            ZashiProposalRepositoryImpl(
                accountDataSource = accounts,
                proposalDataSource = seam,
                zashiSpendingKeyDataSource = Zc101FakeZashiSpendingKeyDataSource(),
            ).also { SendHarnessScopes.current().own(it) }
        AutoSpendVm.setField(
            vm,
            "createChunkedMessageProposal",
            CreateChunkedMessageProposalUseCase(
                keystoneProposalRepository = Zc101FakeKeystoneProposalRepository(),
                zashiProposalRepository = repository,
                accountDataSource = accounts,
                navigationRouter = Zc101RecordingNavigationRouter(),
                biometricRepository = HandshakeHarness.ApprovingBiometricRepository,
            ),
        )
    }

    /** Whether a ZBOOT sender to [peer] holds the process-wide in-flight claim. */
    fun zbootInFlight(peer: String): Boolean = peer in (staticField("nostrBootInFlight") as Collection<*>)

    /** Drops every process-wide entry an automatic-setup test may leave (what the end of the process does). */
    fun resetProcess() {
        AutoSpendVm.resetProcessState()
        FirstMessagePlanChat.resetOwedKexState()
        NostrChatBridge.unregisterPublisher()
        for (name in PROCESS_WIDE) {
            val value = staticField(name)
            (value as? MutableCollection<*>)?.clear()
            (value as? MutableMap<*, *>)?.clear()
        }
    }

    suspend fun kill(vararg vms: ChatViewModel) {
        ResetOwedChat.kill(*vms)
        resetProcess()
    }

    private val PROCESS_WIDE =
        listOf(
            "zbootWorkByScope",
            "unsavedZbootWithdrawals",
            "unsentZbootQuotes",
            "nostrBootInFlight",
            "reconnectsInFlight",
            "modeSwitchPicks",
            "modeSyncInFlight",
            "kexAckedKeys",
            "peerSessionLocks",
            "peerSessionEpochs",
        )

    private fun staticField(name: String): Any? =
        ChatViewModel::class.java.declaredFields
            .firstOrNull { it.name == name && Modifier.isStatic(it.modifiers) }
            ?.apply { isAccessible = true }
            ?.get(null)

    private fun unwrapped(call: () -> Any?): Any? =
        try {
            call()
        } catch (e: InvocationTargetException) {
            throw e.targetException
        }
}

/**
 * The settlement seam under the real repository, telling each proposal apart by its first memo like [ResetKexSeam]
 * ("KEX", "ZBOOT", anything else "OTHER"), with two hooks a test sets:
 * - [beforeProposal] runs inside the n-th proposal request of a kind, after the sender's own checks and stamps and
 *   before anything is built: it may park there, and what it throws is that proposal's failure;
 * - [submitOutcome] is what the n-th submit of a kind reports once it reached the SDK (null: accepted); it may park too.
 */
internal class HookedSetupSeam(
    private val peer: String,
    private val recording: Zc101RecordingProposalDataSource = Zc101RecordingProposalDataSource(),
) : ProposalDataSource by recording {
    private val asked = ConcurrentHashMap<String, AtomicInteger>()
    private val reached = ConcurrentHashMap<String, AtomicInteger>()
    private val kinds = Collections.synchronizedMap(java.util.IdentityHashMap<Proposal, String>())

    /** The kind of every submit that reached the SDK, in order. */
    val submitted: MutableList<String> = Collections.synchronizedList(mutableListOf())

    @Volatile var beforeProposal: (suspend (kind: String, n: Int) -> Unit)? = null

    @Volatile var submitOutcome: (suspend (kind: String, n: Int) -> SubmitResult?)? = null

    fun proposalsOf(kind: String): Int = asked[kind]?.get() ?: 0

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
        val n = asked.getOrPut(kind) { AtomicInteger(0) }.incrementAndGet()
        beforeProposal?.invoke(kind, n)
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
        val kind = kinds[proposal] ?: "UNKNOWN"
        submitted += kind
        val n = reached.getOrPut(kind) { AtomicInteger(0) }.incrementAndGet()
        return submitOutcome?.invoke(kind, n) ?: SubmitResult.Success(txIds = listOf("synthetic-zboot-setup-txid-${submitted.size}"))
    }

    companion object {
        /** A submit refused after the SDK stored the transaction: it may still be broadcast, so its outcome is unknown. */
        fun storedThenRejected(): SubmitResult =
            SubmitResult.Failure(txIds = listOf("synthetic-stored-zboot"), code = 18, description = "rejected")
    }
}
