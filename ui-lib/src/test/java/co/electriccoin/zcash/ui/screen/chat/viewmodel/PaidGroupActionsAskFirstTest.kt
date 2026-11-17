package co.electriccoin.zcash.ui.screen.chat.viewmodel

import androidx.lifecycle.viewModelScope
import cash.z.ecc.android.sdk.Synchronizer
import cash.z.ecc.android.sdk.model.PersistableWallet
import cash.z.ecc.android.sdk.model.TransactionId
import co.electriccoin.zcash.ui.common.model.SynchronizerError
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
import co.electriccoin.zcash.ui.screen.chat.model.Contact
import co.electriccoin.zcash.ui.screen.chat.model.ContactBook
import co.electriccoin.zcash.ui.screen.chat.model.GroupInfo
import co.electriccoin.zcash.ui.screen.chat.model.GroupMember
import co.electriccoin.zcash.ui.screen.chat.model.InviteStatus
import co.electriccoin.zcash.ui.screen.chat.model.MemberStatus
import co.electriccoin.zcash.ui.screen.chat.model.ZMSGGroupProtocol
import co.electriccoin.zcash.ui.screen.chat.usecase.CreateChunkedMessageProposalUseCase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.lang.reflect.Proxy
import java.security.SecureRandom
import java.time.Instant
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * R2-2 (b), plan step p2-2546b8c8a490 — group actions that pay on-chain without saying so.
 *
 * Every group control goes to each member as its own on-chain transaction when the member is not reachable
 * over Nostr (about 17,000 zatoshi each). At b1f45ab32 creating a group, adding a member, re-sending an
 * invite, removing a member, rotating the key, syncing keys and leaving all pay at once; the screens show
 * a toast ("Rotating group key…") and no cost.
 *
 * Wanted: each asks first with the number of on-chain messages and the total, pays nothing until the user
 * confirms, and confirming pays what was quoted. The confirmation API is reached by name.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PaidGroupActionsAskFirstTest {
    private val sendScopes = SendHarnessScopes()
    private val vms = mutableListOf<GroupViewModel>()

    // leaveGroup pauses 500 ms between members; virtual time is advanced while a test waits.
    private val scheduler = TestCoroutineScheduler()

    @Before
    fun setUp() {
        sendScopes.open()
        Dispatchers.setMain(UnconfinedTestDispatcher(scheduler))
    }

    @After
    fun tearDown() {
        sendScopes.close()
        vms.forEach { it.viewModelScope.cancel() }
        // A cancelled screen still resumes on Main while it ends; resetting Main first leaks into another test.
        kotlinx.coroutines.runBlocking {
            vms.toList().forEach { screen ->
                kotlinx.coroutines.withTimeoutOrNull(5_000L) {
                    screen.viewModelScope.coroutineContext[kotlinx.coroutines.Job]?.join()
                }
            }
        }
        vms.clear()
        Dispatchers.resetMain()
    }

    private suspend fun check(
        f: PaidGroupFixture,
        messages: Int,
        action: (GroupViewModel) -> Unit,
    ) {
        val vm = f.groupViewModel().also { vms += it }
        f.scheduler = scheduler
        f.awaitNewJobs(vm) { action(vm) }
        assertEquals("the group action paid on-chain with no confirmation on screen", 0, f.seam.proposalRequests.get())
        val confirmation = GroupViewModel::class.java.getMethod("getPaidActionConfirmation").invoke(vm)
        val value = (confirmation as StateFlow<*>).value
        assertTrue("the user must be asked with the on-chain message count and the total", value != null)
        val count = value!!.javaClass.getMethod("getOnChainMessages").invoke(value) as Int
        val total = value.javaClass.getMethod("getTotalCost").invoke(value)
        val totalZat = total.javaClass.getMethod("getValue").invoke(total) as Long
        assertEquals("on-chain messages quoted", messages, count)
        assertTrue("total quoted ($totalZat) below $messages x 17,000 zatoshi", totalZat >= messages * 17_000L)

        f.awaitNewJobs(vm) { GroupViewModel::class.java.getMethod("confirmPaidAction").invoke(vm) }
        assertEquals("confirming pays exactly what was quoted", messages, f.seam.proposalRequests.get())
    }

    @Test
    fun `rotating the group key asks first`() =
        runBlocking { check(PaidGroupFixture(), messages = 2) { it.rotateGroupKey(PaidGroupFixture.GROUP_ID) } }

    @Test
    fun `removing a member asks first`() =
        runBlocking {
            // Round 8 P6 (PHONE_TRIAGE F5b): the new key to A, and the removal notice (no key) to B, who has a session.
            check(PaidGroupFixture(), messages = 2) { it.kickMember(PaidGroupFixture.GROUP_ID, PaidGroupFixture.MEMBER_B) }
        }

    @Test
    fun `leaving the group asks first`() =
        runBlocking { check(PaidGroupFixture(), messages = 2) { it.leaveGroup(PaidGroupFixture.GROUP_ID) } }

    @Test
    fun `re-sending an invite asks first`() =
        runBlocking {
            check(PaidGroupFixture(), messages = 1) { it.resendInvite(PaidGroupFixture.GROUP_ID, PaidGroupFixture.MEMBER_A) }
        }

    @Test
    fun `adding a member asks first`() =
        runBlocking {
            check(PaidGroupFixture(), messages = 1) { it.addMemberToGroup(PaidGroupFixture.GROUP_ID, PaidGroupFixture.MEMBER_C) }
        }

    @Test
    fun `syncing keys as the admin asks first`() =
        runBlocking {
            check(PaidGroupFixture(owedA = true), messages = 1) { it.requestGroupKeySync(PaidGroupFixture.GROUP_ID) }
        }

    @Test
    fun `creating a group asks first`() =
        runBlocking {
            check(PaidGroupFixture(), messages = 2) { vm ->
                vm.setGroupName("Synthetic new group")
                vm.toggleMemberSelection(PaidGroupFixture.MEMBER_A)
                vm.toggleMemberSelection(PaidGroupFixture.MEMBER_B)
                vm.createGroup()
            }
        }
}

/**
 * We administer [GROUP_ID] at epoch 1 with [MEMBER_A] and [MEMBER_B] ACTIVE; both hold a KEX session with
 * us and neither has a Nostr key, so every control goes on-chain. [MEMBER_C] is a contact outside the
 * group. Lenient: any other call gets the type's empty value.
 */
internal class PaidGroupFixture(
    owedA: Boolean = false,
    /** GPT-6 F2: members with a Nostr key, quoted as free while a publisher is registered. */
    private val nostrPeers: Set<String> = emptySet(),
    /** R4-2: [MEMBER_B] is also owed the current key, so an admin key-sync fans out to two members. */
    owedB: Boolean = false,
    /** ZC-972: the "Automatic setup payments" setting (KI-23); On unless a test switches it Off. */
    private val handshakeSpendConsent: Boolean = true,
) {
    val seam = HandshakeSeam(MEMBER_A)

    /** Advanced while [awaitNewJobs] waits, so a delay() inside the action completes. */
    var scheduler: TestCoroutineScheduler? = null

    private val adminKeys: E2EKeyPair = E2EEncryption.generateKeyPair()

    // R4-2: MEMBER_C is keyed too, so it can be rostered as a third key-holding member.
    private val peerKeys =
        mapOf(
            MEMBER_A to E2EEncryption.generateKeyPair(),
            MEMBER_B to E2EEncryption.generateKeyPair(),
            MEMBER_C to E2EEncryption.generateKeyPair(),
        )
    private val groupKeys = ConcurrentHashMap<String, String>()
    private val groupInfos = ConcurrentHashMap<String, String>()
    private val groupMembers = ConcurrentHashMap<String, String>()
    private val epochs = ConcurrentHashMap<String, Int>()
    private val selfCreated: MutableSet<String> = ConcurrentHashMap.newKeySet()

    // R5-1: the per-group consent to pay for group MESSAGES, remembered across ViewModel instances
    // exactly as the preferences file would remember it.
    private val messageSpendConsents = ConcurrentHashMap<String, Set<String>>()

    // R5-1: the persisted composer draft, so a test can read back what survives a cost prompt.
    private val drafts = ConcurrentHashMap<String, String>()

    /**
     * R6-1: how many group-message sequence numbers were taken. A send takes one only once it has
     * really started its fan-out, so a send that goes nowhere must leave this at zero.
     */
    val sequenceBumps = AtomicInteger()

    init {
        groupKeys["$GROUP_ID|1"] = Base64.getEncoder().encodeToString(ByteArray(32).also { SecureRandom().nextBytes(it) })
        epochs[GROUP_ID] = 1
        selfCreated += GROUP_ID
        groupInfos[GROUP_ID] =
            ZMSGGroupProtocol.serializeGroupInfo(
                GroupInfo(groupId = GROUP_ID, name = "Paid actions", creatorAddress = SELF, createdAt = Instant.ofEpochMilli(1_000L)),
            )
        groupMembers[GROUP_ID] =
            ZMSGGroupProtocol.serializeGroupMembers(
                listOf(
                    GroupMember(address = SELF, joinedAt = Instant.ofEpochMilli(1_000L), status = MemberStatus.ACTIVE, isAdmin = true),
                    GroupMember(
                        address = MEMBER_A,
                        joinedAt = Instant.ofEpochMilli(1_000L),
                        status = MemberStatus.ACTIVE,
                        inviteStatus = InviteStatus.SENT,
                        pendingKeyEpoch = if (owedA) 1 else null,
                    ),
                    GroupMember(
                        address = MEMBER_B,
                        joinedAt = Instant.ofEpochMilli(1_000L),
                        status = MemberStatus.ACTIVE,
                        inviteStatus = InviteStatus.SENT,
                        pendingKeyEpoch = if (owedB) 1 else null,
                    ),
                ),
            )
    }

    /** R5-1 — the draft persisted for [groupId], or null when nothing was saved. */
    fun draft(groupId: String): String? = drafts[groupId]

    /** R4-2 — the stored roster, so a test can read the per-member invite badge back. */
    fun rosterJson(): String = groupMembers[GROUP_ID]!!

    /** R4-2 — roster another ACTIVE key holder (before the quote, or after it to change the plan). */
    fun addActiveMember(address: String) {
        val members = ZMSGGroupProtocol.deserializeGroupMembers(groupMembers[GROUP_ID]!!)
        groupMembers[GROUP_ID] =
            ZMSGGroupProtocol.serializeGroupMembers(
                members +
                    GroupMember(
                        address = address,
                        joinedAt = Instant.ofEpochMilli(1_000L),
                        status = MemberStatus.ACTIVE,
                        inviteStatus = InviteStatus.SENT,
                    ),
            )
    }

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
        val accountDataSource = Zc101FakeAccountDataSource(syntheticZashiAccount())
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
                contactBook = Contacts(),
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

    @Suppress("CyclomaticComplexMethod", "LongMethod")
    val proxy: ZchatPreferences =
        Proxy.newProxyInstance(
            ZchatPreferences::class.java.classLoader,
            arrayOf(ZchatPreferences::class.java),
        ) { p, method, args ->
            fun arg(i: Int) = args!![i]
            when (method.name) {
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
                // B1: the checked roster write the owed-key stamp uses before a paid re-send.
                "saveGroupMembersDurably" -> {
                    groupMembers[arg(0) as String] = arg(1) as String
                    true
                }
                "isGroupSelfCreated" -> arg(0) in selfCreated
                "setGroupSelfCreated" -> {
                    if (arg(1) as Boolean) selfCreated += arg(0) as String
                    null
                }
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
                "resolvePeerAddress" -> arg(0)
                "getE2EPrivateKey" -> if (arg(0) in peerKeys) adminKeys.privateKey else null
                "getE2EOurPublicKey" -> if (arg(0) in peerKeys) adminKeys.publicKey else null
                "getE2EPeerPublicKey" -> peerKeys[arg(0) as String]?.publicKey
                "getE2EKeyVersion" -> 2
                "getPeerNostrPubkey" -> if (arg(0) in nostrPeers) SYNTHETIC_NOSTR_PUBKEY else null
                "hasConsentedToHandshakeSpend" -> handshakeSpendConsent
                // R6-1 — counted; the value stays the 0L every other test already runs on.
                "incrementGroupMessageSequence" -> {
                    sequenceBumps.incrementAndGet()
                    0L
                }
                // R5-1 — the persisted composer draft.
                "getGroupDraft" -> drafts[arg(0) as String]
                "setGroupDraft" -> {
                    drafts[arg(0) as String] = arg(1) as String
                    null
                }
                "clearGroupDraft" -> {
                    drafts.remove(arg(0) as String)
                    null
                }
                // R5-1 — the per-group group-message spend consent.
                "getGroupMessageSpendConsent" -> messageSpendConsents[arg(0) as String].orEmpty()
                "setGroupMessageSpendConsent" -> {
                    @Suppress("UNCHECKED_CAST")
                    messageSpendConsents[arg(0) as String] = (arg(1) as Collection<String>).toSet()
                    true
                }
                "toString" -> "PaidGroupFixture"
                "hashCode" -> System.identityHashCode(p)
                "equals" -> p === args?.get(0)
                else -> emptyValueFor(method.returnType)
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

    private class Contacts : ContactBook {
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
        const val GROUP_ID = "zgrp_paidactionstest"
        const val SELF = "usyntheticpaidgroupadmin0001"
        const val MEMBER_A = "usyntheticpaidgroupmembera01"
        const val MEMBER_B = "usyntheticpaidgroupmemberb01"
        const val MEMBER_C = "usyntheticpaidgroupmemberc01"
        const val SYNTHETIC_NOSTR_PUBKEY = "5e7e7e7e5e7e7e7e5e7e7e7e5e7e7e7e5e7e7e7e5e7e7e7e5e7e7e7e5e7e7e7e"
    }
}
