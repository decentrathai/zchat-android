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
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.lang.reflect.Proxy
import java.security.SecureRandom
import java.time.Instant
import java.util.Base64
import java.util.Collections

/**
 * NEW (A2) — GroupViewModel's automatic key retry pays on-chain without handshake-spend consent.
 *
 * loadGroupDetail runs every time the group screen opens. When any member is still owed a rotated key
 * (pendingKeyEpoch set), it calls retryPendingGroupKeysInternal, which re-sends the key through
 * sendGroupMemoWithRetry: NOSTR if the member has a key there, otherwise a direct on-chain submit
 * (~17,000 zat). At 8134b88d3 nothing asks: the retry is an automatic spend no gesture requested,
 * exactly the class the handshake-spend consent covers, but it never reaches that decision.
 *
 * The user-initiated routes to the same retry (the admin's "sync group keys" tap, the kick and rotate
 * follow-ups) are the user's own consent and stay ungated; a control pins the tap.
 *
 * A REAL GroupViewModel on the JVM (the RotateAndNotifyRaceTest idiom) over the real
 * CreateChunkedMessageProposalUseCase and ZashiProposalRepositoryImpl, with the HandshakeSeam settlement
 * fake counting proposals and broadcasts. The member has a KEX session with us and no NOSTR pubkey.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GroupKeyRetryRequiresConsentTest {
    private val sendScopes = SendHarnessScopes()

    private val vms = mutableListOf<GroupViewModel>()

    @Before
    fun setUp() {
        sendScopes.open()
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        sendScopes.close()
        vms.forEach { it.viewModelScope.cancel() }
        vms.clear()
        Dispatchers.resetMain()
    }

    @Test
    fun `opening the group while a member is owed a key pays nothing without handshake consent`() =
        runBlocking {
            val fixture = GroupRetryFixture(member = "usyntheticgroupkeyretry0001", consent = false)
            val seam = HandshakeSeam(fixture.member)
            val vm = fixture.groupViewModel(seam).also { vms += it }

            fixture.openGroup(vm)

            assertEquals("fixture sanity", emptyList<String>(), fixture.unexpectedCalls)
            assertEquals(
                "opening the group screen paid an on-chain key re-send without handshake-spend consent. The " +
                    "automatic retry is a spend no gesture asked for; it must respect the consent gate.",
                0,
                seam.proposalRequests.get(),
            )
            assertEquals(
                "the member must stay owed the key, so the retry resumes once consent is given",
                1,
                fixture.memberRow().pendingKeyEpoch,
            )
        }

    /** Control, GREEN before and after the fix: with consent granted, opening the group re-sends the key once. */
    @Test
    fun `control - with consent granted opening the group re-sends the owed key once`() =
        runBlocking {
            val fixture = GroupRetryFixture(member = "usyntheticgroupkeyretry0002", consent = true)
            val seam = HandshakeSeam(fixture.member)
            val vm = fixture.groupViewModel(seam).also { vms += it }

            fixture.openGroup(vm)

            assertEquals("fixture sanity", emptyList<String>(), fixture.unexpectedCalls)
            assertEquals(1, seam.proposalRequests.get())
            assertEquals(1, seam.broadcasts.size)
            assertEquals(null, fixture.memberRow().pendingKeyEpoch)
        }

    /**
     * Control, GREEN before and after the fix: the admin's own "sync group keys" tap is the consent, so it
     * re-sends the owed key even when automatic-handshake consent was never given.
     *
     * R2-2 (b): the tap first shows what it costs (one on-chain message) and pays once the user confirms;
     * handshake consent is still not needed.
     */
    @Test
    fun `control - the admin's sync-keys tap re-sends the owed key without handshake consent`() =
        runBlocking {
            val fixture = GroupRetryFixture(member = "usyntheticgroupkeyretry0003", consent = false)
            val seam = HandshakeSeam(fixture.member)
            val vm = fixture.groupViewModel(seam).also { vms += it }

            fixture.awaitNewJobs(vm) { vm.requestGroupKeySync(GroupRetryFixture.GROUP_ID) }
            assertEquals("the tap pays nothing before its cost is confirmed", 0, seam.proposalRequests.get())
            fixture.awaitNewJobs(vm) { GroupViewModel::class.java.getMethod("confirmPaidAction").invoke(vm) }

            assertEquals("fixture sanity", emptyList<String>(), fixture.unexpectedCalls)
            assertEquals(1, seam.proposalRequests.get())
            assertEquals(1, seam.broadcasts.size)
        }
}

/**
 * The admin's side of one group at epoch 1: we created [GROUP_ID], [member] is ACTIVE, holds a KEX session
 * with us, has no NOSTR pubkey, and is still owed the epoch-1 key (pendingKeyEpoch = 1). The roster and the
 * consent flag are mutable, shared by every GroupViewModel built from this fixture (the app's singleton
 * preferences).
 */
internal class GroupRetryFixture(
    val member: String,
    consent: Boolean,
) {
    @Volatile var consent: Boolean = consent

    private val adminKeys: E2EKeyPair = E2EEncryption.generateKeyPair()
    private val memberKeys: E2EKeyPair = E2EEncryption.generateKeyPair()
    private val groupKey: String = Base64.getEncoder().encodeToString(ByteArray(32).also { SecureRandom().nextBytes(it) })
    private val infoJson =
        ZMSGGroupProtocol.serializeGroupInfo(
            GroupInfo(
                groupId = GROUP_ID,
                name = "Retry Test",
                creatorAddress = SELF,
                createdAt = Instant.ofEpochMilli(1_000L),
            )
        )

    @Volatile var membersJson: String =
        ZMSGGroupProtocol.serializeGroupMembers(
            listOf(
                GroupMember(address = SELF, joinedAt = Instant.ofEpochMilli(1_000L), status = MemberStatus.ACTIVE, isAdmin = true),
                GroupMember(
                    address = member,
                    joinedAt = Instant.ofEpochMilli(1_000L),
                    status = MemberStatus.ACTIVE,
                    pendingKeyEpoch = 1,
                ),
            )
        )

    val unexpectedCalls: MutableList<String> = Collections.synchronizedList(mutableListOf())

    fun memberRow(): GroupMember = ZMSGGroupProtocol.deserializeGroupMembers(membersJson).single { it.address == member }

    /** Open the group screen (AndroidGroupDetail's loadGroupDetail) and wait for everything it started. */
    suspend fun openGroup(vm: GroupViewModel) = awaitNewJobs(vm) { vm.loadGroupDetail(GROUP_ID) }

    /**
     * Run [block] and join the jobs it launched, including those they launch in turn (the sync tap ends
     * by reloading the group). Jobs already running before [block], such as loadCurrentUserAddress, which
     * never finishes here, are not waited for.
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
                started.forEach { it.join() }
            }
        }
    }

    suspend fun groupViewModel(seam: HandshakeSeam): GroupViewModel {
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
                contactBook = EmptyContactBook(),
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

    @Suppress("CyclomaticComplexMethod")
    val proxy: ZchatPreferences =
        Proxy.newProxyInstance(
            ZchatPreferences::class.java.classLoader,
            arrayOf(ZchatPreferences::class.java),
        ) { p, method, args ->
            fun arg(i: Int) = args!![i]

            fun forMember(): Boolean = arg(0) == member
            when (method.name) {
                "getAllGroupIds" -> setOf(GROUP_ID)
                "getGroupInfo" -> if (arg(0) == GROUP_ID) infoJson else null
                "getGroupMembers" -> if (arg(0) == GROUP_ID) membersJson else null
                "saveGroupMembers" -> {
                    membersJson = arg(1) as String
                    null
                }
                // B1: the checked roster write the owed-key stamp uses before a paid re-send.
                "saveGroupMembersDurably" -> {
                    membersJson = arg(1) as String
                    true
                }
                "getGroupDraft" -> null
                "getGroupMessages" -> null
                "isGroupSelfCreated" -> arg(0) == GROUP_ID
                "isSelfAddress" -> arg(0) == SELF
                "getGroupKeyEpoch" -> 1
                "getGroupKey" -> if (arg(0) == GROUP_ID && arg(1) == 1) groupKey else null
                "resolvePeerAddress" -> arg(0)
                "getE2EPrivateKey" -> if (forMember()) adminKeys.privateKey else null
                "getE2EPeerPublicKey" -> if (forMember()) memberKeys.publicKey else null
                "getE2EKeyVersion" -> 2
                "getPeerNostrPubkey" -> null
                "getAllConversationPeerAddresses" -> emptySet<String>()
                "hasConsentedToHandshakeSpend" -> this.consent
                // C5: the consent question drops another wallet's queued setups after a phrase change; one wallet here.
                "walletScopeGeneration" -> 0L
                // GROUP-LABEL: the group screen reads the 1-to-1 nicknames once to name members and senders; none here.
                "getAllNicknames" -> emptyMap<String, String>()
                "toString" -> "GroupRetryFixturePrefs"
                "hashCode" -> System.identityHashCode(p)
                "equals" -> p === args?.get(0)
                else -> {
                    unexpectedCalls.add(method.name)
                    null
                }
            }
        } as ZchatPreferences

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

    private class EmptyContactBook : ContactBook {
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
        const val GROUP_ID = "zgrp_keyretrytest"
        const val SELF = "usyntheticgroupretryadmin00"
    }
}
