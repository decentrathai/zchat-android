package co.electriccoin.zcash.ui.screen.chat.viewmodel

import androidx.lifecycle.viewModelScope
import cash.z.ecc.android.sdk.Synchronizer
import cash.z.ecc.android.sdk.model.PersistableWallet
import cash.z.ecc.android.sdk.model.Proposal
import cash.z.ecc.android.sdk.model.TransactionId
import cash.z.ecc.android.sdk.model.UnifiedSpendingKey
import co.electriccoin.zcash.ui.common.datasource.ProposalDataSource
import co.electriccoin.zcash.ui.common.datasource.Zip321TransactionProposal
import co.electriccoin.zcash.ui.common.model.SubmitResult
import co.electriccoin.zcash.ui.common.model.SynchronizerError
import co.electriccoin.zcash.ui.common.model.WalletAccount
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
import co.electriccoin.zcash.ui.common.usecase.Zc101RecordingProposalDataSource
import co.electriccoin.zcash.ui.common.usecase.syntheticZashiAccount
import co.electriccoin.zcash.ui.common.usecase.syntheticZip321Proposal
import co.electriccoin.zcash.ui.common.wallet.ExchangeRateState
import co.electriccoin.zcash.ui.screen.chat.crypto.E2EEncryption
import co.electriccoin.zcash.ui.screen.chat.crypto.E2EKeyPair
import co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferences
import co.electriccoin.zcash.ui.screen.chat.model.Contact
import co.electriccoin.zcash.ui.screen.chat.model.ContactBook
import co.electriccoin.zcash.ui.screen.chat.model.GroupInfo
import co.electriccoin.zcash.ui.screen.chat.model.GroupMember
import co.electriccoin.zcash.ui.screen.chat.model.InviteStatus
import co.electriccoin.zcash.ui.screen.chat.model.MemberStatus
import co.electriccoin.zcash.ui.screen.chat.model.ZMSGGroupProtocol
import co.electriccoin.zcash.ui.screen.chat.usecase.CreateChunkedMessageProposalUseCase
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.lang.reflect.Proxy
import java.security.SecureRandom
import java.time.Instant
import java.util.Base64
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn

/**
 * KI-22 — the admin's side of one group at key epoch 1, shared by several GroupViewModels (each nav destination has
 * its own: the group screen, the group settings screen), over ONE store, exactly as the app's singleton preferences.
 *
 * - Every member in [sessions] has a KEX session with us; nobody has a Nostr key, so every memo goes on-chain.
 * - The FIRST on-chain submit is held until [release] completes (the "waiting for a block" part of a real send), so a
 *   test can start a second change of the group from another screen while the first one is in flight. Every later
 *   submit succeeds at once.
 * - [memosTo] records, per address, every memo the group sent there (from the ZIP-321 request the send builds), in
 *   order, as the proposal is asked for.
 * - [keyWrites] records every group key the view models stored, in order: two different keys at the same epoch is the
 *   fork.
 * - [storeUnderGroupLock] changes the roster the way ChatViewModel's inbound handlers do (GROUP_LEAVE / GROUP_ACCEPT):
 *   a fresh read-modify-write under the process-wide group lock.
 *
 * The group id is per test, so the process-wide lock of one test is never the lock of another. All addresses, keys and
 * group ids are synthetic.
 */
internal class GroupChangeRaceFixture(
    val groupId: String,
    rows: List<GroupMember>,
    sessions: Set<String>,
) {
    private val adminKeys: E2EKeyPair = E2EEncryption.generateKeyPair()
    private val peerKeys: Map<String, E2EKeyPair> = sessions.associateWith { E2EEncryption.generateKeyPair() }
    private val infoJson =
        ZMSGGroupProtocol.serializeGroupInfo(
            GroupInfo(groupId = groupId, name = "Two screens", creatorAddress = SELF, createdAt = Instant.ofEpochMilli(1_000L)),
        )

    @Volatile var membersJson: String = ZMSGGroupProtocol.serializeGroupMembers(rows)

    @Volatile var epoch: Int = 1
    val keys = ConcurrentHashMap<Int, String>()

    /** Every saveGroupKey, in order: (epoch, key). */
    val keyWrites: MutableList<Pair<Int, String>> = Collections.synchronizedList(mutableListOf())

    /** Every memo sent on-chain, in order: (recipient, the ZIP-321 request). */
    val memos: MutableList<Pair<String, String>> = Collections.synchronizedList(mutableListOf())

    /** Every method name this fixture answered with an empty default, for diagnosing a failure. */
    val defaultedCalls: MutableList<String> = Collections.synchronizedList(mutableListOf())

    val firstSubmitEntered = CompletableDeferred<Unit>()
    val release = CompletableDeferred<Unit>()
    val submits = AtomicInteger()

    private val addresses: List<String> = rows.map { it.address } + sessions

    init {
        keys[1] = Base64.getEncoder().encodeToString(ByteArray(32).also { SecureRandom().nextBytes(it) })
    }

    fun roster(): List<GroupMember> = ZMSGGroupProtocol.deserializeGroupMembers(membersJson)

    fun row(address: String): GroupMember = roster().single { it.address == address }

    /** How many memos went to [address] after the first [from] memos of the whole run. */
    fun memosTo(
        address: String,
        from: Int = 0,
    ): Int = synchronized(memos) { memos.drop(from).count { it.first == address } }

    /** ChatViewModel's inbound roster write: a fresh read-modify-write under the process-wide group lock. */
    fun storeUnderGroupLock(change: (List<GroupMember>) -> List<GroupMember>) {
        synchronized(ZchatPreferences.groupLock(groupId)) {
            membersJson = ZMSGGroupProtocol.serializeGroupMembers(change(roster()))
        }
    }

    /** The on-chain seam: records each memo's recipient; the first submit waits for [release]. */
    private inner class HeldSendSeam(
        private val recording: Zc101RecordingProposalDataSource = Zc101RecordingProposalDataSource(),
    ) : ProposalDataSource by recording {
        override suspend fun createZip321Proposal(
            account: WalletAccount,
            zip321Uri: String,
            allowMultiPayment: Boolean
        ): Zip321TransactionProposal {
            val to = addresses.firstOrNull { zip321Uri.contains(it) } ?: "usyntheticgroupchangeunknown"
            memos += to to zip321Uri
            return syntheticZip321Proposal(to, amount = 1_000L, memo = "synthetic-group-memo")
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
                release.await()
            }
            return SubmitResult.Success(txIds = listOf("synthetic-group-change-txid-$n"))
        }
    }

    private val seam = HeldSendSeam()

    /** A new screen's GroupViewModel (its own instance, as viewModelOf gives every nav destination), on this store. */
    suspend fun groupViewModel(): GroupViewModel {
        val owner = SendHarnessScopes.current()
        val accountDataSource = Zc101FakeAccountDataSource(syntheticZashiAccount())
        val synchronizerProvider = NoSynchronizer()
        val getSelectedWalletAccount = GetSelectedWalletAccountUseCase(accountDataSource)
        val vm =
            GroupViewModel(
                transactionRepository = NoTransactions(),
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
                exchangeRateRepository = OptedOut(),
                contactBook = NoContacts(),
                persistableWalletProvider = NoSeed(),
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

    /** Start [vm]'s kick of [kicked] (null = a key rotation) on its own scope, as the kick / rotate prompt's OK does. */
    fun startRotation(
        vm: GroupViewModel,
        kicked: String?,
    ): Job =
        vm.viewModelScope.launch {
            suspendCoroutineUninterceptedOrReturn<Any?> { cont ->
                ROTATE.invoke(vm, groupId, kicked, { _: String -> true }, cont)
            }
        }

    /** Wait until [done] or [timeoutMs]; false when it timed out. */
    suspend fun waitUntil(
        timeoutMs: Long = HandshakeHarness.TIMEOUT_MS,
        done: () -> Boolean,
    ): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!done()) {
            if (System.currentTimeMillis() > deadline) return false
            delay(10)
        }
        return true
    }

    suspend fun awaitFirstSubmit() = withTimeout(HandshakeHarness.TIMEOUT_MS) { firstSubmitEntered.await() }

    @Suppress("CyclomaticComplexMethod")
    val proxy: ZchatPreferences =
        Proxy.newProxyInstance(
            ZchatPreferences::class.java.classLoader,
            arrayOf(ZchatPreferences::class.java),
        ) { p, method, args ->
            fun arg(i: Int) = args!![i]
            when (method.name) {
                "getAllGroupIds" -> setOf(groupId)
                "getGroupInfo" -> if (arg(0) == groupId) infoJson else null
                "getGroupMembers" -> if (arg(0) == groupId) membersJson else null
                "saveGroupMembers" -> {
                    membersJson = arg(1) as String
                    null
                }
                "saveGroupMembersDurably" -> {
                    membersJson = arg(1) as String
                    true
                }
                "isGroupSelfCreated" -> arg(0) == groupId
                "isSelfAddress" -> arg(0) == SELF
                "getGroupKeyEpoch" -> epoch
                "setGroupKeyEpoch" -> {
                    epoch = arg(1) as Int
                    null
                }
                "getGroupKey" -> keys[arg(1) as Int]
                "saveGroupKey" -> {
                    keys[arg(1) as Int] = arg(2) as String
                    keyWrites += (arg(1) as Int) to (arg(2) as String)
                    null
                }
                "resolvePeerAddress" -> arg(0)
                "getE2EPrivateKey" -> if (arg(0) in peerKeys) adminKeys.privateKey else null
                "getE2EOurPublicKey" -> if (arg(0) in peerKeys) adminKeys.publicKey else null
                "getE2EPeerPublicKey" -> peerKeys[arg(0) as String]?.publicKey
                "getE2EKeyVersion" -> 2
                "getPeerNostrPubkey" -> null
                "hasConsentedToHandshakeSpend" -> true
                "toString" -> "GroupChangeRaceFixture"
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

    private class NoSynchronizer : SynchronizerProvider {
        override val error: StateFlow<SynchronizerError?> = MutableStateFlow(null)
        override val synchronizer: StateFlow<Synchronizer?> = MutableStateFlow(null)

        override suspend fun getSynchronizer(): Synchronizer = awaitCancellation()

        override fun resetSynchronizer() = Unit
    }

    private class OptedOut : ExchangeRateRepository {
        override val state: StateFlow<ExchangeRateState> = MutableStateFlow(ExchangeRateState.OptedOut)

        override fun refreshExchangeRateUsd() = Unit
    }

    private class NoTransactions : TransactionRepository {
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

    private class NoSeed : PersistableWalletProvider {
        override val persistableWallet: Flow<PersistableWallet?> = flowOf(null)

        override suspend fun store(persistableWallet: PersistableWallet) = Unit

        override suspend fun getPersistableWallet(): PersistableWallet? = null

        override suspend fun requirePersistableWallet(): PersistableWallet = error("seed path not used in this test")
    }

    companion object {
        const val SELF = "usyntheticgroupchangeadmin01"
        const val M1 = "usyntheticgroupchangememb001"
        const val M2 = "usyntheticgroupchangememb002"
        const val X = "usyntheticgroupchangeleaver1"
        const val Y = "usyntheticgroupchangeaccept1"
        const val N = "usyntheticgroupchangenewbie1"

        fun member(
            address: String,
            status: MemberStatus = MemberStatus.ACTIVE,
            invite: InviteStatus? = InviteStatus.SENT,
            admin: Boolean = false,
        ) = GroupMember(address = address, joinedAt = Instant.ofEpochMilli(1_000L), status = status, isAdmin = admin, inviteStatus = invite)

        fun admin() = member(SELF, invite = null, admin = true)

        /** GroupViewModel's private suspend rotateAndNotify(groupId, kicked, mayPayOnChain) (the RotateAndNotifyRaceTest idiom). */
        private val ROTATE =
            GroupViewModel::class.java
                .getDeclaredMethod(
                    "rotateAndNotify",
                    String::class.java,
                    String::class.java,
                    Function1::class.java,
                    Continuation::class.java,
                ).apply { isAccessible = true }

        /**
         * KI-22 — the groups whose change on [vm]'s screen waits for another change of the same group (the "Waiting for
         * the other change to this group to finish" line). Reached by name, so this file compiles before it exists.
         */
        @Suppress("UNCHECKED_CAST")
        fun waitingGroups(vm: GroupViewModel): Set<String> =
            (GroupViewModel::class.java.getMethod("getGroupsWaitingForChange").invoke(vm) as StateFlow<Set<String>>).value
    }
}
