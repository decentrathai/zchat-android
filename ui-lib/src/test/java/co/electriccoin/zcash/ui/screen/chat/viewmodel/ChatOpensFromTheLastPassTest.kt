package co.electriccoin.zcash.ui.screen.chat.viewmodel

import cash.z.ecc.android.sdk.model.TransactionId
import cash.z.ecc.android.sdk.model.Zatoshi
import co.electriccoin.zcash.ui.common.datasource.AccountDataSource
import co.electriccoin.zcash.ui.common.model.WalletAccount
import co.electriccoin.zcash.ui.common.usecase.GetDefaultUnifiedAddressUseCase
import co.electriccoin.zcash.ui.common.usecase.Zc101FakeAccountDataSource
import co.electriccoin.zcash.ui.common.usecase.syntheticZashiAccount
import co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferences
import co.electriccoin.zcash.ui.screen.chat.model.CallLogInfo
import co.electriccoin.zcash.ui.screen.chat.model.CallLogType
import co.electriccoin.zcash.ui.screen.chat.model.ChatListState
import co.electriccoin.zcash.ui.screen.chat.model.ChatMessage
import co.electriccoin.zcash.ui.screen.chat.model.Conversation
import co.electriccoin.zcash.ui.screen.chat.model.MessageReaction
import co.electriccoin.zcash.ui.screen.chat.model.MessageStatus
import co.electriccoin.zcash.ui.screen.chat.model.PaymentRequestInfo
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Modifier
import java.lang.reflect.Proxy
import java.time.Instant
import java.util.Collections

/**
 * ZC-946 part 2 (PERF-SPEC revision 2 section 5): every chat open and every Wallet -> Chats builds a new ChatViewModel
 * that starts from Loading and waits a whole history pass (HONOR perf-20261014: 3.7 s warm, 4.0 s cold, 3.7 s back from
 * Wallet). Wanted: the display (`displayChatList()`) shows the last finished pass of this wallet at once, CACHED,
 * through an allowlist mask, while `_chatListState` stays Loading for every money, key and trust reader:
 *
 * - The pass is kept process-wide (ConversationSnapshotCache) with the tag its collection read: account key, our
 *   address, wallet-scope generation. It is written beside `_chatListState.value = state` (`showFinishedPass`) only while
 *   the generation is still the tag's, and shown only while this view model is Loading, the selected account (once it
 *   has emitted) is the tag's account and the generation is unchanged; else Loading.
 * - Kept: per message id, contact, direction, time, plain text and the tick of a mined row; per chat the contact. Read
 *   live: names, drafts, the balance from the selected account. Everything else is masked: no status but "Checking" on
 *   rows not mined, a "Checking" body for locks, requests, files, calls, AI rows and amounts, no reactions, replies, read
 *   marks, E2E or verified marks, no groups, no sync or price; every row is marked so the bubble offers no action.
 * - Live filters: rows hidden or tombstoned since, rows past their disappearing time (a row whose expiry needs the stored
 *   anchor is dropped), system notes, chats left empty. Nothing is written.
 * - The first live pass replaces the display and becomes the snapshot. Early rows (part 1) are never drawn on it.
 *
 * Two view models over the harness; the snapshot writer, the cache and the new fields are reached by name, so this file
 * compiles before they exist. Every address and text is synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatOpensFromTheLastPassTest {
    private val sendScopes = SendHarnessScopes()

    private companion object {
        const val T0 = 1_900_000_000_000L
        val ALICE: String = SyntheticUnifiedAddress.mainnet(9481)
        val BOB: String = SyntheticUnifiedAddress.mainnet(9482)
        val CAROL: String = SyntheticUnifiedAddress.mainnet(9483)
        val ME: String = SyntheticUnifiedAddress.mainnet(9484)
        const val CACHE = "co.electriccoin.zcash.ui.screen.chat.datasource.ConversationSnapshotCache"
        val WRITES = Regex("^(set|put|add|remove|hide|unhide|commit|getOrPut|clear|mark|save|register|delete|record|consume|bump).*")
    }

    @Before
    fun setUp() {
        sendScopes.open()
        Dispatchers.setMain(UnconfinedTestDispatcher())
        resetCache()
    }

    @After
    fun tearDown() {
        resetCache()
        sendScopes.close()
        Dispatchers.resetMain()
    }

    @Test
    fun `a second view model shows the first one's last pass at once, while its own state stays Loading`() =
        runTest {
            val store = Store()
            val a = Vm(this, store)
            a.start()
            a.publish(pass(), a.key)

            val b = Vm(this, store)
            b.start()

            val shown = b.display.last()
            assertTrue("the chat waits for its own pass instead of showing the last one ($shown)", shown is ChatListState.Success)
            shown as ChatListState.Success
            assertEquals("not marked as the last pass", "CACHED", origin(shown))
            assertEquals(setOf(ALICE, BOB), shown.conversations.map { it.peerAddress }.toSet())
            val aliceChat = shown.conversations.single { it.peerAddress == ALICE }
            assertEquals("hello alice", aliceChat.lastMessage?.text)
            assertEquals("money, key and trust readers must still see Loading", ChatListState.Loading, b.live.value)
            b.stop()
            a.stop()
        }

    @Test
    fun `field table - every field of the four states is kept, read live or masked`() =
        runTest {
            val store = Store()
            store.names[ALICE] = "Synthetic Alice"
            store.drafts[ALICE] = "synthetic draft"
            val a = Vm(this, store)
            a.start()
            a.publish(pass(), a.key)
            val b = Vm(this, store)
            b.start()
            val shown = b.display.last() as? ChatListState.Success ?: throw AssertionError("no snapshot shown: ${b.display.last()}")

            // Success: kept / live / masked.
            assertUnlisted(
                ChatListState.Success::class.java,
                setOf(
                    "conversations",
                    "groups",
                    "groupsAwaitingJoin",
                    "currentUserAddress",
                    "balance",
                    "peerSendableBalance",
                    "lastSyncTime",
                    "isRefreshing",
                    "secondsUntilNextSync",
                    "blockHeight",
                    "zecPriceUsd",
                    "privacyStatus",
                    "syncStatus",
                    "renderedPendingIds",
                    "origin",
                ),
            )
            val plainSuccess = ChatListState.Success(conversations = emptyList(), currentUserAddress = ME)
            val maskedSuccess =
                listOf(
                    "groups",
                    "groupsAwaitingJoin",
                    "lastSyncTime",
                    "isRefreshing",
                    "secondsUntilNextSync",
                    "blockHeight",
                    "zecPriceUsd",
                    "privacyStatus",
                    "syncStatus",
                    "renderedPendingIds",
                )
            for (name in maskedSuccess) {
                assertEquals("Success.$name is not masked", read(plainSuccess, name), read(shown, name))
            }
            assertEquals("Success.currentUserAddress is not the tag's", ME, shown.currentUserAddress)
            assertEquals("balance is not the live account's", b.account.displayTotalBalance, shown.balance)
            assertEquals("peer budget is not the live account's", b.account.peerSpendableBalance, shown.peerSendableBalance)

            // Conversation.
            assertUnlisted(
                Conversation::class.java,
                setOf(
                    "peerAddress",
                    "messages",
                    "lastMessage",
                    "unreadCount",
                    "peerStatus",
                    "contactName",
                    "draft",
                    "e2eEnabled",
                    "e2eKeyExchangeComplete",
                    "e2eAckSettled",
                    "e2eKexInFlight",
                    "isMuted",
                    "hasNostrCallChannel",
                    "appLayerE2EPending",
                    "keyNotConfirmed",
                ),
            )
            val alice = shown.conversations.single { it.peerAddress == ALICE }
            val plainChat = Conversation(peerAddress = ALICE, messages = emptyList(), lastMessage = null)
            val maskedChat =
                listOf(
                    "unreadCount",
                    "peerStatus",
                    "e2eEnabled",
                    "e2eKeyExchangeComplete",
                    "e2eAckSettled",
                    "e2eKexInFlight",
                    "isMuted",
                    "hasNostrCallChannel",
                    "appLayerE2EPending",
                    // W-72 (T7aK): the pre-update mark's label, masked with the other E2E marks (CachedSnapshotMask).
                    "keyNotConfirmed",
                )
            for (name in maskedChat) {
                assertEquals("Conversation.$name is not masked", read(plainChat, name), read(alice, name))
            }
            assertEquals("the name is not read live", "Synthetic Alice", alice.contactName)
            assertEquals("the draft is not read live", "synthetic draft", alice.draft)

            // ChatMessage.
            assertUnlisted(
                ChatMessage::class.java,
                setOf(
                    "paymentAmountZatoshi",
                    "waitsForFunds",
                    "id",
                    "txId",
                    "text",
                    "timestamp",
                    "isOutgoing",
                    "peerAddress",
                    "isPending",
                    "status",
                    "unknownReason",
                    "minedHeight",
                    "txIndex",
                    "replyToId",
                    "replyToPreview",
                    "reactions",
                    "isRead",
                    "readAt",
                    "timeLock",
                    "paymentRequest",
                    "fileHash",
                    "fileZfileContent",
                    "fileBlurhash",
                    "fileType",
                    "fileDurationMs",
                    "fileViewOnce",
                    "fileViewed",
                    "isAiMessage",
                    "callLog",
                    "isSystemNote",
                    "authenticated",
                    "preSession",
                    "sendAmountZatoshi",
                    "submitStartedAtMillis",
                    "submitAttemptHeight",
                    "submittedTxIds",
                    "legacyUntrackedSubmit",
                    "failureReason",
                    "supersededTxIds",
                    "notSentProvenAtHeight",
                    "attemptVerdict",
                    "optimistic",
                    "statusChecking",
                    "bodyChecking",
                ),
            )
            val rows = alice.messages.associateBy { it.id }
            val plainRow = row("x", ALICE, "x", 0)
            val special =
                setOf("id", "text", "timestamp", "isOutgoing", "peerAddress", "status", "minedHeight") +
                    setOf("optimistic", "statusChecking", "bodyChecking")
            for (m in rows.values) {
                for (field in fields(ChatMessage::class.java)) {
                    if (field.name in special) continue
                    assertEquals("ChatMessage.${field.name} of ${m.id} is not masked", field.get(plainRow), field.get(m))
                }
                assertTrue("${m.id} offers actions", read(m, "optimistic") as Boolean)
            }
            val mined = rows.getValue("a-mined")
            assertEquals("a mined row lost its tick", MessageStatus.CONFIRMED, mined.status)
            assertEquals(false, read(mined, "statusChecking"))
            assertEquals("hello alice", rows.getValue("a-plain").text)
            assertEquals("a row not mined says more than checking", true, read(rows.getValue("a-plain"), "statusChecking"))
            assertEquals("a row not mined says more than checking", true, read(rows.getValue("a-failed"), "statusChecking"))
            for (id in listOf("a-request", "a-call", "a-file", "a-amount")) {
                assertEquals("$id shows its body", true, read(rows.getValue(id), "bodyChecking"))
                assertEquals("$id shows its text", "", rows.getValue(id).text)
            }
            b.stop()
            a.stop()
        }

    @Test
    fun `hidden, tombstoned and expired rows, system notes and emptied chats are not shown, and nothing is written`() =
        runTest {
            val store = Store()
            val a = Vm(this, store)
            a.start()
            a.publish(pass(), a.key)
            store.hidden += "a-plain"
            store.hidden += "b-only" // the whole chat with Bob (hideChat)
            store.expired += "a-failed"
            store.ttl[ALICE] = ZchatPreferences.DisappearingTtl(3_600L, T0 - 3 * 3_600_000L)
            val b = Vm(this, store)
            b.start()
            val callsBefore = store.calls.size

            val shown = b.display.last() as ChatListState.Success
            val ids = shown.conversations.flatMap { c -> c.messages.map { it.id } }
            assertFalse("a hidden row is shown", "a-plain" in ids)
            assertFalse("a tombstoned row is shown", "a-failed" in ids)
            assertFalse("a system note is shown", "a-note" in ids)
            assertFalse("a row past its disappearing time is shown", "a-old" in ids)
            assertTrue("fixture: a row inside its time stays", "a-mined" in ids)
            assertFalse("a chat left empty is shown", shown.conversations.any { it.peerAddress == BOB })
            val writes = store.calls.drop(callsBefore).filter { WRITES.matches(it) }
            assertEquals("the snapshot wrote to the store", emptyList<String>(), writes)
            b.stop()
            a.stop()
        }

    @Test
    fun `another account, a raised generation or a pass ending after a raise show Loading`() =
        runTest {
            val store = Store()
            val a = Vm(this, store)
            a.start()
            a.publish(pass(), "synthetic-other-account")
            val b = Vm(this, store)
            b.start()
            assertEquals("another account's chats are shown", ChatListState.Loading, b.display.last())
            b.stop()

            a.publish(pass(), a.key)
            store.generation = 1L
            val c = Vm(this, store)
            c.start()
            assertEquals("the chats of a wallet reset since are shown", ChatListState.Loading, c.display.last())
            c.stop()

            resetCache()
            a.publish(pass(), a.key, generation = 0L) // the pass began before the raise to 1
            store.generation = 0L
            val d = Vm(this, store)
            d.start()
            assertEquals("a pass that ended after a raise was kept", ChatListState.Loading, d.display.last())
            d.stop()
            a.stop()
        }

    @Test
    fun `before the account emits nothing is shown, after it the balance is the account's`() =
        runTest {
            val store = Store()
            val a = Vm(this, store)
            a.start()
            a.publish(pass(), a.key)
            val b = Vm(this, store, emitAccount = false)
            b.start()
            assertEquals("chats shown before the account is known", ChatListState.Loading, b.display.last())

            b.emitAccount()
            val shown = b.display.last() as ChatListState.Success
            assertEquals(b.account.displayTotalBalance, shown.balance)
            b.stop()
            a.stop()
        }

    @Test
    fun `the first live pass replaces the display, becomes the snapshot, and early rows never land on a cached one`() =
        runTest {
            val store = Store()
            val a = Vm(this, store)
            a.start()
            a.publish(pass(), a.key)
            val b = Vm(this, store)
            b.start()
            val cached = b.display.last()
            b.pending.update {
                it +
                    row("nostr-out-early", ALICE, "synthetic early", 9)
                        .copy(isOutgoing = true, isPending = true, status = MessageStatus.SENDING)
            }
            runCurrent()
            val texts = (b.display.last() as ChatListState.Success).conversations.flatMap { c -> c.messages.map { it.text } }
            assertFalse("an early row was drawn on the cached pass ($cached)", "synthetic early" in texts)

            val live = ChatListState.Success(conversations = listOf(chat(BOB, row("b-live", BOB, "live bob", 20))), currentUserAddress = ME)
            b.publish(live, b.key)
            assertSame("the live pass does not replace the display", b.live.value, b.display.last())
            val c = Vm(this, store)
            c.start()
            val next = c.display.last() as ChatListState.Success
            assertEquals("the live pass did not become the snapshot", listOf(BOB), next.conversations.map { it.peerAddress })
            c.stop()
            b.stop()
            a.stop()
        }

    @Test
    fun `source - the last finished pass keeps its two writers and only the views read the display`() {
        val vm = source("viewmodel/ChatViewModel.kt")
        assertEquals("_chatListState gained a writer", 2, Regex("""_chatListState\.value\s*=""").findAll(vm).count())
        val readers =
            mainSources()
                .filter { it.name != "ChatViewModel.kt" && it.readText().contains("displayChatList") }
                .map { it.name }
                .toSet()
        assertEquals("the display has a reader outside the screens", setOf("AndroidChat.kt"), readers)
    }

    @Test
    fun `texts - the Checking word is in English and Spanish`() {
        val values = texts("values")
        val es = texts("values-es")
        assertTrue("no English \"Checking…\"", values.contains("<string name=\"chat_cached_checking\">Checking…</string>"))
        assertTrue("no Spanish \"Comprobando…\"", es.contains("<string name=\"chat_cached_checking\">Comprobando…</string>"))
    }

    // ---------------------------------------------------------------- fixture

    private fun row(
        id: String,
        peer: String,
        text: String,
        minute: Long,
    ) = ChatMessage(
        id = id,
        txId = null,
        text = text,
        timestamp = Instant.ofEpochMilli(T0 + minute * 60_000L),
        isOutgoing = false,
        peerAddress = peer,
    )

    private fun chat(
        peer: String,
        vararg rows: ChatMessage,
    ) = Conversation(peerAddress = peer, messages = rows.toList(), lastMessage = rows.maxBy { it.timestamp })

    /** The pass a first view model finished: rich rows in a chat with Alice, one row with Bob. */
    private fun pass(): ChatListState.Success {
        val tx = TransactionId.new("%064x".format(946))
        val alice =
            chat(
                ALICE,
                row("a-old", ALICE, "synthetic old", -90),
                row("a-mined", ALICE, "mined alice", 1).copy(
                    txId = tx,
                    minedHeight = 3_000_100L,
                    txIndex = 2,
                    status = MessageStatus.CONFIRMED,
                    isOutgoing = true,
                    reactions = listOf(MessageReaction("+", ALICE, Instant.ofEpochMilli(T0))),
                    replyToId = "a-old",
                    replyToPreview = "synthetic old",
                    isRead = true,
                    readAt = Instant.ofEpochMilli(T0),
                    authenticated = false,
                    preSession = true,
                ),
                row("a-failed", ALICE, "failed alice", 2).copy(
                    isOutgoing = true,
                    status = MessageStatus.FAILED,
                    failureReason = "synthetic reason",
                    submitStartedAtMillis = T0,
                    submitAttemptHeight = 3_000_000L,
                    submittedTxIds = listOf("synthetic-attempt"),
                    supersededTxIds = listOf("synthetic-older"),
                    notSentProvenAtHeight = 3_000_050L,
                    legacyUntrackedSubmit = true,
                ),
                row("a-request", ALICE, "ZREQ synthetic", 3).copy(paymentRequest = PaymentRequestInfo(5_000L, "synthetic reason")),
                row("a-call", ALICE, "synthetic call", 4).copy(callLog = CallLogInfo(CallLogType.values().first())),
                row("a-file", ALICE, "ZFILE synthetic", 5)
                    .copy(fileHash = "synthetic-hash", fileZfileContent = "ZFILE|synthetic", fileViewOnce = true),
                row("a-amount", ALICE, "synthetic amount", 6)
                    .copy(isOutgoing = true, sendAmountZatoshi = 2_000L, isPending = true, status = MessageStatus.SENDING),
                row("a-note", ALICE, "synthetic note", 7).copy(isSystemNote = true),
                row("a-plain", ALICE, "hello alice", 8),
            ).copy(
                unreadCount = 3,
                e2eEnabled = true,
                e2eKeyExchangeComplete = true,
                isMuted = true,
                hasNostrCallChannel = true,
                appLayerE2EPending = true,
                keyNotConfirmed = true,
            )
        val bob = chat(BOB, row("b-only", BOB, "hello bob", 1))
        return ChatListState.Success(
            conversations = listOf(alice, bob),
            currentUserAddress = ME,
            balance = Zatoshi(123_456L),
            peerSendableBalance = Zatoshi(100_000L),
            isRefreshing = true,
            secondsUntilNextSync = 7,
            blockHeight = 3_000_200L,
            zecPriceUsd = 42.0,
            groupsAwaitingJoin = setOf("synthetic-group"),
        )
    }

    /** The store both view models share: what the snapshot may read live. */
    private class Store {
        val hidden: MutableSet<String> = Collections.synchronizedSet(mutableSetOf())
        val expired: MutableSet<String> = Collections.synchronizedSet(mutableSetOf())
        val ttl = mutableMapOf<String, ZchatPreferences.DisappearingTtl>()
        val names = mutableMapOf<String, String>()
        val drafts = mutableMapOf<String, String>()
        val calls: MutableList<String> = Collections.synchronizedList(mutableListOf())

        @Volatile var generation = 0L

        val prefs =
            ScanPrefs(
                mapOf(
                    "getHiddenMessageIds" to { _ -> HashSet(hidden) },
                    "getExpiredMessageIds" to { _ -> HashSet(expired) },
                    "getDisappearingTtl" to { a -> ttl[a[0]] },
                    "getDisappearingTtls" to { _ -> MutableStateFlow(ttl.toMap()) },
                    "getNickname" to { a -> names[a[0]] },
                    "getAllDrafts" to { _ -> drafts.toMap() },
                    "getDraft" to { a -> drafts[a[0]] },
                    "walletScopeGeneration" to { _ -> generation },
                ),
            )
    }

    private inner class Vm(
        private val test: TestScope,
        private val store: Store,
        emitAccount: Boolean = true,
    ) {
        val display: MutableList<ChatListState> = Collections.synchronizedList(mutableListOf())
        val live = MutableStateFlow<ChatListState>(ChatListState.Loading)
        val pending = MutableStateFlow(emptyList<ChatMessage>())
        lateinit var account: WalletAccount
        lateinit var key: String
        private val accountFlow = MutableStateFlow<WalletAccount?>(null)
        private val emitAtStart = emitAccount
        private val jobs = mutableListOf<Job>()
        private lateinit var vm: ChatViewModel

        suspend fun start() {
            val zashi = syntheticZashiAccount()
            account = zashi
            key = GetDefaultUnifiedAddressUseCase.accountKeyOf(account)
            if (emitAtStart) accountFlow.value = account
            vm = PaymentHarness.paymentViewModel(PaymentPrefs(ALICE), PaymentSeam(ALICE))
            val logging =
                ScanPassHarness.prefsWith(store.prefs.proxy, emptyMap()).let { base ->
                    Proxy.newProxyInstance(ZchatPreferences::class.java.classLoader, arrayOf(ZchatPreferences::class.java)) {
                        _,
                        method,
                        args,
                        ->
                        store.calls += method.name
                        try {
                            method.invoke(base, *(args ?: emptyArray()))
                        } catch (e: InvocationTargetException) {
                            throw e.targetException
                        }
                    } as ZchatPreferences
                }
            PaymentHarness.setField(vm, "zchatPreferences", logging)
            PaymentHarness.setField(vm, "contactBook", ScanPassHarness.noContacts())
            PaymentHarness.setField(vm, "accountDataSource", accounts(Zc101FakeAccountDataSource(zashi)))
            PaymentHarness.setField(vm, "hiddenMessages", MutableStateFlow<Set<String>>(HashSet(store.hidden)))
            PaymentHarness.setField(vm, "pendingMessages", pending)
            PaymentHarness.setField(vm, "_chatListState", live)
            PaymentHarness.setField(vm, "ttlClock", { T0 + 10 * 60_000L })
            PaymentHarness.setField(
                vm,
                "renderDispatcher",
                test.coroutineContext[kotlin.coroutines.ContinuationInterceptor] as CoroutineDispatcher,
            )
            jobs += test.backgroundScope.launch { vm.displayChatList().collect { display += it } }
            test.runCurrent()
        }

        fun emitAccount() {
            accountFlow.value = account
            test.runCurrent()
        }

        /** What loadConversations does with a finished pass of the collection tagged [accountKey], [generation]. */
        fun publish(
            state: ChatListState.Success,
            accountKey: String,
            generation: Long = store.generation,
        ) {
            val method =
                ChatViewModel::class.java.declaredMethods.firstOrNull { it.name == "showFinishedPass" }
                    ?: throw AssertionError(
                        "ChatViewModel has no showFinishedPass: no finished pass is kept for the next screen to show at once",
                    )
            method.isAccessible = true
            try {
                method.invoke(vm, state, accountKey, ME, generation)
            } catch (e: InvocationTargetException) {
                throw e.targetException
            }
            test.runCurrent()
        }

        private fun accounts(base: AccountDataSource): AccountDataSource =
            Proxy.newProxyInstance(AccountDataSource::class.java.classLoader, arrayOf(AccountDataSource::class.java)) {
                _,
                method,
                args,
                ->
                if (method.name == "getSelectedAccount" && args.isNullOrEmpty()) {
                    accountFlow
                } else {
                    try {
                        method.invoke(base, *(args ?: emptyArray()))
                    } catch (e: InvocationTargetException) {
                        throw e.targetException
                    }
                }
            } as AccountDataSource

        fun stop() {
            jobs.forEach { it.cancel() }
        }
    }

    private fun resetCache() {
        runCatching { Class.forName(CACHE) }.getOrNull()?.let { cls ->
            val instance = cls.getField("INSTANCE").get(null)
            cls.getMethod("resetForTest").invoke(instance)
        }
    }

    private fun origin(s: ChatListState.Success): String =
        runCatching { read(s, "origin").toString() }.getOrElse { throw AssertionError("ChatListState.Success has no origin") }

    private fun fields(cls: Class<*>) =
        cls.declaredFields
            .filter { !Modifier.isStatic(it.modifiers) && !it.isSynthetic }
            .onEach { it.isAccessible = true }

    private fun read(
        obj: Any,
        name: String,
    ): Any? {
        val field =
            fields(obj.javaClass).firstOrNull { it.name == name }
                ?: throw AssertionError("${obj.javaClass.simpleName} has no $name")
        return field.get(obj)
    }

    private fun assertUnlisted(
        cls: Class<*>,
        listed: Set<String>,
    ) {
        val names = fields(cls).map { it.name }.toSet()
        assertEquals("${cls.simpleName} has fields the mask does not classify (or lacks listed ones)", listed, names)
    }

    private fun repoFile(path: String): File {
        val start = File(System.getProperty("user.dir") ?: ".").absoluteFile
        return generateSequence(start) { it.parentFile }
            .take(8)
            .flatMap { sequenceOf(it.resolve(path), it.resolve("ui-lib").resolve(path)) }
            .firstOrNull { it.exists() }
            ?: throw AssertionError("could not find $path from ${start.path}")
    }

    private fun source(name: String): String = repoFile("src/main/java/co/electriccoin/zcash/ui/screen/chat/$name").readText()

    private fun mainSources(): List<File> =
        repoFile("src/main/java")
            .walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .toList()

    private fun resDir(): File = repoFile("src/main/res/ui/common")

    private fun texts(folder: String): String =
        resDir()
            .resolve(folder)
            .listFiles()
            .orEmpty()
            .joinToString { it.readText() }
}
