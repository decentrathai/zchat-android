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
import co.electriccoin.zcash.ui.common.usecase.Zc101RecordingProposalDataSource
import co.electriccoin.zcash.ui.common.usecase.Zc101SuspendingBiometricRepository
import co.electriccoin.zcash.ui.common.usecase.syntheticZashiAccount
import co.electriccoin.zcash.ui.common.wallet.ExchangeRateState
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
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import java.lang.reflect.Proxy
import java.time.Instant
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn
import kotlin.test.Test

/**
 * Review findings F1 (CRITICAL, kick/rotate roster-read TOCTOU) + F3 (HIGH, an INVITED member that
 * already holds the current group key is never re-keyed on a kick) — RED regression pins for
 * [GroupViewModel.rotateAndNotify].
 *
 * F1: the member roster is read BEFORE rotationLock.withLock; the lock re-reads only the epoch. Two
 * concurrent kicks both capture the SAME stale roster, so the 2nd kick's recipients re-send the new
 * group key to the already-kicked member and its roster rewrite overwrites the 1st kick's roster —
 * RESURRECTING the kicked member as ACTIVE. Fix: read the roster INSIDE the lock (as the sibling
 * retryPendingGroupKeysInternal already does).
 *
 * F3: recipients restrict to status == ACTIVE, so an INVITED member whose invite was actually
 * delivered (InviteStatus.SENT carries the CURRENT epoch key) is never sent the GROUP_KICK / new
 * key and keeps decrypting post-kick traffic. Fix: the fan-out must include key-HOLDERS (ACTIVE, or
 * INVITED with a delivered SENT invite), never invitees whose invite is still pending/failed.
 *
 * Fixture idiom mirrors GroupReconcileIdentityTest: a REAL GroupViewModel on the JVM, main
 * dispatcher = UnconfinedTestDispatcher, and a dynamic Proxy fake of ZchatPreferences. The private
 * suspend `rotateAndNotify` is driven directly via reflection so the assertions observe exactly the
 * fan-out + roster commit, with NO on-chain send path reached: every member's E2E key is faked null,
 * so each recipient short-circuits into the "failed" branch BEFORE any memo is transmitted. The set
 * of addresses `getE2EPrivateKey` is called on is therefore exactly the rotation recipient set.
 * F1 forces the interleaving deterministically by pre-acquiring the (fair, FIFO) rotationLock so
 * both kicks read the roster and then queue on the lock in launch order.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RotateAndNotifyRaceTest {

    private companion object {
        const val GROUP_ID = "zgrp_rotatetest"
        const val SELF = "usynthetic-admin-self"
        const val X = "usynthetic-member-x"
        const val Y = "usynthetic-member-y"
        const val B = "usynthetic-member-b"
        const val C_INVITED_SENT = "usynthetic-member-c-invited-sent"
        const val D_INVITED_PENDING = "usynthetic-member-d-invited-pending"
    }

    private val vms = mutableListOf<GroupViewModel>()
    private val mainDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
    }

    @After
    fun tearDown() {
        vms.forEach { it.viewModelScope.cancel() }
        vms.clear()
        Dispatchers.resetMain()
    }

    // -------------------------------------------------------------- prefs fake

    /** Mutable backing store for the handful of prefs keys rotateAndNotify + loadGroups touch. */
    private class PrefsState(initialMembers: List<GroupMember>) {
        @Volatile var membersJson: String = ZMSGGroupProtocol.serializeGroupMembers(initialMembers)
        @Volatile var epoch: Int = 0
        val keys = ConcurrentHashMap<Int, String>()
        @Volatile var removalFloor: Int = 0

        /** Every address the rotation fan-out attempted to key = the recipient set (send-side proof). */
        val e2ePrivateKeyCalls: MutableList<String> = Collections.synchronizedList(mutableListOf())
    }

    private fun fakePrefs(state: PrefsState, infoJson: String): ZchatPreferences =
        Proxy.newProxyInstance(
            ZchatPreferences::class.java.classLoader,
            arrayOf(ZchatPreferences::class.java),
        ) { proxy, method, args ->
            when (method.name) {
                // group-load (init) + rotate reads
                "getAllGroupIds" -> setOf(GROUP_ID)
                "getGroupInfo" -> infoJson
                "getGroupMembers" -> state.membersJson
                "saveGroupMembers" -> { state.membersJson = args!![1] as String; null }
                // B1: the checked roster write the owed-key stamp uses before a paid re-send.
                "saveGroupMembersDurably" -> {
                    state.membersJson = args!![1] as String
                    true
                }
                "getGroupDraft" -> null
                "getGroupMessages" -> null
                // epoch / key / floor
                "getGroupKeyEpoch" -> state.epoch
                "setGroupKeyEpoch" -> { state.epoch = args!![1] as Int; null }
                "saveGroupKey" -> { state.keys[args!![1] as Int] = args[2] as String; null }
                "getGroupKey" -> state.keys[args!![1] as Int]
                "getGroupRemovalFloor" -> state.removalFloor
                "setGroupRemovalFloor" -> { state.removalFloor = args!![1] as Int; null }
                // admin / identity
                "isGroupSelfCreated" -> true
                "isSelfAddress" -> (args!![0] as String) == SELF
                "resolvePeerAddress" -> args!![0] as String
                "registerSelfAddress" -> null
                // E2E — always "no session" so the send loop marks every recipient failed BEFORE any
                // memo goes out; recording the call is the recipient-set oracle.
                "getE2EPrivateKey" -> { state.e2ePrivateKeyCalls.add(args!![0] as String); null }
                "getE2EPeerPublicKey" -> null
                "getE2EKeyVersion" -> 0
                // NOSTR fallback (only reached if a send were attempted — it never is here)
                "getPeerNostrPubkey" -> null
                "getAllConversationPeerAddresses" -> emptySet<String>()
                // Object plumbing
                "toString" -> "FakeZchatPreferences"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args!![0]
                else -> throw UnsupportedOperationException(
                    "unexpected ZchatPreferences call in rotate path: ${method.name}"
                )
            }
        } as ZchatPreferences

    // ------------------------------------------------------------- other fakes

    private class HangingSynchronizerProvider : SynchronizerProvider {
        override val error: StateFlow<SynchronizerError?> = MutableStateFlow(null)
        override val synchronizer: StateFlow<Synchronizer?> = MutableStateFlow(null)
        override suspend fun getSynchronizer(): Synchronizer = awaitCancellation()
        override fun resetSynchronizer() = Unit
    }

    private class FakeExchangeRateRepository : ExchangeRateRepository {
        override val state: StateFlow<ExchangeRateState> = MutableStateFlow(ExchangeRateState.OptedOut)
        override fun refreshExchangeRateUsd() = Unit
    }

    private class FakeTransactionRepository : TransactionRepository {
        override val transactions: Flow<List<Transaction>?> = flowOf(null)
        override suspend fun getMemos(transaction: Transaction): List<String> = emptyList()
        override fun observeTransaction(txId: String): Flow<Transaction?> = flowOf(null)
        override fun observeTransactionsByMemo(memo: String): Flow<List<TransactionId>?> = flowOf(null)
        override suspend fun getTransactions(): List<Transaction> = emptyList()
        override suspend fun getAllRecipientAddresses(transaction: Transaction): List<String> = emptyList()
    }

    private class FakeContactBook : ContactBook {
        override fun addContact(contact: Contact) = Unit
        override fun removeContact(address: String) = Unit
        override fun getContact(address: String): Contact? = null
        override fun getAllContacts(): List<Contact> = emptyList()
        override fun hasContact(address: String): Boolean = false
        override fun updateContactName(address: String, newName: String) = Unit
        override fun clearAll() = Unit
    }

    private class FakePersistableWalletProvider : PersistableWalletProvider {
        override val persistableWallet: Flow<PersistableWallet?> = flowOf(null)
        override suspend fun store(persistableWallet: PersistableWallet) = Unit
        override suspend fun getPersistableWallet(): PersistableWallet? = null
        override suspend fun requirePersistableWallet(): PersistableWallet =
            error("seed path not used in this test")
    }

    // --------------------------------------------------------------- harness

    private suspend fun buildVm(state: PrefsState): GroupViewModel {
        val accountDataSource = Zc101FakeAccountDataSource(syntheticZashiAccount())
        val synchronizerProvider = HangingSynchronizerProvider()
        val getSelectedWalletAccount = GetSelectedWalletAccountUseCase(accountDataSource)
        val infoJson =
            ZMSGGroupProtocol.serializeGroupInfo(
                GroupInfo(
                    groupId = GROUP_ID,
                    name = "Rotate Test",
                    creatorAddress = SELF,
                    createdAt = Instant.ofEpochMilli(1_000L),
                )
            )
        val vm =
            GroupViewModel(
                transactionRepository = FakeTransactionRepository(),
                getSelectedWalletAccount = getSelectedWalletAccount,
                getDefaultUnifiedAddress =
                    GetDefaultUnifiedAddressUseCase(synchronizerProvider, getSelectedWalletAccount),
                accountDataSource = accountDataSource,
                createChunkedMessageProposal =
                    CreateChunkedMessageProposalUseCase(
                        keystoneProposalRepository = Zc101FakeKeystoneProposalRepository(),
                        zashiProposalRepository =
                            ZashiProposalRepositoryImpl(
                                accountDataSource = accountDataSource,
                                proposalDataSource = Zc101RecordingProposalDataSource(),
                                zashiSpendingKeyDataSource = Zc101FakeZashiSpendingKeyDataSource(),
                            ),
                        accountDataSource = accountDataSource,
                        navigationRouter = Zc101RecordingNavigationRouter(),
                        biometricRepository = Zc101SuspendingBiometricRepository(),
                    ),
                zchatPreferences = fakePrefs(state, infoJson),
                synchronizerProvider = synchronizerProvider,
                exchangeRateRepository = FakeExchangeRateRepository(),
                contactBook = FakeContactBook(),
                persistableWalletProvider = FakePersistableWalletProvider(),
            )
        vms += vm
        return vm
    }

    private fun member(address: String, status: MemberStatus, invite: InviteStatus? = null, admin: Boolean = false) =
        GroupMember(
            address = address,
            joinedAt = Instant.ofEpochMilli(1_000L),
            status = status,
            isAdmin = admin,
            inviteStatus = invite,
        )

    /** Force GroupViewModel._currentUserAddress (loadCurrentUserAddress hangs on the fake synchronizer). */
    @Suppress("UNCHECKED_CAST")
    private fun setSelfAddress(vm: GroupViewModel, address: String) {
        val field = GroupViewModel::class.java.getDeclaredField("_currentUserAddress").apply { isAccessible = true }
        (field.get(vm) as MutableStateFlow<String?>).value = address
    }

    /** Pre-seed the per-group rotationLock so the test controls its acquisition order, and return it. */
    @Suppress("UNCHECKED_CAST")
    private fun rotationMutex(vm: GroupViewModel): Mutex {
        val field = GroupViewModel::class.java.getDeclaredField("rotationLocks").apply { isAccessible = true }
        val map = field.get(vm) as ConcurrentHashMap<String, Mutex>
        return map.getOrPut(GROUP_ID) { Mutex() }
    }

    // R4-2 gave rotateAndNotify a "may this member be paid for on-chain" predicate (the kick / rotate
    // prompt's quote). This test is about the D5 roster/epoch race, not about money, so it passes the
    // always-pay predicate the rotation used before — every assertion below is unchanged.
    private val rotateMethod =
        GroupViewModel::class.java
            .getDeclaredMethod(
                "rotateAndNotify",
                String::class.java,
                String::class.java,
                Function1::class.java,
                Continuation::class.java,
            ).apply { isAccessible = true }

    /** Invoke the private suspend rotateAndNotify with our own continuation (no kotlin-reflect needed). */
    @Suppress("UNCHECKED_CAST")
    private suspend fun rotateAndNotify(vm: GroupViewModel, kicked: String?): List<String> =
        suspendCoroutineUninterceptedOrReturn<Any?> { cont ->
            rotateMethod.invoke(vm, GROUP_ID, kicked, { _: String -> true }, cont)
        } as List<String>

    // ----------------------------------------------------------------- tests

    @Test
    fun `F1 two concurrent kicks - the first-kicked member is not resurrected nor re-keyed by the stale second kick`() =
        runBlocking {
            val state =
                PrefsState(
                    listOf(
                        member(SELF, MemberStatus.ACTIVE, admin = true),
                        member(X, MemberStatus.ACTIVE),
                        member(Y, MemberStatus.ACTIVE),
                    )
                )
            val vm = buildVm(state)
            setSelfAddress(vm, SELF)

            // Hold the (fair) rotationLock so BOTH kicks read their roster and then queue on the lock
            // in launch order: kick-1 (kicks X) first, kick-2 (kicks Y) second.
            val mutex = rotationMutex(vm)
            mutex.lock()
            val j1 = launch(mainDispatcher) { rotateAndNotify(vm, X) }
            val j2 = launch(mainDispatcher) { rotateAndNotify(vm, Y) }
            // Both are now suspended at withLock. Release: kick-1 commits fully, then kick-2 runs.
            mutex.unlock()
            mainDispatcher.scheduler.advanceUntilIdle()
            j1.join()
            j2.join()

            val roster = ZMSGGroupProtocol.deserializeGroupMembers(state.membersJson)
            val x = roster.single { it.address == X }

            // With the bug (roster read outside the lock) kick-2's stale snapshot rewrites X back to
            // ACTIVE and re-sends it the fresh key.
            assertEquals(
                "the first-kicked member X must stay LEFT, not be resurrected by the stale 2nd kick",
                MemberStatus.LEFT,
                x.status,
            )
            // Round 8 P6 (PHONE_TRIAGE F5b): the 1st kick itself now looks up X's session once, after its commit, for the
            // removal notice (no key) it sends X. A stale 2nd kick re-keying X would be a second lookup.
            assertTrue(
                "the 2nd kick must not re-send the rotated key to the already-kicked X",
                state.e2ePrivateKeyCalls.count { it == X } <= 1,
            )
        }

    @Test
    fun `F3 an INVITED member that already holds the key is included in the kick fan-out, an undelivered invitee is not`() =
        runBlocking {
            val state =
                PrefsState(
                    listOf(
                        member(SELF, MemberStatus.ACTIVE, admin = true),
                        member(B, MemberStatus.ACTIVE),
                        member(C_INVITED_SENT, MemberStatus.INVITED, invite = InviteStatus.SENT),
                        member(D_INVITED_PENDING, MemberStatus.INVITED, invite = InviteStatus.INVITE_PENDING),
                    )
                )
            val vm = buildVm(state)
            setSelfAddress(vm, SELF)

            rotateAndNotify(vm, B) // kick B; rotates to epoch 1

            // C was sent the current group key inside its (delivered, SENT) invite — it must be
            // re-keyed by the kick or it keeps decrypting post-kick traffic.
            assertTrue(
                "an INVITED member holding the key (SENT invite) must be in the kick fan-out",
                state.e2ePrivateKeyCalls.contains(C_INVITED_SENT),
            )
            // D's invite never landed (INVITE_PENDING) — it never received a key, so it must NOT be
            // re-keyed (the fix must not fan out to every invitee).
            assertFalse(
                "an INVITED member whose invite is undelivered must NOT be re-keyed",
                state.e2ePrivateKeyCalls.contains(D_INVITED_PENDING),
            )

            val roster = ZMSGGroupProtocol.deserializeGroupMembers(state.membersJson)
            assertEquals("kicked member B is LEFT", MemberStatus.LEFT, roster.single { it.address == B }.status)
            assertEquals(
                "C (send failed, no KEX) must be persisted as owed the new epoch key so retry repairs it",
                1,
                roster.single { it.address == C_INVITED_SENT }.pendingKeyEpoch,
            )
            assertEquals(
                "D must remain a plain pending invitee, never marked owed a rotation key",
                null,
                roster.single { it.address == D_INVITED_PENDING }.pendingKeyEpoch,
            )
        }
}
