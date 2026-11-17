package co.electriccoin.zcash.ui.screen.chat.viewmodel

import androidx.lifecycle.viewModelScope
import cash.z.ecc.android.sdk.Synchronizer
import cash.z.ecc.android.sdk.model.Account
import cash.z.ecc.android.sdk.model.PersistableWallet
import cash.z.ecc.android.sdk.model.TransactionId
import co.electriccoin.zcash.ui.common.datasource.AccountDataSource
import co.electriccoin.zcash.ui.common.model.SubmitResult
import co.electriccoin.zcash.ui.common.model.SynchronizerError
import co.electriccoin.zcash.ui.common.model.WalletAccount
import co.electriccoin.zcash.ui.common.model.ZashiAccount
import co.electriccoin.zcash.ui.common.provider.PersistableWalletProvider
import co.electriccoin.zcash.ui.common.provider.SynchronizerProvider
import co.electriccoin.zcash.ui.common.repository.ExchangeRateRepository
import co.electriccoin.zcash.ui.common.repository.Transaction
import co.electriccoin.zcash.ui.common.repository.TransactionRepository
import co.electriccoin.zcash.ui.common.repository.ZashiProposalRepositoryImpl
import co.electriccoin.zcash.ui.common.usecase.GetDefaultUnifiedAddressUseCase
import co.electriccoin.zcash.ui.common.usecase.GetSelectedWalletAccountUseCase
import co.electriccoin.zcash.ui.common.usecase.Zc101FakeAccountDataSource
import co.electriccoin.zcash.ui.common.usecase.Zc101FakeKeystoneProposalRepository
import co.electriccoin.zcash.ui.common.usecase.Zc101FakeZashiSpendingKeyDataSource
import co.electriccoin.zcash.ui.common.usecase.Zc101RecordingNavigationRouter
import co.electriccoin.zcash.ui.common.usecase.syntheticZashiAccount
import co.electriccoin.zcash.ui.common.wallet.ExchangeRateState
import co.electriccoin.zcash.ui.screen.chat.crypto.E2EEncryption
import co.electriccoin.zcash.ui.screen.chat.crypto.E2EKeyPair
import co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferences
import co.electriccoin.zcash.ui.screen.chat.model.AnnounceState
import co.electriccoin.zcash.ui.screen.chat.model.Contact
import co.electriccoin.zcash.ui.screen.chat.model.ContactBook
import co.electriccoin.zcash.ui.screen.chat.model.GroupInfo
import co.electriccoin.zcash.ui.screen.chat.model.GroupMember
import co.electriccoin.zcash.ui.screen.chat.model.InviteStatus
import co.electriccoin.zcash.ui.screen.chat.model.MemberStatus
import co.electriccoin.zcash.ui.screen.chat.model.ZMSGGroupProtocol
import co.electriccoin.zcash.ui.screen.chat.usecase.CreateChunkedMessageProposalUseCase
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.job
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.withTimeout
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.security.SecureRandom
import java.time.Instant
import java.util.Base64
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * B1 (round 7) — the admin's side of one group at key epoch 1, for the "a group memo is paid at most once"
 * tests. We created [GROUP_ID]; [MEMBER_P] is in it with a KEX session with us and, unless a test says
 * otherwise, no Nostr key, so every memo to P goes on-chain.
 *
 * [firstSubmitOutcome] is what the SDK reports for the first on-chain submit; every later submit succeeds.
 * `SubmitResult.Failure` with a transaction id is the "stored, then rejected" case: the real
 * CreateChunkedMessageProposalUseCase turns it into `SubmitRejectedException(nothingStored = false)`, and
 * the SDK may still broadcast that stored transaction.
 *
 * The roster lives in this fixture unless [membersStore] is given; then every group-members read and write
 * (`getGroupMembers`, `saveGroupMembers`, and any other `*GroupMembers*` call) is forwarded to that store
 * by name, so a test can put a real ZchatPreferencesImpl with a memory/disk split behind it. Methods are
 * matched by name so this fixture compiles against any version of the interface.
 */
internal class GroupPaidOnceFixture(
    memberStatus: MemberStatus = MemberStatus.ACTIVE,
    memberInviteStatus: InviteStatus? = InviteStatus.SENT,
    memberPendingKeyEpoch: Int? = null,
    firstSubmitOutcome: SubmitResult = SubmitResult.Success(txIds = listOf("synthetic-first-txid")),
    @Volatile var consent: Boolean = true,
    /** The Nostr key P is known by on the [n]th lookup (0-based); null = not reachable free on that lookup. */
    private val nostrKeyForP: (n: Int) -> String? = { null },
    /** What a durable (checked) roster write reports when [membersStore] is null. */
    @Volatile var durableWritesSucceed: Boolean = true,
    private val membersStore: ZchatPreferences? = null,
    /** C1 — thrown by the FIRST proposal request (before anything is stored or submitted); later ones succeed. */
    failFirstProposalWith: Exception? = null,
    /** C1 — the selected account; null = the synthetic Zashi account with empty balances. */
    private val selectedAccount: (suspend () -> WalletAccount)? = null,
    /** C4 — more ACTIVE members after [MEMBER_P], in roster order (no KEX session, no Nostr key: paid on-chain). */
    extraActiveMembers: List<String> = emptyList(),
    /** ZC-289 — false: P never key-exchanged with us (no session, no E2E key of theirs), as in an all-Open group. */
    private val memberHasSession: Boolean = true,
) {
    val seam = HandshakeSeam(MEMBER_P, failFirstProposalWith = failFirstProposalWith, firstSubmitOutcome = firstSubmitOutcome)

    /** Advanced while [awaitNewJobs] waits, so a delay() inside the action completes. */
    var scheduler: TestCoroutineScheduler? = null

    private val adminKeys: E2EKeyPair = E2EEncryption.generateKeyPair()
    private val memberKeys: E2EKeyPair = E2EEncryption.generateKeyPair()
    private val groupKeys = ConcurrentHashMap<String, String>()
    private val groupInfos = ConcurrentHashMap<String, String>()
    private val groupMembers = ConcurrentHashMap<String, String>()
    private val announceStates = ConcurrentHashMap<String, AnnounceState>()
    private val epochs = ConcurrentHashMap<String, Int>()
    private val drafts = ConcurrentHashMap<String, String>()
    private val messageSpendConsents = ConcurrentHashMap<String, Set<String>>()
    private val nostrLookups = AtomicInteger()

    /** Every method name this fixture answered with an empty default, for diagnosing a failure. */
    val defaultedCalls: MutableList<String> = Collections.synchronizedList(mutableListOf())

    /** KI-23: the paid deliveries of a group key per grant key (the creator's Resend key writes one before it pays). */
    val keyGrantPaidCounts = ConcurrentHashMap<String, Int>()

    /** The roster the group starts with; a test with a [membersStore] seeds its disk with this. */
    val initialRosterJson: String =
        ZMSGGroupProtocol.serializeGroupMembers(
            listOf(
                GroupMember(address = SELF, joinedAt = Instant.ofEpochMilli(1_000L), status = MemberStatus.ACTIVE, isAdmin = true),
                GroupMember(
                    address = MEMBER_P,
                    joinedAt = Instant.ofEpochMilli(1_000L),
                    status = memberStatus,
                    inviteStatus = memberInviteStatus,
                    pendingKeyEpoch = memberPendingKeyEpoch,
                ),
            ) +
                extraActiveMembers.map { address ->
                    GroupMember(
                        address = address,
                        joinedAt = Instant.ofEpochMilli(1_000L),
                        status = MemberStatus.ACTIVE,
                        inviteStatus = InviteStatus.SENT,
                    )
                },
        )

    init {
        groupKeys["$GROUP_ID|1"] = Base64.getEncoder().encodeToString(ByteArray(32).also { SecureRandom().nextBytes(it) })
        epochs[GROUP_ID] = 1
        groupInfos[GROUP_ID] =
            ZMSGGroupProtocol.serializeGroupInfo(
                GroupInfo(groupId = GROUP_ID, name = "Paid once", creatorAddress = SELF, createdAt = Instant.ofEpochMilli(1_000L)),
            )
        if (membersStore == null) groupMembers[GROUP_ID] = initialRosterJson
    }

    fun rosterJson(): String? = proxy.getGroupMembers(GROUP_ID)

    fun memberRow(): GroupMember = ZMSGGroupProtocol.deserializeGroupMembers(rosterJson()!!).single { it.address == MEMBER_P }

    fun draft(): String? = drafts[GROUP_ID]

    fun epoch(): Int = epochs[GROUP_ID] ?: 0

    /** On-chain submits made so far (each one is a paid transaction). */
    fun submits(): Int = seam.submits.get()

    /**
     * Run [block] and wait for the jobs it launched, including those they launch in turn. Jobs already
     * running before [block] (loadCurrentUserAddress never finishes here) are not waited for.
     */
    suspend fun awaitNewJobs(
        vm: GroupViewModel,
        block: suspend () -> Unit
    ) {
        val before: Set<Job> =
            vm.viewModelScope.coroutineContext.job.children
                .toSet()
        block()
        withTimeout(HandshakeHarness.TIMEOUT_MS) {
            while (true) {
                val started =
                    vm.viewModelScope.coroutineContext.job.children
                        .filter { it !in before && it.isActive }
                        .toList()
                if (started.isEmpty()) break
                scheduler?.advanceUntilIdle()
                kotlinx.coroutines.delay(5)
            }
        }
    }

    suspend fun groupViewModel(): GroupViewModel {
        val owner = SendHarnessScopes.current()
        val accountDataSource: AccountDataSource =
            selectedAccount?.let { SelectedAccountDataSource(it()) } ?: Zc101FakeAccountDataSource(syntheticZashiAccount())
        val synchronizerProvider = HangingSynchronizerProvider()
        val getSelectedWalletAccount = GetSelectedWalletAccountUseCase(accountDataSource)
        val vm =
            GroupViewModel(
                transactionRepository = EmptyTransactionRepository(),
                getSelectedWalletAccount = getSelectedWalletAccount,
                getDefaultUnifiedAddress = GetDefaultUnifiedAddressUseCase(synchronizerProvider, getSelectedWalletAccount),
                accountDataSource = accountDataSource,
                createChunkedMessageProposal =
                    CreateChunkedMessageProposalUseCase(
                        keystoneProposalRepository = Zc101FakeKeystoneProposalRepository(),
                        zashiProposalRepository =
                            ZashiProposalRepositoryImpl(
                                accountDataSource = accountDataSource,
                                proposalDataSource = seam,
                                zashiSpendingKeyDataSource = Zc101FakeZashiSpendingKeyDataSource(),
                            ).also(owner::own),
                        accountDataSource = accountDataSource,
                        navigationRouter = Zc101RecordingNavigationRouter(),
                        biometricRepository = HandshakeHarness.ApprovingBiometricRepository,
                    ),
                zchatPreferences = proxy,
                synchronizerProvider = synchronizerProvider,
                exchangeRateRepository = OptedOutExchangeRateRepository(),
                contactBook = NoContacts(),
                persistableWalletProvider = NoSeedWalletProvider(),
            ).also(owner::own)
        @Suppress("UNCHECKED_CAST")
        (
            GroupViewModel::class.java
                .getDeclaredField("_currentUserAddress")
                .apply { isAccessible = true }
                .get(vm) as MutableStateFlow<String?>
        ).value = SELF
        return vm
    }

    private fun forwardToMembersStore(
        store: ZchatPreferences,
        method: Method,
        args: Array<Any?>?,
    ): Any? =
        store.javaClass
            .getMethod(method.name, *method.parameterTypes)
            .invoke(store, *(args ?: emptyArray()))

    @Suppress("CyclomaticComplexMethod", "LongMethod")
    val proxy: ZchatPreferences =
        Proxy.newProxyInstance(
            ZchatPreferences::class.java.classLoader,
            arrayOf(ZchatPreferences::class.java),
        ) { p, method, args ->
            fun arg(i: Int) = args!![i]
            val store = membersStore
            if (store != null && method.name.contains("GroupMembers")) {
                return@newProxyInstance forwardToMembersStore(store, method, args)
            }
            if (store != null && method.name.contains("GroupAnnounce")) {
                return@newProxyInstance forwardToMembersStore(store, method, args)
            }
            when (method.name) {
                "getGroupAnnounceState" -> announceStates[arg(0) as String] ?: AnnounceState()
                "saveGroupAnnounceState" -> {
                    announceStates[arg(0) as String] = arg(1) as AnnounceState
                    true
                }
                "getAllGroupIds" -> groupInfos.keys.toSet()
                "getGroupInfo" -> groupInfos[arg(0) as String]
                "saveGroupInfo" -> {
                    groupInfos[arg(0) as String] = arg(1) as String
                    null
                }
                "getGroupMembers" -> groupMembers[arg(0) as String]
                "saveGroupMembers" -> {
                    groupMembers[arg(0) as String] = arg(1) as String
                    null
                }
                // A checked roster write: stored only when it "reaches the disk".
                "saveGroupMembersDurably" -> {
                    if (durableWritesSucceed && args!!.size == 3) {
                        announceStates[arg(0) as String] = arg(2) as AnnounceState
                    }
                    if (durableWritesSucceed) groupMembers[arg(0) as String] = arg(1) as String
                    durableWritesSucceed
                }
                "isGroupSelfCreated" -> arg(0) == GROUP_ID
                "isSelfAddress" -> arg(0) == SELF
                "getGroupKeyEpoch" -> epochs[arg(0) as String] ?: 0
                "setGroupKeyEpoch" -> {
                    epochs[arg(0) as String] = arg(1) as Int
                    null
                }
                "getGroupKey" -> groupKeys["${arg(0)}|${arg(1)}"]
                "saveGroupKey" -> {
                    groupKeys["${arg(0)}|${arg(1)}"] = arg(2) as String
                    null
                }
                "setGroupRemovalFloor" -> null
                "resolvePeerAddress" -> arg(0)
                "getE2EPrivateKey" -> if (memberHasSession && arg(0) == MEMBER_P) adminKeys.privateKey else null
                "getE2EOurPublicKey" -> if (memberHasSession && arg(0) == MEMBER_P) adminKeys.publicKey else null
                "getE2EPeerPublicKey" -> if (memberHasSession && arg(0) == MEMBER_P) memberKeys.publicKey else null
                "getE2EKeyVersion" -> 2
                "getPeerNostrPubkey" -> if (arg(0) == MEMBER_P) nostrKeyForP(nostrLookups.getAndIncrement()) else null
                "getAllConversationPeerAddresses" -> emptySet<String>()
                "hasConsentedToHandshakeSpend" -> consent
                "getGroupMessageSequence" -> 0L
                "incrementGroupMessageSequence" -> 1L
                "getGroupDraft" -> drafts[arg(0) as String]
                "setGroupDraft" -> {
                    drafts[arg(0) as String] = arg(1) as String
                    null
                }
                "clearGroupDraft" -> {
                    drafts.remove(arg(0) as String)
                    null
                }
                "getGroupMessageSpendConsent" -> messageSpendConsents[arg(0) as String].orEmpty()
                "setGroupMessageSpendConsent" -> {
                    @Suppress("UNCHECKED_CAST")
                    messageSpendConsents[arg(0) as String] = (arg(1) as Collection<String>).toSet()
                    true
                }
                "getGroupKeyGrantPaidCount" -> keyGrantPaidCounts[arg(0) as String] ?: 0
                "setGroupKeyGrantPaidCount" -> {
                    val count = arg(1) as Int
                    if (count > 0) keyGrantPaidCounts[arg(0) as String] = count else keyGrantPaidCounts.remove(arg(0) as String)
                    true
                }
                "toString" -> "GroupPaidOnceFixture"
                "hashCode" -> System.identityHashCode(p)
                "equals" -> p === args?.get(0)
                else -> {
                    defaultedCalls += method.name
                    emptyValueFor(method.returnType)
                }
            }
        } as ZchatPreferences

    private fun emptyValueFor(type: Class<*>): Any? =
        when (type) {
            java.lang.Boolean.TYPE -> false
            java.lang.Integer.TYPE -> 0
            java.lang.Long.TYPE -> 0L
            List::class.java -> emptyList<Any>()
            Set::class.java -> emptySet<Any>()
            Map::class.java -> emptyMap<Any, Any>()
            else -> null
        }

    /** C1 — any selected account (a Keystone one, or a Zashi one whose value is all in sealed Orchard). */
    private class SelectedAccountDataSource(
        private val account: WalletAccount,
    ) : AccountDataSource {
        override val allAccounts: StateFlow<List<WalletAccount>?> = MutableStateFlow(listOf(account))
        override val selectedAccount: Flow<WalletAccount?> = flowOf(account)
        override val zashiAccount: Flow<ZashiAccount?> = flowOf(account as? ZashiAccount)

        override suspend fun getAllAccounts(): List<WalletAccount> = listOf(account)

        override suspend fun getSelectedAccount(): WalletAccount = account

        override suspend fun getZashiAccount(): ZashiAccount = account as? ZashiAccount ?: error("no Zashi account in this test")

        override suspend fun selectAccount(account: Account) = Unit

        override suspend fun selectAccount(account: WalletAccount) = Unit

        override suspend fun importKeystoneAccount(
            ufvk: String,
            seedFingerprint: String,
            index: Long,
        ): Account = error("not used in this test")

        override suspend fun requestNextShieldedAddress() = Unit
    }

    private class HangingSynchronizerProvider : SynchronizerProvider {
        override val error: StateFlow<SynchronizerError?> = MutableStateFlow(null)
        override val synchronizer: StateFlow<Synchronizer?> = MutableStateFlow(null)

        override suspend fun getSynchronizer(): Synchronizer = awaitCancellation()

        override fun resetSynchronizer() = Unit
    }

    private class OptedOutExchangeRateRepository : ExchangeRateRepository {
        override val state: StateFlow<ExchangeRateState> = MutableStateFlow(ExchangeRateState.OptedOut)

        override fun refreshExchangeRateUsd() = Unit
    }

    private class EmptyTransactionRepository : TransactionRepository {
        override val transactions: Flow<List<Transaction>?> = flowOf(null)

        override suspend fun getMemos(transaction: Transaction): List<String> = emptyList()

        override fun observeTransaction(txId: String): Flow<Transaction?> = flowOf(null)

        override fun observeTransactionsByMemo(memo: String): Flow<List<TransactionId>?> = flowOf(null)

        override suspend fun getTransactions(): List<Transaction> = emptyList()

        override suspend fun getAllRecipientAddresses(transaction: Transaction): List<String> = emptyList()
    }

    private class NoContacts : ContactBook {
        override fun addContact(contact: Contact) = Unit

        override fun removeContact(address: String) = Unit

        override fun getContact(address: String): Contact? = null

        override fun getAllContacts(): List<Contact> = emptyList()

        override fun hasContact(address: String): Boolean = false

        override fun updateContactName(
            address: String,
            newName: String
        ) = Unit

        override fun clearAll() = Unit
    }

    private class NoSeedWalletProvider : PersistableWalletProvider {
        override val persistableWallet: Flow<PersistableWallet?> = flowOf(null)

        override suspend fun store(persistableWallet: PersistableWallet) = Unit

        override suspend fun getPersistableWallet(): PersistableWallet? = null

        override suspend fun requirePersistableWallet(): PersistableWallet = error("seed path not used in this test")
    }

    companion object {
        const val GROUP_ID = "zgrp_paidoncetest"
        const val SELF = "usyntheticpaidonceadmin00001"
        const val MEMBER_P = "usyntheticpaidoncememberp001"
        const val SYNTHETIC_NOSTR_PUBKEY = "6e7e7e7e6e7e7e7e6e7e7e7e6e7e7e7e6e7e7e7e6e7e7e7e6e7e7e7e6e7e7e7e"

        /** The "stored, then rejected" submit: the SDK holds transaction T and may still broadcast it. */
        fun storedThenRejected(): SubmitResult =
            SubmitResult.Failure(txIds = listOf("synthetic-stored-T"), code = 18, description = "rejected")
    }
}
