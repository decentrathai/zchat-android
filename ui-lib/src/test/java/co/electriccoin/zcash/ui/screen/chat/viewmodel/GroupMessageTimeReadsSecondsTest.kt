package co.electriccoin.zcash.ui.screen.chat.viewmodel

import androidx.lifecycle.viewModelScope
import cash.z.ecc.android.sdk.Synchronizer
import cash.z.ecc.android.sdk.model.PersistableWallet
import cash.z.ecc.android.sdk.model.TransactionId
import cash.z.ecc.android.sdk.model.Zatoshi
import co.electriccoin.zcash.ui.common.model.SynchronizerError
import co.electriccoin.zcash.ui.common.provider.PersistableWalletProvider
import co.electriccoin.zcash.ui.common.provider.SynchronizerProvider
import co.electriccoin.zcash.ui.common.repository.ExchangeRateRepository
import co.electriccoin.zcash.ui.common.repository.ReceiveTransaction
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
import co.electriccoin.zcash.ui.screen.chat.model.GroupMessage
import co.electriccoin.zcash.ui.screen.chat.model.ZMSGConstants
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
import org.junit.Before
import java.lang.reflect.Proxy
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.test.Test

/**
 * B7, GROUP-MSG-TIME (found on the HONOR, round 6b report section 4 item 2): every group message showed
 * a 1970 date.
 *
 * Every sender (2.12.4 and 2.13.0) writes the GROUP_MSG `ts` in SECONDS
 * (ZMSGGroupProtocol: `System.currentTimeMillis() / 1000`), and the wire must stay that way so a
 * 2.12.4 phone keeps reading what 2.13.0 sends. The receiver's history scan read that value as
 * MILLISECONDS (`Instant.ofEpochMilli(msgPayload.timestamp)`), so 1790000000 s (2026-09-21) became
 * 1790000000 ms (1970-01-21), and it then stored `toEpochMilli()` of that wrong instant, which is the
 * raw seconds number again. So rows already on phones hold raw seconds, and the read side must repair
 * them; nothing stored is rewritten.
 *
 * A value that is clearly milliseconds already (>= 1e11, i.e. every millisecond time after March 1973)
 * stays milliseconds: that is how the live chain path, the NOSTR path and our own pending rows store
 * their times.
 *
 * REAL GroupViewModel on the JVM (the GroupReconcileIdentityTest idiom); the group key and the GROUP_MSG
 * memos are real (AES-GCM through the android.util.Base64 test shadow). All addresses and ids are
 * synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GroupMessageTimeReadsSecondsTest {
    private companion object {
        const val GROUP_ID = "zgrp_timetest"
        const val GROUP_ID_OTHER = "zgrp_timetest_other"
        const val ALICE = "usynthetic-alice"
        const val BOB = "usynthetic-bob"

        /** 2026-09-21 14:13:20 UTC, in seconds, exactly as a sender writes the wire `ts`. */
        const val NEWER_SECONDS = 1_790_000_000L

        /** 2026-09-21 11:26:40 UTC, as MILLISECONDS (how a live-path or NOSTR row is stored). */
        const val OLDER_MILLIS = 1_789_990_000_000L

        val SEPT_21_2026: LocalDate = LocalDate.of(2026, 9, 21)
    }

    private val vms = mutableListOf<GroupViewModel>()

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
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

    /** The group reads and writes the load and history-scan paths perform; anything else throws. */
    private fun fakePrefs(
        groupIds: Set<String>,
        infoJson: Map<String, String>,
        messagesJson: MutableMap<String, String>,
        groupKey: String? = null,
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
                "saveGroupMessages" -> {
                    messagesJson[args!![0] as String] = args[1] as String
                    null
                }
                "getGroupKeyEpoch" -> 0
                "getGroupKey" -> if ((args!![1] as Int) == 0) groupKey else null
                "getGroupRemovalFloor" -> 0
                "registerSelfAddress" -> null
                "toString" -> "FakeZchatPreferences"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args!![0]
                else -> throw UnsupportedOperationException(
                    "unexpected ZchatPreferences call: ${method.name}"
                )
            }
        } as ZchatPreferences

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

    /** The chain as the history scan sees it: each transaction with its memos. */
    private class FakeTransactionRepository(
        private val txs: List<Pair<Transaction, List<String>>> = emptyList(),
    ) : TransactionRepository {
        override val transactions: Flow<List<Transaction>?> = flowOf(null)

        override suspend fun getMemos(transaction: Transaction): List<String> =
            txs.first { it.first.id == transaction.id }.second

        override fun observeTransaction(txId: String): Flow<Transaction?> = flowOf(null)

        override fun observeTransactionsByMemo(memo: String): Flow<List<TransactionId>?> = flowOf(null)

        override suspend fun getTransactions(): List<Transaction> = txs.map { it.first }

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

    private fun infoJson(groupId: String): String =
        ZMSGGroupProtocol.serializeGroupInfo(
            GroupInfo(
                groupId = groupId,
                name = "Time Test $groupId",
                creatorAddress = ALICE,
                // A creation time well before every message, so a group's place in the list is
                // decided by its last message.
                createdAt = Instant.parse("2026-09-01T00:00:00Z"),
            )
        )

    private suspend fun vm(
        prefs: ZchatPreferences,
        transactionRepository: TransactionRepository = FakeTransactionRepository(),
    ): GroupViewModel {
        val accountDataSource = Zc101FakeAccountDataSource(syntheticZashiAccount())
        val synchronizerProvider = HangingSynchronizerProvider()
        val getSelectedWalletAccount = GetSelectedWalletAccountUseCase(accountDataSource)
        val vm =
            GroupViewModel(
                transactionRepository = transactionRepository,
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
                zchatPreferences = prefs,
                synchronizerProvider = synchronizerProvider,
                exchangeRateRepository = FakeExchangeRateRepository(),
                contactBook = FakeContactBook(),
                persistableWalletProvider = FakePersistableWalletProvider(),
            )
        vms += vm
        return vm
    }

    /** One stored row in exactly the schema both writers use and parseStoredGroupMessages reads. */
    private fun storedRow(
        id: String,
        groupId: String = GROUP_ID,
        sender: String,
        seq: Long,
        text: String,
        storedTimestamp: Long,
    ): JSONObject =
        JSONObject().apply {
            put("id", id)
            put("groupId", groupId)
            put("seq", seq)
            put("epoch", 0)
            put("sender", sender)
            put("decrypted", text)
            put("timestamp", storedTimestamp)
        }

    private fun rows(vararg objs: JSONObject) = JSONArray().apply { objs.forEach { put(it) } }.toString()

    /** A real GROUP_MSG memo on the wire layout, with the sender's `ts` in seconds. */
    private fun groupMsgMemo(
        key: ByteArray,
        sender: String,
        seq: Long,
        text: String,
        tsSeconds: Long,
    ): String {
        val enc = ZMSGGroupProtocol.encryptMessage(text, key)
        val payload =
            JSONObject().apply {
                put("seq", seq)
                put("epoch", 0)
                put("sender", sender)
                put("nonce", enc.nonce)
                put("ct", enc.ciphertext)
                put("ts", tsSeconds)
            }
        return "${ZMSGConstants.Prefixes.GROUP}GM:$GROUP_ID:$payload"
    }

    private fun receivedTx(n: Int, blockTime: Instant): Transaction {
        val id = TransactionId.new(ByteArray(32) { (it + n * 31).toByte() })
        return ReceiveTransaction.Success(
            id = id,
            amount = Zatoshi(1_000),
            timestamp = blockTime,
            memoCount = 1,
            transactionOutputs = emptyList(),
            overview = ScanPassHarness.overview(id, 3_000_000L + n, blockTime, sent = false, amount = 1_000),
            recipient = null,
        )
    }

    private fun GroupMessage.utcDate(): LocalDate = timestamp.atZone(ZoneOffset.UTC).toLocalDate()

    // ----------------------------------------------------------------- tests

    @Test
    fun `a received group message with ts 1790000000 shows 2026-09-21 and sorts after an older one`() =
        runBlocking {
            val key = ZMSGGroupProtocol.generateGroupKey()
            val stored =
                mutableMapOf(
                    // The older message arrived free over NOSTR, so its row is in milliseconds and the
                    // history scan carries it forward untouched.
                    GROUP_ID to
                        rows(
                            storedRow(id = "grpn-$GROUP_ID-older", sender = BOB, seq = 1L, text = "older", storedTimestamp = OLDER_MILLIS),
                        ),
                )
            val prefs =
                fakePrefs(
                    groupIds = setOf(GROUP_ID),
                    infoJson = mapOf(GROUP_ID to infoJson(GROUP_ID)),
                    messagesJson = stored,
                    groupKey = ZMSGGroupProtocol.encodeGroupKey(key),
                )
            val chain =
                FakeTransactionRepository(
                    listOf(
                        receivedTx(1, Instant.ofEpochSecond(NEWER_SECONDS + 75)) to
                            listOf(groupMsgMemo(key, ALICE, seq = 1L, text = "newer", tsSeconds = NEWER_SECONDS)),
                    )
                )
            val vm = vm(prefs, chain)

            vm.loadGroupMessagesFromHistory(GROUP_ID)

            val messages =
                vm.groupConversations.value
                    .single()
                    .messages
            val newer = messages.single { it.decryptedContent == "newer" }
            // Before the fix: Instant.ofEpochMilli(1790000000) is 1970-01-21T17:13:20Z.
            assertEquals("the wire ts is in seconds", Instant.ofEpochSecond(NEWER_SECONDS), newer.timestamp)
            assertEquals(SEPT_21_2026, newer.utcDate())
            // The older (NOSTR, milliseconds) row keeps its own time.
            assertEquals(Instant.ofEpochMilli(OLDER_MILLIS), messages.single { it.decryptedContent == "older" }.timestamp)
            // Before the fix: the 1970 row sorts above the older 2026 row.
            assertEquals(listOf("older", "newer"), messages.map { it.decryptedContent })
            assertEquals(
                "newer",
                vm.groupConversations.value
                    .single()
                    .lastMessage
                    ?.decryptedContent
            )
        }

    @Test
    fun `a row already stored in raw seconds by the old history scan shows its real date with no migration`() =
        runBlocking {
            // Exactly what 2.12.4 and 2.13.0 up to now stored: toEpochMilli() of ofEpochMilli(seconds),
            // i.e. the raw seconds number, next to a millisecond row from the live path.
            val stored =
                mutableMapOf(
                    GROUP_ID to
                        rows(
                            storedRow(id = "txnewer_1", sender = ALICE, seq = 1L, text = "newer", storedTimestamp = NEWER_SECONDS),
                            storedRow(id = "txolder", sender = BOB, seq = 1L, text = "older", storedTimestamp = OLDER_MILLIS),
                        ),
                )
            val before = stored.getValue(GROUP_ID)
            val vm =
                vm(
                    fakePrefs(
                        groupIds = setOf(GROUP_ID),
                        infoJson = mapOf(GROUP_ID to infoJson(GROUP_ID)),
                        messagesJson = stored,
                    )
                )

            val conversation = vm.groupConversations.value.single()
            val newer = conversation.messages.single { it.decryptedContent == "newer" }
            assertEquals(Instant.ofEpochSecond(NEWER_SECONDS), newer.timestamp)
            assertEquals(SEPT_21_2026, newer.utcDate())
            assertEquals(Instant.ofEpochMilli(OLDER_MILLIS), conversation.messages.single { it.decryptedContent == "older" }.timestamp)
            assertEquals(listOf("older", "newer"), conversation.messages.map { it.decryptedContent })
            assertEquals("newer", conversation.lastMessage?.decryptedContent)
            // Loading never rewrites what is stored.
            assertEquals(before, stored.getValue(GROUP_ID))
        }

    @Test
    fun `a millisecond value stays milliseconds and a seconds value just below 1e11 is read as seconds`() =
        runBlocking {
            val stored =
                mutableMapOf(
                    GROUP_ID to
                        rows(
                            // 1e11 ms = 1973-03-03T09:46:40Z: the first value read as milliseconds.
                            storedRow(id = "txms", sender = ALICE, seq = 1L, text = "ms", storedTimestamp = 100_000_000_000L),
                            // 1e11 - 1 read as seconds = year 5138: the largest value read as seconds.
                            storedRow(id = "txs", sender = BOB, seq = 1L, text = "s", storedTimestamp = 99_999_999_999L),
                        ),
                )
            val vm =
                vm(
                    fakePrefs(
                        groupIds = setOf(GROUP_ID),
                        infoJson = mapOf(GROUP_ID to infoJson(GROUP_ID)),
                        messagesJson = stored,
                    )
                )

            val messages =
                vm.groupConversations.value
                    .single()
                    .messages
            assertEquals(Instant.ofEpochMilli(100_000_000_000L), messages.single { it.decryptedContent == "ms" }.timestamp)
            assertEquals(Instant.ofEpochSecond(99_999_999_999L), messages.single { it.decryptedContent == "s" }.timestamp)
        }

    @Test
    fun `the group whose last message is newer comes first in the group list, whatever unit its row was stored in`() =
        runBlocking {
            val stored =
                mutableMapOf(
                    // Group A's last message: 2026-09-21 14:13 UTC, stored in raw seconds.
                    GROUP_ID to
                        rows(
                            storedRow(id = "txa_1", sender = ALICE, seq = 1L, text = "a", storedTimestamp = NEWER_SECONDS),
                        ),
                    // Group B's last message: 2026-09-21 11:26 UTC, stored in milliseconds.
                    GROUP_ID_OTHER to
                        rows(
                            storedRow(
                                id = "txb",
                                groupId = GROUP_ID_OTHER,
                                sender = BOB,
                                seq = 1L,
                                text = "b",
                                storedTimestamp = OLDER_MILLIS,
                            ),
                        ),
                )
            val vm =
                vm(
                    fakePrefs(
                        groupIds = setOf(GROUP_ID_OTHER, GROUP_ID),
                        infoJson = mapOf(GROUP_ID to infoJson(GROUP_ID), GROUP_ID_OTHER to infoJson(GROUP_ID_OTHER)),
                        messagesJson = stored,
                    )
                )

            // Before the fix: group A's last message reads as 1970, so it sorts below B (and below its own
            // creation time).
            assertEquals(
                listOf(GROUP_ID, GROUP_ID_OTHER),
                vm.groupConversations.value.map { it.groupInfo.groupId },
            )
        }
}
