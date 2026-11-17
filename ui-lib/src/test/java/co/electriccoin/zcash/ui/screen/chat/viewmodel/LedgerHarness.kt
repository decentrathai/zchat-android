package co.electriccoin.zcash.ui.screen.chat.viewmodel

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
import co.electriccoin.zcash.ui.common.sendattempt.SendAttemptProof
import co.electriccoin.zcash.ui.common.usecase.Zc101FakeAccountDataSource
import co.electriccoin.zcash.ui.common.usecase.Zc101FakeKeystoneProposalRepository
import co.electriccoin.zcash.ui.common.usecase.Zc101FakeZashiSpendingKeyDataSource
import co.electriccoin.zcash.ui.common.usecase.Zc101RecordingNavigationRouter
import co.electriccoin.zcash.ui.common.usecase.Zc101RecordingProposalDataSource
import co.electriccoin.zcash.ui.common.usecase.syntheticZashiAccount
import co.electriccoin.zcash.ui.common.usecase.syntheticZip321Proposal
import co.electriccoin.zcash.ui.common.util.FaultSeam
import co.electriccoin.zcash.ui.common.util.FaultSeams
import co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferences
import co.electriccoin.zcash.ui.screen.chat.model.ChatListState
import co.electriccoin.zcash.ui.screen.chat.model.ChatMessage
import co.electriccoin.zcash.ui.screen.chat.model.ConversationMode
import co.electriccoin.zcash.ui.screen.chat.model.SendMessageState
import co.electriccoin.zcash.ui.screen.chat.usecase.CreateChunkedMessageProposalUseCase
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Modifier
import java.lang.reflect.Proxy
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * F8 (fix-all 2.13.0, train T6b; ZC-112, ZC-121, ZC-600): the fixture of the send-attempt ledger tests.
 *
 * The ledger is a new collaborator of ChatViewModel. Every name of it is reached BY NAME here (class names, the field
 * `sendAttempts`, the ledger's methods), so this file compiles and its tests run on a build that has no ledger: there
 * [LedgerDouble.exists] is false, [LedgerWorld.install] changes nothing, and each test fails on the behaviour it asks
 * for (an entry, a notice, "nothing is paid a second time"), not on a missing class.
 *
 * What is real: ChatViewModel, the use case and the repository with its detached submit scope (as [HandshakeHarness]),
 * and the chat history pass with its chain read ([SendAttemptHarness]'s synthetic wallet). What is faked: the
 * preferences store ([LedgerPrefs], a recording overlay on [AutoSpendPrefs]), the SDK seam ([IdsHandshakeSeam]) and the
 * ledger's store (the in-memory twin, behind a proxy that can fail a write or run a hook at one exact step).
 *
 * Every address, key, group id and transaction id is synthetic.
 */
internal object LedgerNames {
    const val PKG = "co.electriccoin.zcash.ui.common.sendattempt"
    const val LEDGER = "$PKG.SendAttemptLedger"
    const val TWIN = "$PKG.InMemorySendAttemptLedger"
    const val ENVIRONMENT = "$PKG.SendAttemptEnvironment"
    const val TAG = "$PKG.SendAttemptTag"
    const val KIND = "$PKG.SendAttemptKind"
    const val WRITE_FAILED = "$PKG.LedgerWriteFailedException"

    /** The ledger methods that write an entry. */
    val WRITES =
        setOf(
            "begin",
            "recordStored",
            "discard",
            "settleDelivered",
            "settleMined",
            "markNotSent",
            "reopen",
            "setAnchor",
            "delete",
            "writeAhead",
        )
}

/** One ledger entry, read by name. */
internal class EntryView(
    val raw: Any,
) {
    @Suppress("UNCHECKED_CAST")
    private fun <T> read(name: String): T = raw.javaClass.getMethod("get" + name.replaceFirstChar { it.uppercase() }).invoke(raw) as T

    val key: String get() = read("key")
    val kind: String get() = read<Any>("kind").toString()
    val subject: String get() = read("subject")
    val identity: String get() = read("identity")
    val startedAt: Long get() = read("startedAtMillis")
    val state: String get() = read<Any>("state").toString()
    val txIds: List<String> get() = read("txIds")
    val expiries: Map<String, Long> get() = read("expiries")
    val superseded: List<String> get() = read("supersededTxIds")
    val minedHeight: Long? get() = read("minedHeight")
    val processId: String? get() = read("processId")
    val accountHash: String? get() = read("accountHash")
    val payload: String? get() = read("payload")

    /** The same entry with the named constructor properties replaced (a record of an earlier build, another process). */
    fun with(vararg changes: Pair<String, Any?>): Any {
        val fields = raw.javaClass.declaredFields.filter { !Modifier.isStatic(it.modifiers) }
        val constructor = raw.javaClass.constructors.single { it.parameterCount == fields.size }
        val replaced = changes.toMap()
        val args =
            fields.map { field ->
                field.isAccessible = true
                if (field.name in replaced) replaced[field.name] else field.get(raw)
            }
        return constructor.newInstance(*args.toTypedArray())
    }

    override fun toString(): String = "$kind/$state started=$startedAt ids=${txIds.size} superseded=${superseded.size}"
}

/**
 * The ledger a test installs: the in-memory twin reached by name, behind a `java.lang.reflect.Proxy` over the ledger
 * interface. The proxy records every call, can fail a write, and runs a hook before or after one method, so a test can
 * place a chat open, a Retry, a second pass or a kill at one exact step with no thread.
 */
internal class LedgerDouble {
    private val ledgerInterface: Class<*>? = runCatching { Class.forName(LedgerNames.LEDGER) }.getOrNull()
    val twin: Any? = runCatching { Class.forName(LedgerNames.TWIN).getDeclaredConstructor().newInstance() }.getOrNull()

    /** False on a build with no ledger. */
    val exists: Boolean get() = twin != null && ledgerInterface != null

    /** A first attempt's entry is not written (a retry's begin writes nothing, so it has nothing to fail). */
    @Volatile var failBegin = false

    @Volatile var failRecordStored = false

    @Volatile var failAnyWrite = false

    @Volatile var failDelete = false

    /** Every ledger call through the proxy, in order. */
    val calls: MutableList<String> = Collections.synchronizedList(mutableListOf())

    /** The order of ledger writes ("ledger:<method>") and store writes ("prefs:<method>") of one phone, when set. */
    @Volatile var timeline: MutableList<String>? = null

    private val changed: MutableList<String> = Collections.synchronizedList(mutableListOf())

    /** The calls that CHANGED what the ledger stores, in order (a begin over a failed entry writes nothing: not listed). */
    fun writes(): List<String> = synchronized(changed) { changed.toList() }

    private fun content(): String = twinCall("all").toString()

    private val before = ConcurrentHashMap<String, () -> Unit>()
    private val after = ConcurrentHashMap<String, () -> Unit>()

    fun beforeMethod(
        name: String,
        hook: () -> Unit,
    ) {
        before[name] = hook
    }

    fun afterMethod(
        name: String,
        hook: () -> Unit,
    ) {
        after[name] = hook
    }

    fun clearHooks() {
        before.clear()
        after.clear()
    }

    val proxy: Any? =
        if (twin == null || ledgerInterface == null) {
            null
        } else {
            Proxy.newProxyInstance(ledgerInterface.classLoader, arrayOf(ledgerInterface)) { p, method, args ->
                when (method.name) {
                    "toString" -> return@newProxyInstance "LedgerDouble"
                    "hashCode" -> return@newProxyInstance System.identityHashCode(p)
                    "equals" -> return@newProxyInstance (p === args?.get(0))
                }
                calls += method.name
                before[method.name]?.invoke()
                val refused =
                    when (method.name) {
                        // A retry's begin (its last argument, the marker the gate saw, is set) writes nothing.
                        "begin" -> (failBegin || failAnyWrite) && args?.last() == null
                        "recordStored" -> failRecordStored || failAnyWrite
                        "delete" -> failDelete || failAnyWrite
                        "discard", "settleDelivered", "settleMined", "markNotSent", "reopen", "setAnchor", "writeAhead" -> failAnyWrite
                        else -> false
                    }
                if (refused) {
                    if (method.name == "begin" || method.name == "recordStored") {
                        throw Class
                            .forName(LedgerNames.WRITE_FAILED)
                            .getConstructor(String::class.java)
                            .newInstance("test: write refused") as Throwable
                    }
                    return@newProxyInstance false
                }
                val stored = if (method.name in LedgerNames.WRITES) content() else null
                val result =
                    try {
                        method.invoke(twin, *(args ?: emptyArray()))
                    } catch (e: InvocationTargetException) {
                        throw e.targetException
                    }
                if (stored != null && stored != content()) {
                    changed += method.name
                    timeline?.add("ledger:" + method.name)
                }
                after[method.name]?.invoke()
                result
            }
        }

    private fun twinCall(
        name: String,
        vararg args: Any?,
    ): Any? {
        val method = twin!!.javaClass.methods.first { it.name == name && it.parameterCount == args.size }
        return try {
            method.invoke(twin, *args)
        } catch (e: InvocationTargetException) {
            throw e.targetException
        }
    }

    /** Every entry, read from the twin directly (not recorded in [calls]). Empty on a build with no ledger. */
    @Suppress("UNCHECKED_CAST")
    fun all(): List<EntryView> = if (twin == null) emptyList() else (twinCall("all") as List<Any>).map(::EntryView)

    fun of(kind: String): List<EntryView> = all().filter { it.kind == kind }

    fun one(kind: String): EntryView? = of(kind).singleOrNull()

    /** Writes [entry] as it is (a record of an earlier build, another process, a state a test starts from). */
    fun put(entry: Any) {
        twinCall("writeAhead", entry)
    }

    /** Removes [entry] from the twin (a test moves on to its next case). */
    fun remove(entry: EntryView) {
        twinCall("delete", entry.key, entry.startedAt)
    }

    fun isLockHeld(): Boolean = twin != null && twinCall("isLockHeld") as Boolean

    private fun tag(
        kind: String,
        subject: String,
        identity: String,
        payload: String? = null,
    ): Any {
        val kindClass = Class.forName(LedgerNames.KIND)
        val kindValue = kindClass.enumConstants!!.first { it.toString() == kind }
        return Class
            .forName(LedgerNames.TAG)
            .constructors
            .single { it.parameterCount == TAG_PROPERTIES }
            .newInstance(kindValue, subject, identity, null, null, payload)
    }

    /**
     * Seeds a LIVE entry the way a sender writes it (begin, then the ids commit when [txIds] is not empty), through the
     * twin. Returns it.
     */
    fun seedLive(
        kind: String,
        subject: String,
        identity: String,
        startedAt: Long,
        attemptHeight: Long?,
        accountHash: String,
        txIds: List<String> = emptyList(),
        expiries: Map<String, Long> = emptyMap(),
        processId: String = SendAttemptProcess.ID,
        payload: String? = null,
    ): EntryView {
        val claim = twinCall("begin", tag(kind, subject, identity, payload), startedAt, attemptHeight, processId, accountHash, null)
        if (txIds.isNotEmpty()) twinCall("recordStored", claim, txIds, expiries)
        twinCall("finish", claim)
        return all().first { it.kind == kind && it.subject == subject && it.identity == identity }
    }

    /** Turns the LIVE entry [entry] into NOT_SENT at [tip], as the proof's commit does. */
    fun seedNotSent(
        entry: EntryView,
        tip: Long,
    ): EntryView {
        val always: (Any) -> Boolean = { true }
        check(twinCall("markNotSent", entry.key, entry.startedAt, tip, always) as Boolean) { "seed: not marked" }
        return all().first { it.key == entry.key }
    }

    /** Turns the LIVE entry [entry] into MINED at [height], as the pass does. */
    fun seedMined(
        entry: EntryView,
        height: Long,
    ): EntryView {
        val always: (Any) -> Boolean = { true }
        check(twinCall("settleMined", entry.key, entry.startedAt, height, always) as Boolean) { "seed: not settled" }
        return all().first { it.key == entry.key }
    }

    private companion object {
        const val TAG_PROPERTIES = 6
    }
}

/**
 * The SDK seam of the ledger tests. Its three-argument submit models F2 (`ProposalDataSource.createStoreAndSubmit`):
 * every transaction is stored first and handed to `onStored`; if that callback THROWS, nothing is submitted (no submit
 * is counted) and the send ends `SentUnconfirmed(ids)`; otherwise the submit is counted and answers [outcome].
 */
internal class IdsHandshakeSeam(
    private val peer: String,
    private val recording: Zc101RecordingProposalDataSource = Zc101RecordingProposalDataSource(),
) : ProposalDataSource by recording {
    /** What the n-th submit (from 1) answers with the ids it stored; default: the network took it. */
    @Volatile var outcome: (Int, List<String>) -> SubmitResult = { _, ids -> SubmitResult.Success(ids) }

    /** The expiry height each stored transaction carries. */
    @Volatile var expiry: Long = SendAttemptHarness.H + EXPIRY_AHEAD

    /** Makes the next proposal request throw (a refusal before anything is stored), once. */
    @Volatile var failNextProposalWith: Exception? = null

    /** While set, every proposal request throws it (a refusal that outlasts the block waits). */
    @Volatile var failEveryProposalWith: Exception? = null

    /** ZC-1233 fixtures: while set, a build carries a real fee, 15,000 for the one note plus this (see feeCarryingZip321Proposal). */
    @Volatile var proposalFeeDelta: Long? = null

    /** While set, every proposal request waits for it (a seam that holds proposal creation). */
    @Volatile var holdProposals: CompletableDeferred<Unit>? = null

    /** While set, every submit waits for it after the ids were handed over. */
    @Volatile var holdSubmits: CompletableDeferred<Unit>? = null

    val proposals = AtomicInteger(0)
    val stored: MutableList<String> = Collections.synchronizedList(mutableListOf())
    val submits = AtomicInteger(0)

    /** ZC-1233 fixtures (while [proposalFeeDelta] is set): what each submitted transaction costs, its outputs and its fee. */
    val paidFigures: MutableList<Long> = Collections.synchronizedList(mutableListOf())

    @Volatile private var builtOutputs = 0L
    val proposalEntered = CompletableDeferred<Unit>()
    val submitEntered = CompletableDeferred<Unit>()
    private val nextId = AtomicInteger(FIRST_ID)

    override suspend fun createZip321Proposal(
        account: WalletAccount,
        zip321Uri: String,
        allowMultiPayment: Boolean,
    ): Zip321TransactionProposal {
        proposals.incrementAndGet()
        proposalEntered.complete(Unit)
        holdProposals?.await()
        failNextProposalWith?.let {
            failNextProposalWith = null
            throw it
        }
        failEveryProposalWith?.let { throw it }
        proposalFeeDelta?.let {
            builtOutputs =
                co.electriccoin.zcash.ui.screen.chat.usecase
                    .zip321Amounts(zip321Uri)
                    .sum()
            return feeCarryingZip321Proposal(peer, zip321Uri, listOf(1_000_000L), it)
        }
        return syntheticZip321Proposal(peer, amount = 1_000L, memo = "synthetic-ledger-memo")
    }

    override suspend fun submitTransaction(
        proposal: Proposal,
        usk: UnifiedSpendingKey,
        onStored: OnTransactionsStored,
    ): SubmitResult {
        recordPaidFigure(proposal)
        val id = SendAttemptHarness.txId(nextId.getAndIncrement())
        stored += id
        try {
            onStored(listOf(StoredTransaction(id, expiry)))
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            return SubmitResult.SentUnconfirmed(listOf(id), "the stored ids were not recorded")
        }
        val n = submits.incrementAndGet()
        submitEntered.complete(Unit)
        holdSubmits?.await()
        return outcome(n, listOf(id))
    }

    /** A build that records no ids calls this overload: one submit, the same scripted answer. */
    override suspend fun submitTransaction(
        proposal: Proposal,
        usk: UnifiedSpendingKey,
    ): SubmitResult {
        recordPaidFigure(proposal)
        val id = SendAttemptHarness.txId(nextId.getAndIncrement())
        stored += id
        val n = submits.incrementAndGet()
        submitEntered.complete(Unit)
        holdSubmits?.await()
        return outcome(n, listOf(id))
    }

    private fun recordPaidFigure(proposal: Proposal) {
        if (proposalFeeDelta != null) paidFigures += builtOutputs + proposal.totalFeeRequired().value
    }

    /** The number of the n-th stored transaction (from 1) in the synthetic wallet. */
    fun number(n: Int): Int = FIRST_ID + n - 1

    companion object {
        const val FIRST_ID = 7_001
        const val EXPIRY_AHEAD = 40L
    }
}

/** One recorded preferences call: what was asked, and which locks were held at that moment. */
internal class PrefsCall(
    val name: String,
    val args: List<Any?>,
    val ledgerLockHeld: Boolean,
) {
    override fun toString(): String = name
}

/**
 * The recording preferences double: [AutoSpendPrefs] (the handshake, bootstrap and group-join state) with the state
 * the ledger tests also need (the KEXACK markers, the owed marks, the quoted allowance, notes, the timer, the wallet
 * generation). Every call is recorded in order with whether the ledger's own lock was held at that moment.
 */
internal class LedgerPrefs(
    val base: AutoSpendPrefs = AutoSpendPrefs(),
    private val ledger: LedgerDouble? = null,
) {
    val log: MutableList<PrefsCall> = Collections.synchronizedList(mutableListOf())

    /** The shared order of ledger and store writes ([LedgerDouble.timeline]), when set. */
    @Volatile var timeline: MutableList<String>? = null
    val sentKexAck = ConcurrentHashMap<String, String>()
    val receivedKex = ConcurrentHashMap<String, String>()
    val peerNostr = ConcurrentHashMap<String, String>()
    val kexOwed: MutableSet<String> = ConcurrentHashMap.newKeySet()
    val kexOwedReason = ConcurrentHashMap<String, String>()
    val zbootQuoted: MutableSet<String> = ConcurrentHashMap.newKeySet()
    val zbootOwed: MutableSet<String> = ConcurrentHashMap.newKeySet()
    val rows = ConcurrentHashMap<String, ZchatPreferences.PendingMessageData>()
    val timers = ConcurrentHashMap<String, ZchatPreferences.DisappearingTtl>()
    val grantedRequests: MutableSet<String> = ConcurrentHashMap.newKeySet()
    val selfAddresses: MutableSet<String> = ConcurrentHashMap.newKeySet()
    val budgetUsed = AtomicInteger(0)

    @Volatile var generation = 0L

    /** While true a note or row write does not reach the store. */
    @Volatile var failRowWrites = false

    /** The calls that throw, as a store that cannot be read (by method name). */
    @Volatile var unreadable: Set<String> = emptySet()

    /** The names of the calls that changed something, in order (a setter, a mark, a clear, a row write). */
    fun writes(): List<String> = synchronized(log) { log.filter { isWrite(it.name) }.map { it.name } }

    fun writesSince(mark: Int): List<PrefsCall> = synchronized(log) { log.drop(mark).filter { isWrite(it.name) } }

    fun mark(): Int = log.size

    /** The names of the writes since [mark], in order. */
    fun writeNamesSince(mark: Int): List<String> = writesSince(mark).map { it.name }

    private fun isWrite(name: String): Boolean =
        WRITE_PREFIXES.any { name.startsWith(it) } && name != "registerOwnNostrPubkey" && name != "registerSelfAddress"

    @Suppress("CyclomaticComplexMethod", "LongMethod")
    val proxy: ZchatPreferences =
        Proxy.newProxyInstance(
            ZchatPreferences::class.java.classLoader,
            arrayOf(ZchatPreferences::class.java),
        ) { p, method, args ->
            fun arg(i: Int) = args!![i]

            fun peer() = arg(0) as String
            when (method.name) {
                "toString" -> return@newProxyInstance "LedgerPrefs"
                "hashCode" -> return@newProxyInstance System.identityHashCode(p)
                "equals" -> return@newProxyInstance (p === args?.get(0))
            }
            log += PrefsCall(method.name, args?.toList() ?: emptyList(), ledger?.isLockHeld() == true)
            if (isWrite(method.name)) timeline?.add("prefs:" + method.name)
            if (method.name in unreadable) throw IllegalStateException("synthetic: the store cannot be read")
            when (method.name) {
                "walletScopeGeneration" -> generation
                "isSelfAddress" -> peer() in selfAddresses
                "getSentKexAckPubkey" -> sentKexAck[peer()]
                "setSentKexAckPubkey" -> {
                    val value = arg(1) as String?
                    if (value == null) sentKexAck.remove(peer()) else sentKexAck[peer()] = value
                    true
                }
                "getReceivedKexPubkey" -> receivedKex[peer()]
                "setReceivedKexPubkey" -> {
                    val value = arg(1) as String?
                    if (value == null) receivedKex.remove(peer()) else receivedKex[peer()] = value
                    null
                }
                "tryConsumeKexAckBudget" -> {
                    budgetUsed.incrementAndGet()
                    true
                }
                "getPeerNostrPubkey" -> peerNostr[peer()]
                "isKexOwed" -> peer() in kexOwed
                "setKexOwed" -> {
                    if (arg(1) as Boolean) kexOwed += peer() else kexOwed -= peer()
                    true
                }
                "getKexOwedPeers" -> kexOwed.toSet()
                "getKexOwedReason" -> kexOwedReason[peer()]
                "isZbootQuoted" -> peer() in zbootQuoted
                "setZbootQuoted" -> {
                    if (arg(1) as Boolean) {
                        zbootQuoted += peer()
                    } else {
                        zbootQuoted -= peer()
                        zbootOwed -= peer()
                    }
                    true
                }
                "restoreQuotedZbootAsOwed" -> {
                    zbootQuoted += peer()
                    zbootOwed += peer()
                    true
                }
                "isZbootOwed" -> peer() in zbootOwed
                "getZbootOwedPeers" -> zbootOwed.toSet()
                "getPendingMessages" -> rows.values.sortedBy { it.timestampMillis }
                "addPendingMessage", "commitPendingMessage" -> {
                    val row = arg(0) as ZchatPreferences.PendingMessageData
                    if (!failRowWrites) rows[row.id] = row
                    null
                }
                "removePendingMessage" -> {
                    rows.remove(arg(0) as String)
                    null
                }
                "removePendingMessages" -> {
                    @Suppress("UNCHECKED_CAST")
                    (arg(0) as Set<String>).forEach { rows.remove(it) }
                    null
                }
                "getDisappearingTtl" -> timers[peer()]
                "setDisappearingTtl" -> {
                    timers[peer()] = ZchatPreferences.DisappearingTtl(arg(1) as Long, arg(2) as Long)
                    true
                }
                "getOrPutPendingTxFirstSeenMillis" -> arg(1)
                "isGroupKeyRequestGranted" -> (arg(0) as String) in grantedRequests
                "setGroupKeyRequestGranted" -> {
                    if (arg(1) as Boolean) grantedRequests += arg(0) as String else grantedRequests -= arg(0) as String
                    true
                }
                else ->
                    try {
                        method.invoke(base.proxy, *(args ?: emptyArray()))
                    } catch (e: InvocationTargetException) {
                        throw e.targetException
                    }
            }
        } as ZchatPreferences

    private companion object {
        val WRITE_PREFIXES = listOf("set", "mark", "clear", "add", "commit", "remove", "restore", "save", "delete", "store", "record")
    }
}

/**
 * One phone for a ledger test: a contact ([peer]) in a chat of [mode], the recording store, the SDK seam, the synthetic
 * wallet the history pass reads, and the ledger. [openVm] gives a ChatViewModel over them (a second call is a second
 * screen, or the process after a restart when [restart] was called in between).
 */
internal class LedgerWorld(
    val peer: String = PEER,
    mode: ConversationMode = ConversationMode.TUNNEL,
    exchangeComplete: Boolean = false,
    consent: Boolean = true,
    /** False: no ledger is installed (the world of every test written before the ledger existed). */
    private val withLedger: Boolean = true,
    /** The phone's accounts, when a test changes them (a spendable balance for a paid Join, a Keystone account). */
    private val accountSource: AccountDataSource? = null,
) {
    val ledger = LedgerDouble()
    val base = AutoSpendPrefs(consent)
    val prefs = LedgerPrefs(base, ledger)

    /** The order of every ledger write and every store write of this phone. */
    val timeline: MutableList<String> =
        Collections.synchronizedList(mutableListOf<String>()).also {
            ledger.timeline = it
            prefs.timeline = it
        }
    val seam = IdsHandshakeSeam(peer)
    val wallet = SendAttemptHarness.Wallet(scanned = SendAttemptHarness.H)
    val ourKeys = base.session(peer, complete = exchangeComplete, mode = mode)

    /** What the environment answers for the account an attempt is made by; null: no ZCHAT account is known. */
    @Volatile var scriptedAccountHash: String? = null

    @Volatile private var scripted = false
    private lateinit var accounts: AccountDataSource

    fun scriptAccountHash(value: String?) {
        scripted = true
        scriptedAccountHash = value
    }

    /** The hash the view model itself computes for its ZCHAT account (the entries of this phone carry it). */
    fun accountHash(): String =
        SendAttemptProcess.accountHash(
            accounts.allAccounts.value!!
                .firstNotNullOf { it as? ZashiAccount }
                .sdkAccount.accountUuid.value,
        )

    suspend fun openVm(): ChatViewModel {
        if (!::accounts.isInitialized) accounts = accountSource ?: Zc101FakeAccountDataSource(syntheticZashiAccount())
        val owner = SendHarnessScopes.current()
        val accounts = accounts
        val vm = HandshakeHarness.chatViewModelWith(prefs.proxy, HandshakeSeam(peer), accountSource = accounts)
        val useCase =
            CreateChunkedMessageProposalUseCase(
                keystoneProposalRepository = Zc101FakeKeystoneProposalRepository(),
                zashiProposalRepository =
                    ZashiProposalRepositoryImpl(
                        accountDataSource = accounts,
                        proposalDataSource = seam,
                        zashiSpendingKeyDataSource = Zc101FakeZashiSpendingKeyDataSource(),
                    ).also(owner::own),
                accountDataSource = accounts,
                navigationRouter = Zc101RecordingNavigationRouter(),
                biometricRepository = HandshakeHarness.ApprovingBiometricRepository,
            )
        PaymentHarness.setField(vm, "createChunkedMessageProposal", useCase)
        AutoSpendVm.wireBootstrapFields(vm)
        // What the history pass reads beside the handshake fields (PaymentHarness.paymentViewModel's list).
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
        // The synthetic wallet (transactions, heights). It also sets a store wrapper and a contact book for the payment
        // harness; this world's own store and contacts go back in after it.
        SendAttemptHarness.install(vm, PaymentPrefs(peer), wallet)
        PaymentHarness.setField(vm, "zchatPreferences", prefs.proxy)
        PaymentHarness.setField(vm, "contactBook", EmptyContacts)
        if (withLedger) install(vm)
        return vm
    }

    /**
     * Sets the view model's `sendAttempts` to an environment over this world's ledger, and gives it the flow its notice
     * is published in. A no-op on a build that has no such field: the tests then run, and fail, on behaviour.
     */
    fun install(vm: ChatViewModel) {
        val field = ChatViewModel::class.java.declaredFields.firstOrNull { it.name == "sendAttempts" } ?: return
        val environmentInterface = runCatching { Class.forName(LedgerNames.ENVIRONMENT) }.getOrNull() ?: return
        val ledgerProxy = ledger.proxy ?: return
        val environment =
            Proxy.newProxyInstance(environmentInterface.classLoader, arrayOf(environmentInterface)) { p, method, args ->
                when (method.name) {
                    "getLedger" -> ledgerProxy
                    "accountHash" -> if (scripted) scriptedAccountHash else accountHash()
                    "toString" -> "ScriptedEnvironment"
                    "hashCode" -> System.identityHashCode(p)
                    "equals" -> p === args?.get(0)
                    else -> throw UnsupportedOperationException("unexpected SendAttemptEnvironment.${method.name}")
                }
            }
        field.isAccessible = true
        field.set(vm, environment)
        val notice = MutableStateFlow<Map<String, Any>>(emptyMap())
        for (name in listOf("_handshakeNotSent", "handshakeNotSent")) {
            ChatViewModel::class.java.declaredFields.firstOrNull { it.name == name }?.let {
                it.isAccessible = true
                it.set(vm, notice)
            }
        }
    }

    /** One complete history pass of [vm] with the wallet scanned to [tip]. */
    suspend fun pass(
        vm: ChatViewModel,
        tip: Long,
    ) {
        wallet.scanned = tip
        wallet.network = tip
        SendAttemptHarness.runPass(vm, wallet)
    }

    /** The chats whose notice [vm] shows: contact to the marker of the failed attempt the notice was drawn from. */
    fun notice(vm: ChatViewModel): Map<String, Long> {
        val field = ChatViewModel::class.java.declaredFields.firstOrNull { it.name == "_handshakeNotSent" } ?: return emptyMap()
        field.isAccessible = true

        @Suppress("UNCHECKED_CAST")
        val shown = (field.get(vm) as? MutableStateFlow<Map<String, Any>>)?.value ?: return emptyMap()
        return shown.mapValues { (_, offer) -> offer.javaClass.getMethod("getStartedAt").invoke(offer) as Long }
    }

    /** Taps Retry on the notice [vm] shows for [peer] (the marker handed back is the one drawn). False: no such tap exists. */
    fun tapRetry(
        vm: ChatViewModel,
        offerStartedAt: Long,
    ): Boolean {
        val method =
            ChatViewModel::class.java.methods.firstOrNull { it.name == "retryHandshake" && it.parameterCount == 2 } ?: return false
        method.invoke(vm, peer, offerStartedAt)
        return true
    }

    /** The paid question on [vm]'s screen, by the name of its action; null when none is open. */
    fun question(vm: ChatViewModel): String? =
        (HandshakeHarness.sendMessageState(vm) as? SendMessageState.NeedsPaidActionConfirmation)
            ?.confirmation
            ?.action
            ?.name

    /** The stored transaction number [n] (from 1) as the wallet lists it. */
    fun chainTx(
        n: Int,
        minedHeight: Long? = null,
        expiryHeight: Long? = seam.expiry,
        recipient: String? = peer,
    ) = SendAttemptHarness.ChainTx(
        number = seam.number(n),
        recipient = recipient,
        memo = null,
        minedHeight = minedHeight,
        expiryHeight = expiryHeight,
        amount = 1_000L,
    )

    /** The process ended and started again: nothing is in flight, the proof is empty, the in-memory guards are gone. */
    fun restart() {
        LedgerProcess.reset()
    }

    companion object {
        const val PEER = "usyntheticledgerpeer00000001"
        const val H = SendAttemptHarness.H
        const val MARGIN = 10L
    }
}

/** Process-wide state the ledger tests share with every other test of a Gradle worker. */
internal object LedgerProcess {
    /**
     * The "kill": a cancellation, which every catch on the send path rethrows untouched (so nothing after the kill point
     * runs but the finally blocks, which only touch in-memory state a dead process would not have either), and which a
     * launched coroutine ends on without an uncaught-exception report.
     */
    class Killed : kotlinx.coroutines.CancellationException("test: the process was killed here")

    private val realKiller = FaultSeams.killer

    /** Arms the debug seam that ends the process right after an attempt's entry is written and before anything is created. */
    fun armKillAfterBegin() {
        FaultSeams.killer = { throw Killed() }
        FaultSeams.arm(FaultSeam.KILL_AFTER_MARKER_BEFORE_CREATE, 1)
    }

    fun disarm() {
        FaultSeams.disarm(FaultSeam.KILL_AFTER_MARKER_BEFORE_CREATE)
        FaultSeams.killer = realKiller
    }

    /** Clears the process-wide guards a test may have left, as a fresh process starts. */
    fun reset() {
        disarm()
        SendAttemptProof.clearAll()
        AutoSpendVm.resetProcessState()
        for (name in listOf("kexAckedKeys", "kexAckInFlight", "nostrBootInFlight", "retryInFlight", "kexOwedPeers", "owedKexLocks")) {
            val field =
                ChatViewModel::class.java.declaredFields
                    .firstOrNull { it.name == name && Modifier.isStatic(it.modifiers) } ?: continue
            field.isAccessible = true
            (field.get(null) as? MutableCollection<*>)?.clear()
            (field.get(null) as? MutableMap<*, *>)?.clear()
        }
        inFlight().clear()
    }

    /** Empties the set of running Retries (a test stands in for a second process, or removes the single-flight). */
    fun clearRetryInFlight() {
        val field =
            ChatViewModel::class.java.declaredFields
                .firstOrNull { it.name == "retryInFlight" && Modifier.isStatic(it.modifiers) } ?: return
        field.isAccessible = true
        (field.get(null) as MutableCollection<*>).clear()
    }

    /** The ledger keys whose Retry is running in this process (empty on a build with no Retry). */
    @Suppress("UNCHECKED_CAST")
    fun retryInFlight(): Set<String> {
        val field =
            ChatViewModel::class.java.declaredFields
                .firstOrNull { it.name == "retryInFlight" && Modifier.isStatic(it.modifiers) } ?: return emptySet()
        field.isAccessible = true
        return (field.get(null) as Set<String>).toSet()
    }

    @Suppress("UNCHECKED_CAST")
    private fun inFlight(): MutableSet<String> =
        SendAttemptProcess::class.java
            .getDeclaredField("inFlight")
            .apply { isAccessible = true }
            .get(SendAttemptProcess) as MutableSet<String>
}
