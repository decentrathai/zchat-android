package co.electriccoin.zcash.ui.screen.chat.viewmodel

import androidx.lifecycle.viewModelScope
import cash.z.ecc.android.sdk.model.Proposal
import cash.z.ecc.android.sdk.model.UnifiedSpendingKey
import co.electriccoin.zcash.ui.common.datasource.AccountDataSource
import co.electriccoin.zcash.ui.common.datasource.OnTransactionsStored
import co.electriccoin.zcash.ui.common.datasource.ProposalDataSource
import co.electriccoin.zcash.ui.common.datasource.StoredTransaction
import co.electriccoin.zcash.ui.common.datasource.Zip321TransactionProposal
import co.electriccoin.zcash.ui.common.model.SubmitResult
import co.electriccoin.zcash.ui.common.model.WalletAccount
import co.electriccoin.zcash.ui.common.model.ZashiAccount
import co.electriccoin.zcash.ui.common.repository.ZashiProposalRepositoryImpl
import co.electriccoin.zcash.ui.common.sendattempt.SendAttemptProcess
import co.electriccoin.zcash.ui.common.usecase.Zc101FakeAccountDataSource
import co.electriccoin.zcash.ui.common.usecase.Zc101FakeKeystoneProposalRepository
import co.electriccoin.zcash.ui.common.usecase.Zc101FakeZashiSpendingKeyDataSource
import co.electriccoin.zcash.ui.common.usecase.Zc101RecordingNavigationRouter
import co.electriccoin.zcash.ui.common.usecase.Zc101RecordingProposalDataSource
import co.electriccoin.zcash.ui.common.usecase.syntheticZashiAccount
import co.electriccoin.zcash.ui.common.usecase.syntheticZip321Proposal
import co.electriccoin.zcash.ui.common.util.FaultSeam
import co.electriccoin.zcash.ui.common.util.FaultSeams
import co.electriccoin.zcash.ui.nostr.NostrChatBridge
import co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferences
import co.electriccoin.zcash.ui.screen.chat.model.ChatListState
import co.electriccoin.zcash.ui.screen.chat.model.ChatMessage
import co.electriccoin.zcash.ui.screen.chat.model.GroupMember
import co.electriccoin.zcash.ui.screen.chat.model.GroupSettingsState
import co.electriccoin.zcash.ui.screen.chat.model.MemberStatus
import co.electriccoin.zcash.ui.screen.chat.usecase.CreateChunkedMessageProposalUseCase
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.job
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Modifier
import java.lang.reflect.Proxy
import java.util.Base64
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * F8-G (fix-all 2.13.0, train T6b; ZC-112 part 2, ZC-055, ZC-082, ZC-083, ZC-619): the fixture of the group side of
 * the send-attempt ledger tests.
 *
 * One phone: the creator [MA3.C] of the group [MA3.GID], over the MA3 store ([AnnouncerPhone]) with a recording
 * overlay ([GroupLedgerStore]). A GroupViewModel ([GroupLedgerWorld.screen], the sender and the Group Settings state)
 * and a ChatViewModel ([GroupLedgerWorld.chatVm], whose history pass judges the ledger) share that ONE store and ONE
 * ledger instance ([LedgerDouble], F8's in-memory twin behind its recording proxy), one wallet seam
 * ([GroupLedgerSeam]) and one synthetic wallet for the pass ([SendAttemptHarness.Wallet]).
 *
 * What F8-G adds to GroupViewModel is reached BY NAME (the field `sendAttempts`, the two Group Settings maps, the two
 * Resend taps, the process-wide Resend set), so this file compiles on a build without them: there nothing is installed
 * and each test fails on the behaviour it asks for (an entry, a badge, "nothing is paid twice").
 *
 * Every address, key, group id and transaction id is synthetic. Nothing here pays.
 */
internal class GroupLedgerSeam(
    private val recording: Zc101RecordingProposalDataSource = Zc101RecordingProposalDataSource(),
) : ProposalDataSource by recording {
    /** One stored transaction: its id, who it pays and the memo it carries. */
    class Stored(
        val number: Int,
        val id: String,
        val memo: PaidMemo,
    )

    /** What the n-th submit (from 1) answers with the ids it stored; default: the network took it. */
    @Volatile var outcome: (Int, List<String>) -> SubmitResult = { _, ids -> SubmitResult.Success(ids) }

    /** The expiry height each stored transaction carries. */
    @Volatile var expiry: Long = SendAttemptHarness.H + IdsHandshakeSeam.EXPIRY_AHEAD

    /** Runs at every proposal request (from 1), before it is answered; it may throw (a refusal before anything is stored). */
    @Volatile var onProposal: ((n: Int, memo: PaidMemo) -> Unit)? = null

    /** While set, every submit waits for it after the ids were handed over. */
    @Volatile var holdSubmits: CompletableDeferred<Unit>? = null

    val proposals: MutableList<PaidMemo> = Collections.synchronizedList(mutableListOf())
    val stored: MutableList<Stored> = Collections.synchronizedList(mutableListOf())
    val submits = AtomicInteger(0)
    private val proposalCount = AtomicInteger(0)
    private val nextId = AtomicInteger(FIRST_ID)

    @Volatile private var last: PaidMemo? = null

    override suspend fun createZip321Proposal(
        account: WalletAccount,
        zip321Uri: String,
        allowMultiPayment: Boolean,
    ): Zip321TransactionProposal {
        val paid = decode(zip321Uri)
        proposals += paid
        onProposal?.invoke(proposalCount.incrementAndGet(), paid)
        last = paid
        return syntheticZip321Proposal(paid.recipient, amount = 1_000L, memo = "synthetic-group-ledger-memo")
    }

    private fun store(): Stored {
        val number = nextId.getAndIncrement()
        return Stored(number, SendAttemptHarness.txId(number), last ?: PaidMemo("", "")).also { stored += it }
    }

    override suspend fun submitTransaction(
        proposal: Proposal,
        usk: UnifiedSpendingKey,
        onStored: OnTransactionsStored,
    ): SubmitResult {
        val tx = store()
        try {
            onStored(listOf(StoredTransaction(tx.id, expiry)))
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            return SubmitResult.SentUnconfirmed(listOf(tx.id), "the stored ids were not recorded")
        }
        val n = submits.incrementAndGet()
        holdSubmits?.await()
        return outcome(n, listOf(tx.id))
    }

    /** A build that records no ids calls this overload: one submit, the same scripted answer. */
    override suspend fun submitTransaction(
        proposal: Proposal,
        usk: UnifiedSpendingKey,
    ): SubmitResult {
        val tx = store()
        val n = submits.incrementAndGet()
        holdSubmits?.await()
        return outcome(n, listOf(tx.id))
    }

    /** The stored transactions that pay [recipient]. */
    fun to(recipient: String): List<Stored> = synchronized(stored) { stored.filter { it.memo.recipient == recipient } }

    private fun decode(uri: String): PaidMemo {
        val recipient = uri.removePrefix("zcash:").substringBefore('?')
        val encoded =
            uri
                .substringAfter('?')
                .split('&')
                .first { it.startsWith("memo=") }
                .removePrefix("memo=")
        return PaidMemo(recipient, String(Base64.getUrlDecoder().decode(encoded), Charsets.UTF_8))
    }

    companion object {
        const val FIRST_ID = 8_001
    }
}

/**
 * The store of the phone: [base] (the MA3 store) with what the history pass and the ledger tests also need. Every roster
 * write is recorded with whether the group's lock was held; a durable roster write can be refused; the group message
 * sequence counts up.
 */
internal class GroupLedgerStore(
    private val base: ZchatPreferences,
) {
    /** While true a durable (checked) roster write does not reach the disk. */
    @Volatile var refuseDurableRoster = false

    /** The roster writes so far (method names, in order). */
    val rosterWrites: MutableList<String> = Collections.synchronizedList(mutableListOf())

    /** The order of ledger writes ("ledger:<method>") and roster / announce writes ("prefs:<method>"), when set. */
    @Volatile var timeline: MutableList<String>? = null

    /** Roster writes made without the group's lock held by the writing thread. */
    val unlockedRosterWrites = AtomicInteger(0)
    private val sequences = ConcurrentHashMap<String, Long>()

    val proxy: ZchatPreferences =
        Proxy.newProxyInstance(
            ZchatPreferences::class.java.classLoader,
            arrayOf(ZchatPreferences::class.java),
        ) { p, method, args ->
            when (method.name) {
                "toString" -> return@newProxyInstance "GroupLedgerStore"
                "hashCode" -> return@newProxyInstance System.identityHashCode(p)
                "equals" -> return@newProxyInstance (p === args?.get(0))
                "walletScopeGeneration" -> return@newProxyInstance 0L
                "getOrPutPendingTxFirstSeenMillis" -> return@newProxyInstance args!![1]
                "getGroupMessageSequence" -> return@newProxyInstance (sequences[args!![0] as String] ?: 0L)
                "incrementGroupMessageSequence" ->
                    return@newProxyInstance sequences.merge(args!![0] as String, 1L) { a, b -> a + b }
                "saveGroupMembers", "saveGroupMembersDurably" -> {
                    rosterWrites += method.name
                    timeline?.add("prefs:" + method.name)
                    if (!Thread.holdsLock(ZchatPreferences.groupLock(args!![0] as String))) unlockedRosterWrites.incrementAndGet()
                    if (method.name == "saveGroupMembersDurably" && refuseDurableRoster) return@newProxyInstance false
                }
                "casGroupAnnounce" -> timeline?.add("prefs:" + method.name)
            }
            try {
                method.invoke(base, *(args ?: emptyArray()))
            } catch (e: InvocationTargetException) {
                throw e.targetException
            }
        } as ZchatPreferences
}

/** One group screen: its ViewModel and the fixture whose `awaitNewJobs` drives it. */
internal class GroupScreen(
    val vm: GroupViewModel,
    val f: GroupPaidOnceFixture,
)

internal class GroupLedgerWorld(
    members: List<Pair<String, MemberStatus>> =
        listOf(MA3.A to MemberStatus.ACTIVE, MA3.B to MemberStatus.ACTIVE, MA3.D to MemberStatus.ACTIVE),
    /** False: no member has a key exchange with the creator (no key message can be built for them). */
    sessions: Boolean = true,
    private val scheduler: TestCoroutineScheduler,
) {
    val admin: AnnouncerPhone = MA3.adminPhone(members)
    val ledger = LedgerDouble()
    val seam = GroupLedgerSeam()
    val wallet = SendAttemptHarness.Wallet(scanned = SendAttemptHarness.H)
    val store = GroupLedgerStore(admin.proxy)
    val accounts: AccountDataSource = Zc101FakeAccountDataSource(kotlinx.coroutines.runBlocking { syntheticZashiAccount() })

    /** The order of every ledger write and every roster / announce write of this phone. */
    val timeline: MutableList<String> =
        Collections.synchronizedList(mutableListOf<String>()).also {
            ledger.timeline = it
            store.timeline = it
        }

    init {
        if (sessions) members.forEach { (address, _) -> admin.base.link(address) }
    }

    fun accountHash(): String =
        SendAttemptProcess.accountHash(
            accounts.allAccounts.value!!
                .firstNotNullOf { it as? ZashiAccount }
                .sdkAccount.accountUuid.value,
        )

    private fun environment(): Any? {
        val environmentInterface = runCatching { Class.forName(LedgerNames.ENVIRONMENT) }.getOrNull() ?: return null
        val ledgerProxy = ledger.proxy ?: return null
        return Proxy.newProxyInstance(environmentInterface.classLoader, arrayOf(environmentInterface)) { p, method, args ->
            when (method.name) {
                "getLedger" -> ledgerProxy
                "accountHash" -> accountHash()
                "toString" -> "GroupLedgerEnvironment"
                "hashCode" -> System.identityHashCode(p)
                "equals" -> p === args?.get(0)
                else -> throw UnsupportedOperationException("unexpected SendAttemptEnvironment.${method.name}")
            }
        }
    }

    private fun useCase(source: AccountDataSource): CreateChunkedMessageProposalUseCase {
        val owner = SendHarnessScopes.current()
        return CreateChunkedMessageProposalUseCase(
            keystoneProposalRepository = Zc101FakeKeystoneProposalRepository(),
            zashiProposalRepository =
                ZashiProposalRepositoryImpl(
                    accountDataSource = source,
                    proposalDataSource = seam,
                    zashiSpendingKeyDataSource = Zc101FakeZashiSpendingKeyDataSource(),
                ).also(owner::own),
            accountDataSource = source,
            navigationRouter = Zc101RecordingNavigationRouter(),
            biometricRepository = HandshakeHarness.ApprovingBiometricRepository,
        )
    }

    /**
     * A group screen of the creator over this phone's store, wallet and ledger. A second call is a second screen (or
     * the process after [restart]). [withLedger] false: the ViewModel of every test written before the ledger.
     */
    suspend fun screen(
        withLedger: Boolean = true,
        /** The fixture the ViewModel is made by (a test that selects another account passes its own). */
        fixture: GroupPaidOnceFixture = GroupPaidOnceFixture(),
        /** The phone's accounts; null keeps the ones [fixture] gave the ViewModel. */
        accountSource: AccountDataSource? = accounts,
    ): GroupScreen {
        val f = fixture.also { it.scheduler = scheduler }
        val vm = f.groupViewModel()

        fun field(name: String) = GroupViewModel::class.java.getDeclaredField(name).apply { isAccessible = true }
        field("zchatPreferences").set(vm, store.proxy)
        if (accountSource != null) field("accountDataSource").set(vm, accountSource)
        @Suppress("UNCHECKED_CAST")
        (field("_currentUserAddress").get(vm) as MutableStateFlow<String?>).value = admin.self
        field("createChunkedMessageProposal").set(vm, useCase(field("accountDataSource").get(vm) as AccountDataSource))
        if (withLedger) {
            GroupViewModel::class.java.declaredFields.firstOrNull { it.name == "sendAttempts" }?.let { ledgerField ->
                environment()?.let { env ->
                    ledgerField.isAccessible = true
                    ledgerField.set(vm, env)
                }
            }
        }
        vm.consumeGroupSendEvent()
        return GroupScreen(vm, f)
    }

    /** The chat list's ChatViewModel over the same store, wallet and ledger: its history pass judges the ledger. */
    suspend fun chatVm(): ChatViewModel {
        val vm = HandshakeHarness.chatViewModelWith(store.proxy, HandshakeSeam(admin.self), accountSource = accounts)
        AutoSpendVm.wireBootstrapFields(vm)
        AutoSpendVm.setField(vm, "_currentUserAddress", MutableStateFlow<String?>(admin.self))
        PaymentHarness.setField(vm, "addressCache", NoOpAddressCache.proxy)
        PaymentHarness.setField(vm, "_chatListState", MutableStateFlow<ChatListState>(ChatListState.Loading))
        PaymentHarness.setField(vm, "_showCostDisclaimer", MutableStateFlow(false))
        PaymentHarness.setField(vm, "_blockHeight", MutableStateFlow<Long?>(null))
        PaymentHarness.setField(vm, "pendingMessages", MutableStateFlow<List<ChatMessage>>(emptyList()))
        PaymentHarness.setField(vm, "paidRequestIds", MutableStateFlow(emptySet<String>()))
        PaymentHarness.setField(vm, "unsavedPaidMarks", ConcurrentHashMap.newKeySet<String>())
        PaymentHarness.setField(vm, "messageQueue", mutableListOf<Any>())
        PaymentHarness.setField(vm, "messageProcessors", ConcurrentHashMap<String, Any>())
        PaymentHarness.setField(vm, "replyRefSentinel", '\u0001')
        PaymentHarness.setField(vm, "replyRefPrefix", "\u0001RPL:")
        PaymentHarness.setField(vm, "replyPreviewSep", '\u001f')
        SendAttemptHarness.install(vm, PaymentPrefs(admin.self), wallet)
        PaymentHarness.setField(vm, "zchatPreferences", store.proxy)
        PaymentHarness.setField(vm, "contactBook", EmptyContacts)
        ChatViewModel::class.java.declaredFields.firstOrNull { it.name == "sendAttempts" }?.let { ledgerField ->
            environment()?.let { env ->
                ledgerField.isAccessible = true
                ledgerField.set(vm, env)
            }
        }
        val notice = MutableStateFlow<Map<String, Any>>(emptyMap())
        for (name in listOf("_handshakeNotSent", "handshakeNotSent")) {
            ChatViewModel::class.java.declaredFields.firstOrNull { it.name == name }?.let {
                it.isAccessible = true
                it.set(vm, notice)
            }
        }
        return vm
    }

    // ── the wallet the pass reads ─────────────────────────────────────────────────────────────────────

    /** Transactions a test listed by hand (the ids of seeded entries). */
    private val seededTxs: MutableList<SendAttemptHarness.ChainTx> = Collections.synchronizedList(mutableListOf())

    /**
     * The wallet lists every stored transaction; [mined] gives the mined height of the n-th (from 1), null = unmined;
     * [expiry] its expiry height (default: the seam's).
     */
    fun walletLists(
        expiry: (n: Int) -> Long = { seam.expiry },
        mined: (n: Int) -> Long? = { null },
    ) {
        wallet.txs =
            synchronized(seam.stored) {
                seam.stored.mapIndexed { i, tx ->
                    SendAttemptHarness.ChainTx(
                        number = tx.number,
                        recipient = tx.memo.recipient,
                        memo = null,
                        minedHeight = mined(i + 1),
                        expiryHeight = expiry(i + 1),
                        amount = 1_000L,
                    )
                }
            } + seededTxs
    }

    /** The wallet also lists the transaction of a seeded entry ([entry]'s first id), paid to [recipient]. */
    fun walletListsSeeded(
        entry: EntryView,
        recipient: String,
        mined: Long? = null,
    ) {
        val id = entry.txIds.first()
        val number = (SEED_FIRST until SEED_ID.get()).first { SendAttemptHarness.txId(it) == id }
        seededTxs.removeAll { it.number == number }
        seededTxs += SendAttemptHarness.ChainTx(number, recipient, null, mined, seam.expiry, 1_000L)
        walletLists()
    }

    /** One complete history pass of [chat] with the wallet scanned to [tip]. */
    suspend fun pass(
        chat: ChatViewModel,
        tip: Long,
    ) {
        wallet.scanned = tip
        wallet.network = tip
        SendAttemptHarness.runPass(chat, wallet)
    }

    /** Two complete passes at strictly rising tips, both past the stored transactions' expiry plus the reorg margin. */
    suspend fun twoPassesPastExpiry(chat: ChatViewModel) {
        val from = maxOf(seam.expiry + MARGIN, wallet.scanned + 1)
        pass(chat, from)
        pass(chat, from + 1)
    }

    /** A stored transaction the network never confirms: the submit answers a transport failure with its ids. */
    fun storedNeverMined() {
        seam.outcome = { _, ids -> SubmitResult.GrpcFailure(ids) }
    }

    // ── the screens ───────────────────────────────────────────────────────────────────────────────────

    suspend fun run(
        s: GroupScreen,
        block: suspend () -> Unit,
    ) = s.f.awaitNewJobs(s.vm, block)

    /** The paid question on [s], by the name of its action; null when none is open. */
    fun question(s: GroupScreen): String? =
        s.vm.paidActionConfirmation.value
            ?.action
            ?.name

    /** How many on-chain messages the paid question on [s] counts; null when none is open. */
    fun questionCount(s: GroupScreen): Int? =
        s.vm.paidActionConfirmation.value
            ?.onChainMessages

    suspend fun confirm(s: GroupScreen) = run(s) { s.vm.confirmPaidAction() }

    /** Group Settings of [s], loaded now. */
    suspend fun settings(s: GroupScreen): GroupSettingsState.Success {
        run(s) { s.vm.loadGroupSettings(MA3.GID) }
        return s.vm.groupSettingsState.value as GroupSettingsState.Success
    }

    /** The member rows that show a badge ([map] is `keyNotDelivered` or `messageNotDelivered`): row address to its offer. */
    @Suppress("UNCHECKED_CAST")
    fun badges(
        state: GroupSettingsState.Success,
        map: String,
    ): Map<String, Any> =
        runCatching {
            state.javaClass.getMethod("get" + map.replaceFirstChar { it.uppercase() }).invoke(state) as Map<String, Any>
        }.getOrDefault(emptyMap())

    suspend fun keyBadges(s: GroupScreen): Map<String, Any> = badges(settings(s), "keyNotDelivered")

    suspend fun messageBadges(s: GroupScreen): Map<String, Any> = badges(settings(s), "messageNotDelivered")

    fun offerStartedAt(offer: Any): Long = offer.javaClass.getMethod("getStartedAt").invoke(offer) as Long

    fun offerKey(offer: Any): String = offer.javaClass.getMethod("getKey").invoke(offer) as String

    /**
     * Taps Resend on [member]'s badge ([tap] is `resendKeyToMember` or `resendMessageCopy`) with the offer the badge
     * was drawn from. False: this build has no such tap.
     */
    suspend fun tapResend(
        s: GroupScreen,
        tap: String,
        member: String,
        offer: Any,
    ): Boolean {
        val methods = GroupViewModel::class.java.methods
        val method = methods.firstOrNull { it.name == tap && it.parameterCount == RESEND_PARAMETERS } ?: return false
        run(s) {
            try {
                method.invoke(s.vm, MA3.GID, member, offer)
            } catch (e: InvocationTargetException) {
                throw e.targetException
            }
        }
        return true
    }

    // ── the phone's state ─────────────────────────────────────────────────────────────────────────────

    fun roster(): List<GroupMember> = admin.base.roster(MA3.GID)

    fun row(member: String): GroupMember = roster().single { it.address == member }

    fun setRow(
        member: String,
        change: (GroupMember) -> GroupMember,
    ) {
        admin.base.rosters[MA3.GID] =
            co.electriccoin.zcash.ui.screen.chat.model.ZMSGGroupProtocol
                .serializeGroupMembers(roster().map { if (it.address == member) change(it) else it })
    }

    fun epoch(): Int = admin.base.epochs[MA3.GID] ?: 0

    /** The stored group keys of this phone, by "<group>|<key number>". */
    val keys: MutableMap<String, String> get() = admin.base.keys

    /** The stored group-message rows of this phone, by group. */
    val messages: MutableMap<String, String> get() = admin.base.messages

    fun hasKey(epoch: Int): Boolean = keys.containsKey(MA3.GID + "|" + epoch)

    /** The member the first stored transaction pays. */
    fun firstPaid(): String {
        val first = seam.stored.first()
        return first.memo.recipient
    }

    /** A LIVE entry of this phone's account, as a sender writes it; then [LedgerDouble.seedNotSent] or `seedMined`. */
    fun seed(
        kind: String,
        subject: String,
        identity: String,
        txIds: List<String> = listOf(SendAttemptHarness.txId(SEED_ID.getAndIncrement())),
        payload: String? = null,
    ): EntryView =
        ledger.seedLive(
            kind,
            subject,
            identity,
            startedAt = MARKER.getAndIncrement(),
            attemptHeight = SendAttemptHarness.H,
            accountHash = accountHash(),
            txIds = txIds,
            expiries = txIds.associateWith { seam.expiry },
            payload = payload,
        )

    fun memberSubject(member: String): String = MA3.GID + "|" + member

    /** The process ended and started again: nothing is in flight, the proof is empty, the in-memory guards are gone. */
    fun restart() = GroupLedgerTests.resetProcess()

    companion object {
        /** The creator with two ACTIVE members, [MA3.A] and [MA3.B]. */
        fun twoActive(scheduler: TestCoroutineScheduler) =
            GroupLedgerWorld(listOf(MA3.A to MemberStatus.ACTIVE, MA3.B to MemberStatus.ACTIVE), scheduler = scheduler)

        /** The creator with [MA3.A] ACTIVE and [MA3.B] INVITED (its invite shown as sent). */
        fun activeAndInvited(scheduler: TestCoroutineScheduler) =
            GroupLedgerWorld(listOf(MA3.A to MemberStatus.ACTIVE, MA3.B to MemberStatus.INVITED), scheduler = scheduler)

        const val H = SendAttemptHarness.H
        const val MARGIN = 10L
        const val RESEND_PARAMETERS = 3
        private val MARKER = AtomicLong(1_700_000_000_000L)
        private const val SEED_FIRST = 9_001
        private val SEED_ID = AtomicInteger(SEED_FIRST)
    }
}

/** The set-up and tear-down the group ledger tests share, and the process-wide state they reset. */
@OptIn(ExperimentalCoroutinesApi::class)
internal object GroupLedgerTests {
    fun setUp(
        scopes: SendHarnessScopes,
        scheduler: TestCoroutineScheduler,
    ) {
        scopes.open()
        Dispatchers.setMain(UnconfinedTestDispatcher(scheduler))
        resetProcess()
        AutoSpendVm.setHistoryRead(true)
    }

    fun tearDown(scopes: SendHarnessScopes) {
        // The announcer's passes that wait for the relays are stopped while the test's Main dispatcher is still set (a
        // cancel after it is reset has no dispatcher to resume on, and the next test would be handed that error).
        NostrChatBridge.unregisterPublisher()
        MA3.resetRuntime()
        scopes.close()
        Dispatchers.resetMain()
        resetProcess()
    }

    /** Clears every process-wide guard a test may have left, as a fresh process starts. */
    fun resetProcess() {
        LedgerProcess.reset()
        NostrChatBridge.unregisterPublisher()
        MA3.resetRuntime()
        FaultSeams.disarm(FaultSeam.GROUP_SEND_DELAY_MS)
        val companion =
            runCatching { GroupViewModel::class.java.getDeclaredField("Companion").get(null) }.getOrNull()
        for (name in listOf("groupResendsInFlight", "keyRetriesInFlight", "rotationLocks", "undeliveredOwnMessages")) {
            val field =
                GroupViewModel::class.java.declaredFields
                    .firstOrNull { it.name == name && Modifier.isStatic(it.modifiers) } ?: continue
            field.isAccessible = true
            (field.get(companion) as? MutableCollection<*>)?.clear()
            (field.get(companion) as? MutableMap<*, *>)?.clear()
        }
    }

    /** The ledger keys whose group Resend is running in this process (null on a build with no such set). */
    @Suppress("UNCHECKED_CAST")
    fun resendsInFlight(): MutableSet<String>? {
        val field =
            GroupViewModel::class.java.declaredFields
                .firstOrNull { it.name == "groupResendsInFlight" && Modifier.isStatic(it.modifiers) } ?: return null
        field.isAccessible = true
        return field.get(null) as MutableSet<String>
    }

    /**
     * Runs [block] and drives the jobs it starts on [s] to the end; while they run, [whileRunning] is called between
     * small steps of virtual time (a test mines a block, stores a leave, ends a hold there).
     */
    suspend fun drive(
        s: GroupScreen,
        scheduler: TestCoroutineScheduler,
        whileRunning: () -> Unit,
        block: suspend () -> Unit,
    ) {
        val before: Set<Job> =
            s.vm.viewModelScope.coroutineContext.job.children
                .toSet()
        block()
        withTimeout(HandshakeHarness.TIMEOUT_MS) {
            while (s.vm.viewModelScope.coroutineContext.job.children
                    .any { it !in before && it.isActive }
            ) {
                whileRunning()
                scheduler.advanceTimeBy(STEP_MS)
                scheduler.runCurrent()
                delay(5)
            }
        }
    }

    const val STEP_MS = 100L
}
