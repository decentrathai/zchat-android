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
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import java.lang.reflect.Proxy
import java.time.Instant
import kotlin.test.Test

/**
 * #223 (group pending/mined reconcile) + #426 (double-store across writer id schemes) — RED
 * regression pins for GroupViewModel.loadGroup's message-identity key.
 *
 * The reconcile/dedup identity MUST be the full (senderAddress, seq, epoch) triple
 * (GroupViewModel.kt ~line 263, `fun identity(m: GroupMessage)`), never `seq` alone:
 * seq is PER-SENDER monotonic, so two different members legitimately share seq values, and one
 * sender legitimately reuses a seq across a key-rotation epoch bump. A seq-only (or
 * seq+epoch-only, or sender+seq-only) key silently HIDES one of the colliding messages.
 *
 * Fixture idiom mirrors SwapQuoteVMToctouTest: REAL GroupViewModel on the JVM, main dispatcher =
 * UnconfinedTestDispatcher, hand-written fakes for the constructor seams (the money-path
 * dependencies are never invoked — this test only drives the pure load/reconcile path through
 * `loadGroups()`, which runs synchronously in init on the unconfined main dispatcher).
 *
 * ZchatPreferences (~200 methods) is faked with a dynamic Proxy that answers ONLY the five
 * group-load reads and throws loudly on anything else. All addresses/ids are synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GroupReconcileIdentityTest {

    private companion object {
        const val GROUP_ID = "zgrp_reconciletest"
        const val ALICE = "usynthetic-alice"
        const val BOB = "usynthetic-bob"
    }

    private val vms = mutableListOf<GroupViewModel>()

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        // Kill the forever-suspended loadCurrentUserAddress + exchange-rate collectors.
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

    // ------------------------------------------------------------------ fakes

    /** Answers exactly the reads GroupViewModel's load path performs; anything else throws. */
    private fun fakePrefs(
        groupIds: Set<String>,
        infoJson: Map<String, String>,
        messagesJson: Map<String, String>,
    ): ZchatPreferences =
        Proxy.newProxyInstance(
            ZchatPreferences::class.java.classLoader,
            arrayOf(ZchatPreferences::class.java),
        ) { proxy, method, args ->
            when (method.name) {
                "getAllGroupIds" -> groupIds
                "getGroupInfo" -> infoJson[args!![0] as String]
                "getGroupMembers" -> null
                "getGroupDraft" -> null
                "getGroupMessages" -> messagesJson[args!![0] as String]
                "registerSelfAddress" -> null
                "toString" -> "FakeZchatPreferences"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args!![0]
                else -> throw UnsupportedOperationException(
                    "unexpected ZchatPreferences call in load path: ${method.name}"
                )
            }
        } as ZchatPreferences

    private class HangingSynchronizerProvider : SynchronizerProvider {
        override val error: StateFlow<SynchronizerError?> = MutableStateFlow(null)
        override val synchronizer: StateFlow<Synchronizer?> = MutableStateFlow(null)

        /** Parks loadCurrentUserAddress forever — the address is irrelevant to reconcile. */
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

    /** Real GroupViewModel whose init { loadGroups() } runs to completion on the unconfined main. */
    private suspend fun vmWithStoredMessages(storedMessages: JSONArray): GroupViewModel {
        val accountDataSource = Zc101FakeAccountDataSource(syntheticZashiAccount())
        val synchronizerProvider = HangingSynchronizerProvider()
        val getSelectedWalletAccount = GetSelectedWalletAccountUseCase(accountDataSource)
        val infoJson =
            ZMSGGroupProtocol.serializeGroupInfo(
                GroupInfo(
                    groupId = GROUP_ID,
                    name = "Reconcile Test",
                    creatorAddress = ALICE,
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
                zchatPreferences =
                    fakePrefs(
                        groupIds = setOf(GROUP_ID),
                        infoJson = mapOf(GROUP_ID to infoJson),
                        messagesJson = mapOf(GROUP_ID to storedMessages.toString()),
                    ),
                synchronizerProvider = synchronizerProvider,
                exchangeRateRepository = FakeExchangeRateRepository(),
                contactBook = FakeContactBook(),
                persistableWalletProvider = FakePersistableWalletProvider(),
            )
        vms += vm
        return vm
    }

    /** One stored-message record in exactly the schema parseStoredGroupMessages reads. */
    private fun storedMsg(
        id: String,
        sender: String,
        seq: Long,
        epoch: Int,
        decrypted: String?,
        encrypted: String? = null,
        timestampMillis: Long,
    ): JSONObject =
        JSONObject().apply {
            put("id", id)
            put("groupId", GROUP_ID)
            put("seq", seq)
            put("epoch", epoch)
            put("sender", sender)
            decrypted?.let { put("decrypted", it) }
            encrypted?.let { put("encrypted", it) }
            put("timestamp", timestampMillis)
        }

    private fun jsonArrayOf(vararg objs: JSONObject) = JSONArray().apply { objs.forEach { put(it) } }

    // ----------------------------------------------------------------- tests

    @Test
    fun `two senders sharing the same seq are BOTH retained - seq is per-sender, not global`() =
        runBlocking {
            // Alice's seq=1 and Bob's seq=1 are two DIFFERENT messages (seq is per-sender monotonic).
            val vm =
                vmWithStoredMessages(
                    jsonArrayOf(
                        storedMsg(
                            id = "txa_1",
                            sender = ALICE,
                            seq = 1L,
                            epoch = 0,
                            decrypted = "from-alice",
                            timestampMillis = 10_000L,
                        ),
                        storedMsg(
                            id = "txb_1",
                            sender = BOB,
                            seq = 1L,
                            epoch = 0,
                            decrypted = "from-bob",
                            timestampMillis = 11_000L,
                        ),
                    )
                )

            val conversation = vm.groupConversations.value.single()
            val texts = conversation.messages.map { it.decryptedContent }

            // MUTATION that reddens this: change loadGroup's identity key from
            // Triple(senderAddress, seq, epoch) to seq alone (or to (seq, epoch)) — the #426
            // groupBy(identity) then collapses Alice's and Bob's distinct messages into ONE bucket
            // and keeps a single message: messages.size == 1 and one of the two texts vanishes
            // (the original "#223 seq-only reconcile hides another member's message" bug).
            assertEquals("both senders' seq=1 messages must be retained", 2, conversation.messages.size)
            assertTrue("Alice's message hidden by seq collision", texts.contains("from-alice"))
            assertTrue("Bob's message hidden by seq collision", texts.contains("from-bob"))
        }

    @Test
    fun `same sender reusing a seq across a key-rotation epoch keeps BOTH messages`() =
        runBlocking {
            // After a rotation (epoch 0 -> 1) a sender's seq stream can legitimately collide with a
            // pre-rotation seq. Epoch is part of the identity, so both survive.
            val vm =
                vmWithStoredMessages(
                    jsonArrayOf(
                        storedMsg(
                            id = "txa_e0",
                            sender = ALICE,
                            seq = 5L,
                            epoch = 0,
                            decrypted = "pre-rotation",
                            timestampMillis = 10_000L,
                        ),
                        storedMsg(
                            id = "txa_e1",
                            sender = ALICE,
                            seq = 5L,
                            epoch = 1,
                            decrypted = "post-rotation",
                            timestampMillis = 12_000L,
                        ),
                    )
                )

            val conversation = vm.groupConversations.value.single()
            val texts = conversation.messages.map { it.decryptedContent }

            // MUTATION that reddens this: drop `epoch` from the identity key — i.e.
            // Pair(senderAddress, seq) — the two records share one bucket, dedup keeps only one, and
            // either the pre- or post-rotation message silently disappears (messages.size == 1).
            assertEquals("epoch must disambiguate a reused seq", 2, conversation.messages.size)
            assertTrue(texts.contains("pre-rotation"))
            assertTrue(texts.contains("post-rotation"))
        }

    @Test
    fun `426 duplicate rows from the two writer id-schemes collapse to one, preferring the decrypted copy`() =
        runBlocking {
            // The SAME on-chain message stored twice under different id schemes: the history scan's
            // "<txid>_<seq>" row (decrypted) and the live path's plain "<txid>" row (ciphertext-only).
            // Identity (sender, seq, epoch) is identical -> exactly ONE message, the readable one.
            val vm =
                vmWithStoredMessages(
                    jsonArrayOf(
                        storedMsg(
                            id = "txdup",
                            sender = ALICE,
                            seq = 7L,
                            epoch = 0,
                            decrypted = null,
                            encrypted = "b64-ciphertext",
                            timestampMillis = 10_000L,
                        ),
                        storedMsg(
                            id = "txdup_7",
                            sender = ALICE,
                            seq = 7L,
                            epoch = 0,
                            decrypted = "hello group",
                            timestampMillis = 10_000L,
                        ),
                    )
                )

            val conversation = vm.groupConversations.value.single()

            // MUTATIONS that redden this:
            //  - revert #426 (drop the groupBy(identity) collapse): both rows survive id-dedup because
            //    their ids differ ("txdup" vs "txdup_7") -> messages.size == 2 (the double-bubble bug);
            //  - drop the "prefer decrypted" pick (dup.first() unconditionally): the retained copy is
            //    the ciphertext-only row -> decryptedContent == null.
            assertEquals("the same message stored under both id schemes must render once", 1, conversation.messages.size)
            val kept = conversation.messages.single()
            assertNotNull("dedup must prefer the decrypted copy", kept.decryptedContent)
            assertEquals("hello group", kept.decryptedContent)
        }
}
