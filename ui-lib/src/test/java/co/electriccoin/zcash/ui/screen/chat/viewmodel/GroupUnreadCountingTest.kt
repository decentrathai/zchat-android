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
import co.electriccoin.zcash.ui.screen.chat.model.ChatListState
import co.electriccoin.zcash.ui.screen.chat.model.Contact
import co.electriccoin.zcash.ui.screen.chat.model.ContactBook
import co.electriccoin.zcash.ui.screen.chat.model.GroupInfo
import co.electriccoin.zcash.ui.screen.chat.model.GroupUnread
import co.electriccoin.zcash.ui.screen.chat.usecase.CreateChunkedMessageProposalUseCase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import java.lang.reflect.Proxy
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test

/**
 * ZC-1104 (W-79) — the Groups row's per-group unread count is pinned (guard tests: they pass on the fix).
 *
 * [GroupUnread.count] counts the stored group messages from OTHER senders, not held, newer than the last-read mark and
 * not dated in the future, a message stored twice counted once. [GroupViewModel.markGroupRead] moves the mark to
 * max(now, newest shown message) under `GroupUnread.markerKey(groupId)` ("group:<id>", stored as `lastread:group:<id>`),
 * and [ChatViewModel.groupUnread] recounts when the group list, the marker or the group store changes; a group that is
 * not in the list is not in the map. Every address and id is synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GroupUnreadCountingTest {
    private companion object {
        const val GROUP = "zgrp_unreadtest"
        const val GROUP_B = "zgrp_unreadtest_b"
        const val GROUP_NOT_LISTED = "zgrp_unreadtest_unlisted"
        const val ME = "usynthetic-unread-me"
        const val ALICE = "usynthetic-unread-alice"
        const val BOB = "usynthetic-unread-bob"

        /** A millisecond clock well above the seconds/millis cut-off. */
        const val NOW = 1_790_000_000_000L
        const val MINUTE = 60_000L

        val NOT_SELF: (String) -> Boolean = { it == ME }
    }

    private val scopes = SendHarnessScopes()

    @Before
    fun setUp() {
        scopes.open()
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        scopes.close()
        Dispatchers.resetMain()
    }

    // ------------------------------------------------------------ the counter

    private fun row(
        sender: String,
        seq: Long,
        at: Long,
        id: String = "id-$sender-$seq-$at",
        held: String? = null,
        epoch: Int = 0,
    ): JSONObject =
        JSONObject().apply {
            put("id", id)
            put("groupId", GROUP)
            put("seq", seq)
            put("epoch", epoch)
            put("sender", sender)
            put("decrypted", "synthetic text")
            put("timestamp", at)
            if (held != null) put("held", held)
        }

    private fun rows(vararg objs: JSONObject): String = JSONArray().apply { objs.forEach { put(it) } }.toString()

    private fun count(
        json: String?,
        lastRead: Long = 0L,
        now: Long = NOW,
    ) = GroupUnread.count(json, lastRead, now, NOT_SELF)

    @Test
    fun `the marker key is group colon id`() {
        assertEquals("group:$GROUP", GroupUnread.markerKey(GROUP))
    }

    @Test
    fun `no stored messages, blank or unreadable json count zero`() {
        assertEquals(0, count(null))
        assertEquals(0, count(""))
        assertEquals(0, count("   "))
        assertEquals(0, count("not json"))
        assertEquals(0, count("[]"))
    }

    @Test
    fun `messages from others newer than the mark are counted`() {
        val json = rows(row(ALICE, 1, NOW - 3 * MINUTE), row(BOB, 1, NOW - 2 * MINUTE), row(ALICE, 2, NOW - MINUTE))
        assertEquals(3, count(json, lastRead = NOW - 10 * MINUTE))
    }

    @Test
    fun `our own messages are not counted`() {
        val json = rows(row(ME, 1, NOW - MINUTE), row(ALICE, 1, NOW - MINUTE))
        assertEquals(1, count(json, lastRead = NOW - 10 * MINUTE))
    }

    @Test
    fun `our own message does not hide another sender's message with the same seq and time`() {
        val json = rows(row(ME, 1, NOW - MINUTE), row(ALICE, 1, NOW - MINUTE), row(BOB, 1, NOW - MINUTE))
        assertEquals(2, count(json, lastRead = NOW - 10 * MINUTE))
    }

    @Test
    fun `messages at or before the mark are not counted`() {
        val mark = NOW - 5 * MINUTE
        val json =
            rows(
                row(ALICE, 1, mark - MINUTE),
                // exactly at the mark: read
                row(ALICE, 2, mark),
                // one millisecond after the mark: unread
                row(ALICE, 3, mark + 1),
            )
        assertEquals(1, count(json, lastRead = mark))
    }

    @Test
    fun `a future dated message is not counted but one at now is`() {
        val json =
            rows(
                row(ALICE, 1, NOW + 1),
                row(ALICE, 2, NOW + 60 * MINUTE),
                row(ALICE, 3, NOW),
            )
        assertEquals(1, count(json, lastRead = NOW - 10 * MINUTE))
    }

    @Test
    fun `held messages are not counted`() {
        val json =
            rows(
                row(ALICE, 1, NOW - MINUTE, held = "key"),
                row(ALICE, 2, NOW - MINUTE, held = "pending"),
                row(ALICE, 3, NOW - MINUTE),
            )
        assertEquals(1, count(json, lastRead = NOW - 10 * MINUTE))
    }

    @Test
    fun `a message stored twice under two ids is counted once`() {
        val json =
            rows(
                row(ALICE, 7, NOW - MINUTE, id = "grpn-$GROUP-first"),
                row(ALICE, 7, NOW - MINUTE, id = "tx-second"),
                row(ALICE, 8, NOW - MINUTE),
            )
        assertEquals(2, count(json, lastRead = NOW - 10 * MINUTE))
    }

    @Test
    fun `a row with no sender is not counted and the same seq in another epoch is a different message`() {
        val json =
            rows(
                row("", 1, NOW - MINUTE),
                row(ALICE, 1, NOW - MINUTE, epoch = 0),
                row(ALICE, 1, NOW - MINUTE, epoch = 1),
            )
        assertEquals(2, count(json, lastRead = NOW - 10 * MINUTE))
    }

    @Test
    fun `a time stored in seconds is read as seconds`() {
        val seconds = (NOW - MINUTE) / 1000
        val json = rows(row(ALICE, 1, seconds))
        assertEquals(1, count(json, lastRead = NOW - 10 * MINUTE))
        assertEquals(0, count(json, lastRead = NOW - 30_000L))
    }

    // ------------------------------------------- the two view models, one store

    /** One wallet's preferences: group messages, the shared read markers and our address. */
    private class Store {
        val messages = mutableMapOf<String, String>()
        val markers = MutableStateFlow<Map<String, Long>>(emptyMap())
        val markerWrites = mutableListOf<Pair<String, Long>>()
        val storeReads = AtomicInteger()

        /** Every group already has a mark ten minutes back (the state after the group screen was opened once). */
        fun markAllOld(
            n: Long,
            vararg ids: String,
        ) {
            markers.value = markers.value + ids.associate { GroupUnread.markerKey(it) to n - 10 * MINUTE }
        }

        val proxy: ZchatPreferences =
            Proxy.newProxyInstance(
                ZchatPreferences::class.java.classLoader,
                arrayOf(ZchatPreferences::class.java),
            ) { p, method, args ->
                when (method.name) {
                    "getGroupMessages" -> {
                        storeReads.incrementAndGet()
                        messages[args!![0] as String]
                    }
                    "isSelfAddress" -> args!![0] == ME
                    "getReadMarkers" -> markers
                    "getAllLastReadTimestamps" -> markers.value
                    "setLastReadTimestamp" -> {
                        val key = args!![0] as String
                        val millis = args[1] as Long
                        markerWrites += key to millis
                        markers.value = markers.value + (key to maxOf(millis, markers.value[key] ?: 0L))
                        null
                    }
                    "getAllGroupIds" -> messages.keys.toSet()
                    "toString" -> "FakeUnreadStore"
                    "hashCode" -> System.identityHashCode(p)
                    "equals" -> p === args!![0]
                    else ->
                        when (method.returnType) {
                            java.lang.Boolean.TYPE -> false
                            Integer.TYPE -> 0
                            java.lang.Long.TYPE -> 0L
                            else -> null
                        }
                }
            } as ZchatPreferences
    }

    private fun group(id: String) =
        GroupInfo(
            groupId = id,
            name = "Unread $id",
            creatorAddress = ALICE,
            createdAt = Instant.ofEpochMilli(NOW - 1_000 * MINUTE),
        )

    private fun listState(vararg ids: String) =
        ChatListState.Success(
            conversations = emptyList(),
            groups = ids.map(::group),
            currentUserAddress = ME,
        )

    private class ListVm(
        val vm: ChatViewModel,
        val list: MutableStateFlow<ChatListState>,
    ) {
        val unread: Map<String, Int> get() = vm.groupUnread.value
    }

    private suspend fun listVm(
        store: Store,
        vararg ids: String,
    ): ListVm {
        val vm = HandshakeHarness.chatViewModelWith(store.proxy, HandshakeSeam(ALICE))
        val list = MutableStateFlow<ChatListState>(listState(*ids))
        set(vm, "_chatListState", list)
        // The constructor-free allocation skips the lazy; build it from the compiled initializer lambda.
        val initializer =
            ChatViewModel::class.java.declaredMethods.single { it.name.startsWith("groupUnread_delegate\$lambda") }
        initializer.isAccessible = true
        set(vm, "groupUnread\$delegate", lazy { initializer.invoke(null, vm) })
        // WhileSubscribed: keep one collector on, as the chat list does.
        vm.viewModelScope.launch { vm.groupUnread.collect { } }
        return ListVm(vm, list)
    }

    private fun set(
        vm: ChatViewModel,
        name: String,
        value: Any,
    ) {
        ChatViewModel::class.java
            .getDeclaredField(name)
            .apply { isAccessible = true }
            .set(vm, value)
    }

    private suspend fun waitUntil(done: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + HandshakeHarness.TIMEOUT_MS
        while (!done()) {
            if (System.currentTimeMillis() > deadline) return false
            kotlinx.coroutines.delay(10)
        }
        return true
    }

    private suspend fun awaitUnread(
        list: ListVm,
        expected: Map<String, Int>,
    ) {
        val ok = waitUntil { list.unread == expected }
        assertEquals("the Groups row's unread counts", expected, list.unread)
        assertFalse("timed out", !ok)
    }

    private suspend fun groupVm(store: Store): GroupViewModel {
        val owner = SendHarnessScopes.current()
        val accounts = Zc101FakeAccountDataSource(syntheticZashiAccount())
        val sync = HangingSynchronizerProvider()
        val selected = GetSelectedWalletAccountUseCase(accounts)
        return GroupViewModel(
            transactionRepository = NoTransactions(),
            getSelectedWalletAccount = selected,
            getDefaultUnifiedAddress = GetDefaultUnifiedAddressUseCase(sync, selected),
            accountDataSource = accounts,
            createChunkedMessageProposal =
                CreateChunkedMessageProposalUseCase(
                    keystoneProposalRepository = Zc101FakeKeystoneProposalRepository(),
                    zashiProposalRepository =
                        ZashiProposalRepositoryImpl(
                            accountDataSource = accounts,
                            proposalDataSource = Zc101RecordingProposalDataSource(),
                            zashiSpendingKeyDataSource = Zc101FakeZashiSpendingKeyDataSource(),
                        ).also(owner::own),
                    accountDataSource = accounts,
                    navigationRouter = Zc101RecordingNavigationRouter(),
                    biometricRepository = Zc101SuspendingBiometricRepository(),
                ),
            zchatPreferences = store.proxy,
            synchronizerProvider = sync,
            exchangeRateRepository = OptedOutRates(),
            contactBook = NoContactsBook(),
            persistableWalletProvider = NoWallet(),
        ).also(owner::own)
    }

    private fun now() = System.currentTimeMillis()

    @Test
    fun `the list counts per group from others, newer than the mark, past, not held, once`() =
        runBlocking {
            val n = now()
            val store = Store()
            store.messages[GROUP] =
                rows(
                    row(ALICE, 1, n - 3 * MINUTE),
                    row(ALICE, 1, n - 3 * MINUTE, id = "again"),
                    row(BOB, 1, n - 2 * MINUTE),
                    row(ME, 1, n - MINUTE),
                    row(BOB, 2, n - MINUTE, held = "key"),
                    row(BOB, 3, n + 60 * MINUTE),
                    row(ALICE, 9, n - 20 * MINUTE),
                )
            store.markers.value = mapOf(GroupUnread.markerKey(GROUP) to n - 10 * MINUTE)
            store.messages[GROUP_B] = rows(row(BOB, 1, n - MINUTE))
            store.markers.value = store.markers.value + (GroupUnread.markerKey(GROUP_B) to n - 10 * MINUTE)
            val list = listVm(store, GROUP, GROUP_B)

            awaitUnread(list, mapOf(GROUP to 2, GROUP_B to 1))
        }

    @Test
    fun `a group that is not in the list is not in the map`() =
        runBlocking {
            val n = now()
            val store = Store()
            store.messages[GROUP] = rows(row(ALICE, 1, n - MINUTE))
            store.messages[GROUP_NOT_LISTED] = rows(row(ALICE, 1, n - MINUTE))
            store.markAllOld(n, GROUP, GROUP_NOT_LISTED)
            val list = listVm(store, GROUP)

            awaitUnread(list, mapOf(GROUP to 1))
            assertFalse(list.unread.containsKey(GROUP_NOT_LISTED))
        }

    @Test
    fun `markGroupRead clears the group count, writes the marker under group colon id and leaves other groups alone`() =
        runBlocking {
            val n = now()
            val store = Store()
            store.messages[GROUP] = rows(row(ALICE, 1, n - 2 * MINUTE), row(BOB, 1, n - MINUTE))
            store.messages[GROUP_B] = rows(row(BOB, 1, n - MINUTE))
            store.markAllOld(n, GROUP, GROUP_B)
            val list = listVm(store, GROUP, GROUP_B)
            awaitUnread(list, mapOf(GROUP to 2, GROUP_B to 1))
            val groupVm = groupVm(store)

            val before = now()
            groupVm.markGroupRead(GROUP)

            assertEquals(1, store.markerWrites.size)
            assertEquals(GroupUnread.markerKey(GROUP), store.markerWrites.single().first)
            val mark = store.markerWrites.single().second
            assertEquals("the mark is not before now", true, mark >= before)
            awaitUnread(list, mapOf(GROUP to 0, GROUP_B to 1))
        }

    @Test
    fun `a message that arrives after the mark counts one`() =
        runBlocking {
            val n = now()
            val store = Store()
            store.messages[GROUP] = rows(row(ALICE, 1, n - MINUTE))
            store.markAllOld(n, GROUP)
            val list = listVm(store, GROUP)
            val groupVm = groupVm(store)
            awaitUnread(list, mapOf(GROUP to 1))
            groupVm.markGroupRead(GROUP)
            awaitUnread(list, mapOf(GROUP to 0))

            // Stored after the mark (the mark is now or later; the message is dated beyond it but not in the future).
            val mark = store.markers.value.getValue(GroupUnread.markerKey(GROUP))
            val arrival = mark + 1
            // The clock may still be behind the mark by a few ms: wait until the arrival is not in the future.
            while (System.currentTimeMillis() < arrival) Thread.sleep(1)
            store.messages[GROUP] = rows(row(ALICE, 1, n - MINUTE), row(BOB, 2, arrival))
            ZchatPreferences.markGroupStoreChanged(GROUP)

            awaitUnread(list, mapOf(GROUP to 1))
        }

    @Test
    fun `the list recounts when its group list changes`() =
        runBlocking {
            val n = now()
            val store = Store()
            store.messages[GROUP] = rows(row(ALICE, 1, n - MINUTE))
            store.messages[GROUP_B] = rows(row(BOB, 1, n - MINUTE), row(BOB, 2, n - MINUTE))
            store.markAllOld(n, GROUP, GROUP_B)
            val list = listVm(store, GROUP)
            awaitUnread(list, mapOf(GROUP to 1))

            list.list.value = listState(GROUP, GROUP_B)
            awaitUnread(list, mapOf(GROUP to 1, GROUP_B to 2))

            list.list.value = listState(GROUP_B)
            awaitUnread(list, mapOf(GROUP_B to 2))
        }

    // ----------------------------------------------- review 26 Oct, findings 1, 2, 4 (RED at 833cd3590)

    @Test
    fun `F1 a group with stored messages and no mark starts at zero, then counts what arrives`() =
        runBlocking {
            val n = now()
            val store = Store()
            // The state after an upgrade or a restore: history in the store, no "lastread:group:<id>" entry.
            store.messages[GROUP] = rows(row(ALICE, 1, n - 3 * MINUTE), row(BOB, 1, n - 2 * MINUTE), row(ALICE, 2, n - MINUTE))
            val list = listVm(store, GROUP)

            awaitUnread(list, mapOf(GROUP to 0))

            // Dated after whatever mark the first load set (at most the clock then), and not in the future.
            Thread.sleep(20)
            val arrival = now()
            store.messages[GROUP] =
                rows(
                    row(ALICE, 1, n - 3 * MINUTE),
                    row(BOB, 1, n - 2 * MINUTE),
                    row(ALICE, 2, n - MINUTE),
                    row(BOB, 3, arrival),
                )
            ZchatPreferences.markGroupStoreChanged(GROUP)
            awaitUnread(list, mapOf(GROUP to 1))
        }

    @Test
    fun `F2 a change of only the sync countdown does not read or recount any group`() =
        runBlocking {
            val n = now()
            val store = Store()
            store.messages[GROUP] = rows(row(ALICE, 1, n - MINUTE))
            store.markAllOld(n, GROUP)
            val list = listVm(store, GROUP)
            awaitUnread(list, mapOf(GROUP to 1))
            // Let the first pass and anything it triggers settle.
            Thread.sleep(300)
            val reads = store.storeReads.get()

            repeat(5) { i ->
                val s = list.list.value as ChatListState.Success
                list.list.value = s.copy(secondsUntilNextSync = 30 - i)
                Thread.sleep(100)
            }
            Thread.sleep(300)
            assertEquals("group store reads after five countdown ticks", reads, store.storeReads.get())

            // And a real change (a group's store version) still recounts.
            store.messages[GROUP] = rows(row(ALICE, 1, n - MINUTE), row(BOB, 2, n - MINUTE))
            ZchatPreferences.markGroupStoreChanged(GROUP)
            awaitUnread(list, mapOf(GROUP to 2))
        }

    @Test
    fun `F4 a search query does not let a tap on the Groups row flip the saved expanded flag`() {
        // No Compose test seam exists for the list; the call site that hands the toggle to the list is read from
        // source. While a query is non-empty the folder is shown open (groupsExpanded || searchQuery.isNotBlank()), so
        // the toggle handed down must know about the query, or the tap writes the opposite of what is on screen.
        val source = viewSource("ChatListView.kt")
        val call = source.substringAfter("ConversationsAndGroupsList(\n").substringBefore("onConversationClick = onConversationClick")
        val toggleArg = call.substringAfter("onToggleGroupsFolder =").substringBefore("\n")
        assertEquals(
            "the toggle passed to the list at the call site is: $toggleArg",
            true,
            toggleArg.contains("searchQuery"),
        )
    }

    private fun viewSource(name: String): String {
        val start = java.io.File(System.getProperty("user.dir") ?: ".").absoluteFile
        val file =
            generateSequence(start) { it.parentFile }
                .take(8)
                .flatMap { sequenceOf(it, it.resolve("ui-lib")) }
                .map { it.resolve("src/main/java/co/electriccoin/zcash/ui/screen/chat/view/$name") }
                .firstOrNull { it.isFile }
                ?: throw AssertionError("could not find $name from ${start.path}")
        return file.readText()
    }

    // ----------------------------------------------------------------- fakes

    private class HangingSynchronizerProvider : SynchronizerProvider {
        override val error: StateFlow<SynchronizerError?> = MutableStateFlow(null)
        override val synchronizer: StateFlow<Synchronizer?> = MutableStateFlow(null)

        override suspend fun getSynchronizer(): Synchronizer = awaitCancellation()

        override fun resetSynchronizer() = Unit
    }

    private class OptedOutRates : ExchangeRateRepository {
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

    private class NoContactsBook : ContactBook {
        override fun addContact(contact: Contact) = Unit

        override fun removeContact(address: String) = Unit

        override fun getContact(address: String): Contact? = null

        override fun getAllContacts(): List<Contact> = emptyList()

        override fun hasContact(address: String): Boolean = false

        override fun updateContactName(
            address: String,
            newName: String,
        ) = Unit

        override fun clearAll() = Unit
    }

    private class NoWallet : PersistableWalletProvider {
        override val persistableWallet: Flow<PersistableWallet?> = flowOf(null)

        override suspend fun store(persistableWallet: PersistableWallet) = Unit

        override suspend fun getPersistableWallet(): PersistableWallet? = null

        override suspend fun requirePersistableWallet(): PersistableWallet = error("seed path not used in this test")
    }
}
