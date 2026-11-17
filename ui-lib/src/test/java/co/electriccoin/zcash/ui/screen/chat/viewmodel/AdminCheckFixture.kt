package co.electriccoin.zcash.ui.screen.chat.viewmodel

import androidx.lifecycle.viewModelScope
import cash.z.ecc.android.sdk.Synchronizer
import cash.z.ecc.android.sdk.model.BlockHeight
import cash.z.ecc.android.sdk.model.TransactionId
import cash.z.ecc.android.sdk.model.Zatoshi
import co.electriccoin.zcash.ui.common.repository.ReceiveTransaction
import co.electriccoin.zcash.ui.common.repository.Transaction
import co.electriccoin.zcash.ui.common.repository.TransactionRepository
import co.electriccoin.zcash.ui.nostr.NostrChatBridge
import co.electriccoin.zcash.ui.screen.chat.crypto.E2EEncryption
import co.electriccoin.zcash.ui.screen.chat.crypto.E2EKeyPair
import co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferences
import co.electriccoin.zcash.ui.screen.chat.model.ChatMessage
import co.electriccoin.zcash.ui.screen.chat.model.Contact
import co.electriccoin.zcash.ui.screen.chat.model.ContactBook
import co.electriccoin.zcash.ui.screen.chat.model.Conversation
import co.electriccoin.zcash.ui.screen.chat.model.GroupInfo
import co.electriccoin.zcash.ui.screen.chat.model.GroupMember
import co.electriccoin.zcash.ui.screen.chat.model.MemberStatus
import co.electriccoin.zcash.ui.screen.chat.model.ZMSGGroupProtocol
import co.electriccoin.zcash.ui.screen.chat.model.announceStateFromJson
import co.electriccoin.zcash.ui.screen.chat.model.announceStateToJson
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.lang.reflect.Field
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.lang.reflect.Proxy
import java.time.Instant
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.Continuation
import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn

/**
 * KI-25 pair 1 (ZC-022, KI-25-SPEC 3.6b section 4) — the wallet history of one [AdminCheckPhone]: the received
 * transactions the tests put there and their memos. [memoReads] and [listReads] record what a caller read, in call
 * order, so a case can say that only the transactions of one block time were opened.
 *
 * Every transaction id, time and memo is synthetic.
 */
internal class AdminCheckHistory : TransactionRepository {
    private val list = MutableStateFlow<List<Transaction>?>(emptyList())
    private val memos = ConcurrentHashMap<String, List<String>>()

    /** The transaction ids whose memos were read ([getMemos]), in call order. */
    val memoReads: MutableList<String> = Collections.synchronizedList(mutableListOf())

    /** How often the whole list was read ([getTransactions]). */
    val listReads = AtomicInteger(0)

    /**
     * KI-25 pair 1b (ZC-704): the transaction ids whose memos cannot be read. [getMemos] throws for them, after the read
     * is logged, as a wallet whose memo read fails does; the pass counts it as a memo it could not read.
     */
    val unreadable: MutableSet<String> = ConcurrentHashMap.newKeySet()

    override val transactions: Flow<List<Transaction>?> get() = list

    override suspend fun getMemos(transaction: Transaction): List<String> {
        val id = transaction.id.txIdString()
        memoReads += id
        if (id in unreadable) throw java.io.IOException("fixture: the memo of this transaction cannot be read")
        return memos[id].orEmpty()
    }

    override fun observeTransaction(txId: String): Flow<Transaction?> = flowOf(null)

    override fun observeTransactionsByMemo(memo: String): Flow<List<TransactionId>?> = flowOf(null)

    override suspend fun getTransactions(): List<Transaction> {
        listReads.incrementAndGet()
        return list.value.orEmpty()
    }

    override suspend fun getAllRecipientAddresses(transaction: Transaction): List<String> = emptyList()

    /**
     * One received, mined transaction number [n] with block time [time] that carries [memo]; it is in the history from
     * now on (the [transactions] flow emits the longer list).
     */
    fun received(
        memo: String,
        time: Instant,
        n: Int,
        minedHeight: Long = AdminCheck.HEIGHT + n,
    ): Transaction {
        val id = AnnounceDriver.txId(n)
        val tx =
            ReceiveTransaction.Success(
                id = id,
                amount = Zatoshi(1_000),
                timestamp = time,
                memoCount = 1,
                transactionOutputs = emptyList(),
                overview = ScanPassHarness.overview(id, minedHeight, time, sent = false, amount = 1_000),
                recipient = null,
            )
        memos[id.txIdString()] = listOf(memo)
        list.value = list.value.orEmpty() + tx
        return tx
    }
}

/**
 * KI-25 pair 1 (ZC-022) — one view model's own window on a phone's wallet history: every read goes to [history], and
 * [memoReads] holds the transaction ids whose memos THIS view model read, in call order. Two view models over one
 * phone each get one ([AdminCheck.ownHistory]), so a case can say which of them made a history pass.
 */
internal class AdminCheckHistoryView(
    private val history: TransactionRepository,
) : TransactionRepository by history {
    val memoReads: MutableList<String> = Collections.synchronizedList(mutableListOf())

    override suspend fun getMemos(transaction: Transaction): List<String> {
        memoReads += transaction.id.txIdString()
        return history.getMemos(transaction)
    }
}

/**
 * KI-25 pair 1 (ZC-022) — one member's phone for the admin-check tests: an [AnnouncePhone] ([base]) behind a wrapper
 * ([prefs]) that answers from the fields below and hands every other store call to [AnnouncePhone.proxy] unchanged.
 * [calls] holds the name of every store call the code under test made, in call order (the wrapper's own answers and
 * the delegated ones).
 *
 * Answered here:
 * - `isE2EVerified(address)`: [verified];
 * - `getInboundKexAwaitingAccept(address)`: [AnnouncePhone.awaitingAccept] of [base];
 * - `getPeerIronwoodCap(address)`: [ironwoodCaps] (true = that contact's ZChat is 2.13, false = older; no entry = no
 *   record);
 * - `saveGroupInfosDurably(records: Map<String, String>): Boolean` (new with pair 1; one editor, one commit): the call
 *   is logged in [durableWrites], [onDurableWrite] runs inside it (a case swaps a contact's key there), and it stores
 *   every record and answers true unless [storeRefuses], in which case it stores nothing and answers false;
 * - `walletScopeGeneration()`: [walletGeneration];
 * - `getNickname` / `getDisplayName`: [names] (the address itself when it has no name);
 * - `getDisappearingTtls()`: an empty map (the history flow combines it);
 * - `isSelfAddress(address)`: true for [ownAddresses] too (another address of this phone, as an imported account's).
 *
 * [onCall] runs at every store call, after it is logged in [calls], with the call's name: a case reads there what
 * the process held at that moment (the pass signal, the group's pass number).
 *
 * KI-25 pair 1b (ZC-704, R19) adds, all answered from fields:
 * - `markNostrEventSeen(eventId[, wrapCreatedAtSec])`, `unmarkNostrEventSeen(eventId)`, `hasSeenNostrEvent(eventId[, …])`:
 *   [seenEvents], the relay seen list;
 * - `flushGroupStores(): Boolean` (new with pair 1b): [flushAnswers];
 * - `groupKeyStoreCommitFailed(): Boolean` (new with pair 1b): [keyCommitFails] (true = the commit was refused);
 * - `getPeerNostrPubkey(address)`: [AnnouncePhone.nostrKeys] of [base];
 * - `saveGroupAnnounceState(groupId, state)` while [announceWritesRefused]: the state is kept in memory and the call
 *   answers false, as a refused `commit()` does on the platform; otherwise the call goes to [base] as before.
 * [callLog] holds every store call with its arguments, in call order (the same calls as [calls]). A case that makes a
 * store read throw does it in [onCall] (the exception leaves the store call as a store's own would).
 *
 * The saved contacts ([contactBook]) are [savedContacts]; a contact saved there with a key in
 * [AnnouncePhone.peerKeys] and no entry in [AnnouncePhone.ourKeys] is "a saved contact with no conversation".
 *
 * All addresses, keys and ids are synthetic.
 */
internal class AdminCheckPhone(
    val base: AnnouncePhone = AnnouncePhone(),
) {
    val self: String get() = base.self

    /** The admin's key pair for this phone (what the admin signs with), when the phone has a chat with the admin. */
    var adminKeys: E2EKeyPair? = null
    val admin: E2EKeyPair get() = adminKeys ?: error("fixture: this phone has no chat with the admin")

    val verified: MutableSet<String> = ConcurrentHashMap.newKeySet()
    val ironwoodCaps = ConcurrentHashMap<String, Boolean>()
    val names = ConcurrentHashMap<String, String>()
    val savedContacts: MutableList<Contact> = Collections.synchronizedList(mutableListOf())

    @Volatile var storeRefuses: Boolean = false

    @Volatile var onDurableWrite: ((Map<String, String>) -> Unit)? = null

    @Volatile var walletGeneration: Long = 0L

    /** More addresses of this phone itself, beside [self]. */
    val ownAddresses: MutableSet<String> = ConcurrentHashMap.newKeySet()

    @Volatile var onCall: ((String) -> Unit)? = null

    /** KI-25 pair 1b: the relay seen list (event ids marked seen and not un-marked). */
    val seenEvents: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** KI-25 pair 1b: what `flushGroupStores()` answers (true = all three stores took their commit). */
    @Volatile var flushAnswers: Boolean = true

    /** KI-25 pair 1b: what `groupKeyStoreCommitFailed()` answers (true = the group-key store refused its commit). */
    @Volatile var keyCommitFails: Boolean = false

    /** KI-25 pair 1b: `saveGroupAnnounceState` keeps the value in memory and answers false. */
    @Volatile var announceWritesRefused: Boolean = false

    /** KI-25 pair 1b: every store call with its arguments, in call order. */
    val callLog: MutableList<Pair<String, List<Any?>>> = Collections.synchronizedList(mutableListOf())

    val durableWrites: MutableList<Map<String, String>> = Collections.synchronizedList(mutableListOf())
    val calls: MutableList<String> = Collections.synchronizedList(mutableListOf())
    val history = AdminCheckHistory()

    private val noTimers = MutableStateFlow<Map<String, ZchatPreferences.DisappearingTtl>>(emptyMap())

    @Suppress("CyclomaticComplexMethod")
    val prefs: ZchatPreferences =
        Proxy.newProxyInstance(
            ZchatPreferences::class.java.classLoader,
            arrayOf(ZchatPreferences::class.java),
        ) { p, method, args ->
            val a = args ?: emptyArray()
            when (method.name) {
                "toString" -> "AdminCheckPhone($self)"
                "hashCode" -> System.identityHashCode(p)
                "equals" -> p === a.getOrNull(0)
                else -> {
                    calls += method.name
                    callLog += method.name to a.toList()
                    onCall?.invoke(method.name)
                    when {
                        method.name == "markNostrEventSeen" -> return@newProxyInstance null.also { seenEvents += a[0] as String }
                        method.name == "unmarkNostrEventSeen" -> return@newProxyInstance null.also { seenEvents -= a[0] as String }
                        method.name == "hasSeenNostrEvent" -> return@newProxyInstance (a[0] as String) in seenEvents
                        method.name == "flushGroupStores" -> return@newProxyInstance flushAnswers
                        method.name == "groupKeyStoreCommitFailed" -> return@newProxyInstance keyCommitFails
                        method.name == "getPeerNostrPubkey" -> return@newProxyInstance base.nostrKeys[a[0] as String]
                        method.name == "saveGroupAnnounceState" && announceWritesRefused -> {
                            base.states[a[0] as String] = a[1] as co.electriccoin.zcash.ui.screen.chat.model.AnnounceState
                            return@newProxyInstance false
                        }
                    }
                    when (method.name) {
                        "isE2EVerified" -> (a[0] as String) in verified
                        "isSelfAddress" -> (a[0] as String) in ownAddresses || method.invoke(base.proxy, *a) as Boolean
                        "getInboundKexAwaitingAccept" -> base.awaitingAccept[a[0] as String]
                        "getPeerIronwoodCap" ->
                            ironwoodCaps[a[0] as String]?.let { ZchatPreferences.PeerIronwoodCap(it, AdminCheck.T / 1_000) }
                        "saveGroupInfosDurably" -> {
                            @Suppress("UNCHECKED_CAST")
                            val records = HashMap(a[0] as Map<String, String>)
                            durableWrites += records
                            onDurableWrite?.invoke(records)
                            if (storeRefuses) {
                                false
                            } else {
                                base.infos.putAll(records)
                                true
                            }
                        }
                        "walletScopeGeneration" -> walletGeneration
                        "getNickname" -> names[a[0] as String]
                        "getDisplayName" -> names[a[0] as String] ?: a[0]
                        "getDisappearingTtls" -> noTimers
                        else ->
                            try {
                                method.invoke(base.proxy, *a)
                            } catch (e: InvocationTargetException) {
                                throw e.targetException
                            }
                    }
                }
            }
        } as ZchatPreferences

    val contactBook: ContactBook =
        Proxy.newProxyInstance(ContactBook::class.java.classLoader, arrayOf(ContactBook::class.java)) { p, method, args ->
            when (method.name) {
                "getContact" -> savedContacts.firstOrNull { it.address == args!![0] }
                "getAllContacts" -> savedContacts.toList()
                "hasContact" -> savedContacts.any { it.address == args!![0] }
                "toString" -> "AdminCheckContacts"
                "hashCode" -> System.identityHashCode(p)
                "equals" -> p === args?.get(0)
                else -> error("fixture: unexpected ContactBook.${method.name}")
            }
        } as ContactBook

    /** [address] is shown as [name] everywhere: a saved contact with that name, and the same nickname. */
    fun name(
        address: String,
        name: String,
    ) {
        names[address] = name
        savedContacts.removeAll { it.address == address }
        savedContacts += Contact(address = address, name = name, addedAt = Instant.ofEpochMilli(AdminCheck.T))
    }

    /** The group key this phone stores for [groupId] at [epoch]. */
    fun groupKey(
        epoch: Int,
        groupId: String = AdminCheck.GID,
    ): String? = base.keys["$groupId|$epoch"]

    /**
     * The invitation that created the group, as the wallet's history holds it: a compact `GROUP_INVITE` with `k2` (the
     * group key [groupKey] at [keyEpoch], wrapped under [session]), in a received transaction number [n] whose block
     * time is [time]. The invite's own `inviter` text is [inviter] (the address text this phone stored as the
     * creator's, which is not where the session is: the #197 fallback).
     */
    fun creatingInvite(
        session: ByteArray,
        time: Instant,
        inviter: String = AdminCheck.X_OLD,
        groupKey: String = groupKey(1) ?: error("fixture: no group key at epoch 1"),
        keyEpoch: Int = 1,
        groupId: String = AdminCheck.GID,
        n: Int = 1,
    ): Transaction = history.received(AnnounceDriver.gi(inviter, session, groupKey, keyEpoch, groupId), time, n)

    /** Every store of the phone a control, a confirmation or a state read could write, as one comparable value. */
    fun snapshot(): Map<String, Any?> =
        mapOf(
            "infos" to HashMap(base.infos),
            "rosters" to HashMap(base.rosters),
            "keys" to HashMap(base.keys),
            "epochs" to HashMap(base.epochs),
            "floors" to HashMap(base.floors),
            "kickHeights" to HashMap(base.kickHeights),
            "states" to HashMap(base.states),
            "peerKeys" to HashMap(base.peerKeys),
            "aliases" to HashMap(base.aliases),
            "verified" to HashSet(verified),
            "needsJoin" to HashMap(base.needsJoin),
        )
}

/**
 * KI-25 pair 1 (ZC-022; KI-25-SPEC 3.6b sections 3.2 and 4) — the phones, the admin's changes and the seams of the
 * admin-check tests.
 *
 * The phones. X_OLD is the address text this phone stored as the group's creator when it joined; Y is the address
 * text it holds the admin's chat (and key) under; M is another member.
 * - [driftedPhone]: a chat with Y, the group's creator address is X_OLD with no key under it, no stored creator key,
 *   roster [X_OLD admin, this phone, M ACTIVE], key epoch 1, created at [T] (shape A);
 * - [pinnedPhone]: the same with the stored creator key = the admin's key (shape B, and what a confirmation leaves);
 * - [plainKeyPhone]: a group joined with the creator's plaintext relay invite (`plainKeyJoin`), a relay key for the
 *   creator, no key at the creator address and no stored creator key.
 *
 * **The new operations are reached by name through reflection**, so this file compiles on the commit before the fix.
 * A missing operation fails the calling test with "no such operation: <what is missing>". A function is found by its
 * name (or `name$<module>` when it is internal) and its parameter count. What the fix must provide, as the spec names
 * it or, where the spec gives no name, as chosen here (marked "chosen"):
 *
 * Model (`co.electriccoin.zcash.ui.screen.chat.model`, file `GroupAdminCheck.kt`):
 * - `enum class AdminKeyState { NOT_APPLICABLE, OK, UNKNOWN, ORPHANED }`;
 * - `GroupAdminNotice`, an enum whose constants are (chosen, after the three text keys) `CHECK_LINE`, `CATCHUP_KEY`,
 *   `CATCHUP_LIST`;
 * - `AdminConfirmOutcome`: `Confirmed` and `Refused(reason)`, the reason an enum with `NOT_NEEDED`,
 *   `NOT_THE_RECORDED_CHAT`, `OWN_ADDRESS`, `NOT_A_SECURE_CHAT`, `KEY_CHANGED`, `NOT_VERIFIED`, `KEY_DIFFERS`,
 *   `STORE_REFUSED`; [confirmationOutcome] reads the class's simple name and, of a `Refused`, the property `reason`;
 * - `AdminCandidate` with the properties `address`, `name`, `source` (chosen: an enum with `INVITATION` and `ADDRESS`).
 *
 * ChatViewModel:
 * - `groupAdminKeyState(groupId: String): AdminKeyState`;
 * - `loadGroupAdminPreview(groupId: String)` (plain or suspend) and `groupAdminPreview: StateFlow<P?>`, P with the
 *   properties (chosen) `name`, `address`, `contactKey`, `safetyNumber`, `source`;
 * - `confirmGroupAdmin(groupId: String, candidate: String, shownKey: String)` (plain or suspend; `candidate` is the
 *   chat's address) and `groupAdminConfirmation: StateFlow<AdminConfirmOutcome?>`, backed by (chosen) a
 *   `MutableStateFlow` field named `_groupAdminConfirmation`, which [confirm] clears before each call;
 * - in the companion (chosen names): `historyPassCounter: AtomicLong` (the process-wide count of history passes),
 *   `adminConfirmedAtPass: Map<String, Long>` (per group id, the number the counter showed at its confirmation) and
 *   `historyPassSignal: MutableStateFlow<Long>` (the pass signal, one more input of `conversationRenderFlow`);
 * - (chosen) `beginHistoryPass(): H`: takes the next pass number and reads the wallet generation, as the pass does
 *   before its loop. H is a `Long` (the number) or a class with a property `number: Long`;
 * - (chosen) `processGroupMessageInPass(memo: String, txId: TransactionId?, timestamp: Instant?, isOwnSend: Boolean,
 *   ownRecipient: String?, minedHeight: Long?, pass: H)`: what the pass calls for an on-chain group memo (seven
 *   parameters; `processGroupMessage` keeps its two forms);
 * - (chosen) `passMayApplyAdminChange(groupId, pass: H): Boolean`: the guard's test (the pass began after the group's
 *   confirmation, and the wallet generation is still the one the pass read). [AdminCheckSource] finds its two calls:
 *   in the dispatcher's `GROUP_KICK` and `GROUP_KEY` branches, inside the `withGroupLock(` block, before the handler;
 * - in the source of `confirmGroupAdmin` (what [AdminCheckSource] reads there): the group's number is recorded with
 *   `adminConfirmedAtPass[…] = …` (or `.put(`), the signal is raised with `historyPassSignal.update { … }` or an
 *   assignment to `historyPassSignal.value`, and the roster query is `processSendScope().launch { … sendRosterQuery(…) }`.
 *
 * GroupViewModel:
 * - `groupAdminNotice(groupId: String): GroupAdminNotice?`;
 * - `groupAdminKeyState(groupId: String): AdminKeyState`;
 * - `loadGroupAdminCandidate(groupId: String)` (plain or suspend) and `groupAdminCandidate: StateFlow<AdminCandidate?>`.
 *
 * ZchatPreferences: `saveGroupInfosDurably(records: Map<String, String>): Boolean` (the interface default reports
 * false), answered by [AdminCheckPhone.prefs].
 *
 * **KI-25 pair 1b (ZC-704, R19; KI-25-SPEC 3.6b section 4.1b)** adds the seams below. A missing operation fails the
 * calling test with "KI-25 pair 1b (ZC-704): no such operation: <what is missing>" ([missingKept]). What the fix must
 * provide, as the spec names it or as chosen here (marked "chosen"):
 *
 * Model, file `GroupMemberAnnounce.kt`:
 * - `KeptAdminChange` with the properties (chosen names; the JSON keys are the spec's) `id: String` (`id`),
 *   `kind: String` (`t`: `GK`, `GY` or `GI`), `epoch: Int` (`e`), `payload: String` (`p`), `from: String` (`from`),
 *   `by: String` (`by`), `at: Long` (`at`; chosen: the rumor's time as milliseconds, as ZC-294's `source_ms`),
 *   `kept: Long` (`kept`, milliseconds), `height: Long?` (`h`), `failedRuns: Int` (`f`, written only when not 0) and
 *   `accepted: Boolean` (`acc`, written only when true);
 * - `AnnounceState.keptAdminChanges: List<KeptAdminChange>` (the last field, default empty), JSON
 *   `kept_admin_changes`, written by `announceStateToJson` only when not empty and read by `announceStateFromJson`
 *   through a reader of its own. The tests build and read entries only through these two functions ([keptChanges],
 *   [putKept]), so the class's constructor is free.
 *
 * ChatViewModel (chosen names; [AdminCheckSource] holds them as constants):
 * - `endHistoryPass(pass: HistoryPass, complete: Boolean)`: the hook at the end of every history pass; `complete` is
 *   the pass's own test `historyScanComplete && memoReadFailures == 0` ([endPass]);
 * - `runKeptAdminChanges(…)`: the run of a group's stored changes with its flush and its acknowledgement, called from
 *   the hook only;
 * - `keepRelayAdminChange(…)`: the keep step of the dispatcher's `GROUP_KICK` and `GROUP_KEY` branches (a refused
 *   change, and a relay copy that was accepted and not saved);
 * - `keepRelayReinvite(…)`: the keep of a re-invite, called in the existing-group branch of `processGroupInviteLocked`;
 * - `handOverKeptReinvites(groupId: String)`: the hand-over of a group's kept invites to ZC-294's slot, called from
 *   `confirmGroupAdmin` and from
 * - `handOverKeptReinvitesBeforeLoop(pass: HistoryPass)`: what a history pass runs before its loop (the hand-over for
 *   every group that reads OK and whose list holds an invite). [beginPass] calls it after `beginHistoryPass()` when
 *   the view model has it; on the commit before the fix a pass runs nothing there, and neither does [beginPass].
 *
 * ZchatPreferences: `flushGroupStores(): Boolean` and `groupKeyStoreCommitFailed(): Boolean` (both interface defaults
 * answer false), answered by [AdminCheckPhone.prefs] from [AdminCheckPhone.flushAnswers] and
 * [AdminCheckPhone.keyCommitFails].
 *
 * The pure rules of `GroupAdminCheck` and `AdminChangeOutcome` are reached by KeptAdminChangesRulesTest, which lists
 * them.
 *
 * All addresses, keys, ids and times are synthetic.
 */
@Suppress("TooManyFunctions", "LargeClass")
internal object AdminCheck {
    const val GID = "zgrp_1c2d3e4f5a6b7c8d9e0f1a2b"

    /** The address text this phone stored as the group's creator; it holds no key under it. */
    const val X_OLD = "usyntheticki25adminold000001"

    /** The address text this phone holds the admin's chat and key under. */
    const val Y = "usyntheticki25adminchat00001"

    /** A second address text of the admin's chat (the same key, when a case links it so). */
    const val Y2 = "usyntheticki25adminchat00002"

    /** Another member of the group. */
    const val M = "usyntheticki25memberm0000001"

    /** A contact that is not in the group. */
    const val STRANGER = "usyntheticki25stranger000001"
    const val CREATOR_NOSTR = "6b6b6b6b6b6b6b6b6b6b6b6b6b6b6b6b6b6b6b6b6b6b6b6b6b6b6b6b6b6b6b6b"

    /** The relay key of the admin's chat [Y], when a case gives it one (`nostrKeys[Y] = Y_NOSTR`); synthetic. */
    val Y_NOSTR = "5c".repeat(32)

    /** The relay key of [Y2], when a case gives it one; synthetic. */
    val Y2_NOSTR = "4d".repeat(32)
    const val GROUP_NAME = "Admin check group"

    /** The group's stored creation time, as milliseconds (a whole second, as a block time is). */
    const val T = AnnounceDriver.T

    /** The mined height of the history's transaction number 0. */
    const val HEIGHT = 3_000_000L

    const val NOTICE_CHECK_LINE = "CHECK_LINE"
    const val NOTICE_CATCHUP_KEY = "CATCHUP_KEY"
    const val NOTICE_CATCHUP_LIST = "CATCHUP_LIST"
    const val CONFIRMED = "Confirmed"

    // ------------------------------------------------------------------ phones

    fun driftedPhone(): AdminCheckPhone = phone(pinned = false)

    fun pinnedPhone(): AdminCheckPhone = phone(pinned = true)

    private fun phone(pinned: Boolean): AdminCheckPhone {
        val p = AdminCheckPhone()
        val admin = p.base.link(Y)
        p.adminKeys = admin
        p.base.putGroup(
            GroupInfo(
                groupId = GID,
                name = GROUP_NAME,
                creatorAddress = X_OLD,
                createdAt = Instant.ofEpochMilli(T),
                isActive = true,
                creatorPublicKey = if (pinned) admin.publicKey else null,
            ),
            roster(p.self),
            1,
            AnnounceDriver.newGroupKey(),
        )
        return p
    }

    fun plainKeyPhone(): AdminCheckPhone {
        val p = AdminCheckPhone()
        p.base.putGroup(
            GroupInfo(
                groupId = GID,
                name = GROUP_NAME,
                creatorAddress = X_OLD,
                createdAt = Instant.ofEpochMilli(T),
                isActive = true,
                creatorPublicKey = null,
            ),
            roster(p.self),
            1,
            AnnounceDriver.newGroupKey(),
        )
        p.base.states[GID] = p.base.state(GID).copy(plainKeyJoin = true)
        p.base.nostrKeys[X_OLD] = CREATOR_NOSTR
        return p
    }

    private fun roster(self: String): List<GroupMember> =
        listOf(
            GroupMember(address = X_OLD, joinedAt = Instant.ofEpochMilli(T), status = MemberStatus.ACTIVE, isAdmin = true),
            GroupMember(address = self, joinedAt = Instant.ofEpochMilli(T), status = MemberStatus.ACTIVE),
            GroupMember(address = M, joinedAt = Instant.ofEpochMilli(T), status = MemberStatus.ACTIVE),
        )

    // ------------------------------------------------------- the admin's changes

    /**
     * A `GROUP_KICK` of [kicked] to [newEpoch] whose `kicker` text is [naming], signed with [signer] (the admin's key for
     * this phone unless a case forges one). The new key [newKey] is wrapped under this phone's session with [wrapFor]
     * (the admin's chat, wherever the kick says it comes from); null wraps no key, as in a kick that names this phone.
     */
    @Suppress("LongParameterList")
    fun kickNaming(
        p: AdminCheckPhone,
        naming: String,
        kicked: String,
        newEpoch: Int,
        newKey: String?,
        signer: E2EKeyPair = p.admin,
        wrapFor: String = Y,
        groupId: String = GID,
    ): String {
        val wrapped = newKey?.let { E2EEncryption.encrypt(it, p.base.sessionWith(wrapFor)) }
        val sig =
            E2EEncryption.sign(signer.privateKey, ZMSGGroupProtocol.groupKickSignedData(groupId, kicked, naming, newEpoch, wrapped))
        return ZMSGGroupProtocol.createGroupKickMessage(groupId, kicked, naming, newEpoch, wrapped, sig)
    }

    /** A `GROUP_KEY` rotation to [newEpoch] whose `signer` text is [naming]; the rest as [kickNaming]. */
    @Suppress("LongParameterList")
    fun keyNaming(
        p: AdminCheckPhone,
        naming: String,
        newEpoch: Int,
        newKey: String,
        signer: E2EKeyPair = p.admin,
        wrapFor: String = Y,
        reason: String = "rotation",
        groupId: String = GID,
    ): String {
        val wrapped = E2EEncryption.encrypt(newKey, p.base.sessionWith(wrapFor))
        val sig =
            E2EEncryption.sign(signer.privateKey, ZMSGGroupProtocol.groupKeySignedData(groupId, naming, newEpoch, wrapped, reason))
        return ZMSGGroupProtocol.createGroupKeyMessage(groupId, naming, newEpoch, wrapped, sig, reason)
    }

    // -------------------------------------------------------------- view models

    /** A real ChatViewModel over [p] as [AdminCheckPhone.self], with [p]'s saved contacts and wallet history. */
    suspend fun chatVm(
        p: AdminCheckPhone,
        seam: HandshakeSeam,
    ): ChatViewModel =
        HandshakeHarness.chatViewModelWith(p.prefs, seam).also { vm ->
            AutoSpendVm.wireBootstrapFields(vm)
            AutoSpendVm.setField(vm, "_currentUserAddress", MutableStateFlow<String?>(p.self))
            AutoSpendVm.setField(vm, "contactBook", p.contactBook)
            AutoSpendVm.setField(vm, "transactionRepository", p.history)
        }

    /** The group screen's GroupViewModel over [p] (the [GroupPaidOnceFixture] wiring and SDK seam). */
    suspend fun groupVm(
        p: AdminCheckPhone,
        f: GroupPaidOnceFixture,
    ): GroupViewModel {
        val vm = f.groupViewModel()
        setField(vm, "zchatPreferences", p.prefs)
        setField(vm, "contactBook", p.contactBook)
        setField(vm, "transactionRepository", p.history)
        @Suppress("UNCHECKED_CAST")
        (field(GroupViewModel::class.java, "_currentUserAddress")!!.get(vm) as MutableStateFlow<String?>).value = p.self
        vm.consumeGroupSendEvent()
        return vm
    }

    /** The gate every signed admin change passes (ChatViewModel.verifyGroupAdminControl, private). */
    fun verifyAdminControl(
        vm: ChatViewModel,
        signerAddress: String,
        signedData: String,
        signature: String?,
        groupId: String = GID,
    ): Boolean {
        val gate =
            ChatViewModel::class.java
                .getDeclaredMethod(
                    "verifyGroupAdminControl",
                    String::class.java,
                    String::class.java,
                    String::class.java,
                    String::class.java,
                ).apply { isAccessible = true }
        return invoke(gate, vm, groupId, signerAddress, signedData, signature) as Boolean
    }

    // ------------------------------------------------- the state and the notice

    /** `groupAdminKeyState(groupId)` of a ChatViewModel or a GroupViewModel: the constant's name. */
    fun keyState(
        vm: Any,
        groupId: String = GID,
    ): String {
        val method =
            method(vm.javaClass, "groupAdminKeyState", 1)
                ?: missing("${vm.javaClass.simpleName}.groupAdminKeyState(groupId: String): AdminKeyState")
        return constantName(invoke(method, vm, groupId), "groupAdminKeyState")
    }

    /** `GroupViewModel.groupAdminNotice(groupId)`: the constant's name ([NOTICE_CHECK_LINE], …), or null for no line. */
    fun notice(
        vm: GroupViewModel,
        groupId: String = GID,
    ): String? {
        val method =
            method(GroupViewModel::class.java, "groupAdminNotice", 1)
                ?: missing("GroupViewModel.groupAdminNotice(groupId: String): GroupAdminNotice?")
        return invoke(method, vm, groupId)?.let { constantName(it, "groupAdminNotice") }
    }

    // --------------------------------------------- the candidate and the preview

    /** `GroupViewModel.loadGroupAdminCandidate(groupId)`, then the value of `groupAdminCandidate` (null: none). */
    suspend fun loadCandidate(
        vm: GroupViewModel,
        f: GroupPaidOnceFixture,
        groupId: String = GID,
    ): Any? {
        val method =
            method(GroupViewModel::class.java, "loadGroupAdminCandidate", 1)
                ?: missing("GroupViewModel.loadGroupAdminCandidate(groupId: String)")
        val flow = stateFlow(vm, "groupAdminCandidate") ?: missing("GroupViewModel.groupAdminCandidate: StateFlow<AdminCandidate?>")
        f.awaitNewJobs(vm) { invokeMaybeSuspend(method, vm, groupId) }
        return flow.value
    }

    /**
     * `ChatViewModel.loadGroupAdminPreview(groupId)`, then the value of `groupAdminPreview` once the view model's own
     * jobs are done (null: the dialog has nothing to show).
     */
    suspend fun loadPreview(
        vm: ChatViewModel,
        groupId: String = GID,
    ): Any? {
        val method =
            method(ChatViewModel::class.java, "loadGroupAdminPreview", 1)
                ?: missing("ChatViewModel.loadGroupAdminPreview(groupId: String)")
        val flow = stateFlow(vm, "groupAdminPreview") ?: missing("ChatViewModel.groupAdminPreview: StateFlow")
        invokeMaybeSuspend(method, vm, groupId)
        HandshakeHarness.awaitIdle(vm)
        return flow.value
    }

    /** The property [name] of a candidate, a preview or a pass (its getter, else its field), as the code holds it. */
    fun property(
        target: Any,
        name: String,
    ): Any? {
        val getter = "get" + name.replaceFirstChar { it.uppercase() }
        val type = target.javaClass
        type.methods.firstOrNull { (it.name == getter || it.name.startsWith("$getter$")) && it.parameterCount == 0 }?.let {
            it.isAccessible = true
            return invoke(it, target)
        }
        val field = field(type, name) ?: missing("${type.simpleName}.$name")
        return field.get(target)
    }

    // ---------------------------------------------------------- the confirmation

    /**
     * `ChatViewModel.confirmGroupAdmin(groupId, candidate, shownKey)` and nothing more: the caller owns the scheduler
     * (a case that cancels the view model's scope before it runs again). The outcome is [confirmationOutcome].
     */
    suspend fun confirmNow(
        vm: ChatViewModel,
        candidate: String,
        shownKey: String,
        groupId: String = GID,
    ) {
        val method =
            method(ChatViewModel::class.java, "confirmGroupAdmin", 3)
                ?: missing("ChatViewModel.confirmGroupAdmin(groupId: String, candidate: String, shownKey: String)")
        val outcome =
            field(ChatViewModel::class.java, "_groupAdminConfirmation")
                ?: missing("ChatViewModel._groupAdminConfirmation: MutableStateFlow<AdminConfirmOutcome?>")
        @Suppress("UNCHECKED_CAST")
        (outcome.get(vm) as MutableStateFlow<Any?>).value = null
        invokeMaybeSuspend(method, vm, groupId, candidate, shownKey)
    }

    /**
     * [confirmNow], then waits for the outcome: [CONFIRMED], or the name of the refusal's reason (`NOT_NEEDED`,
     * `NOT_THE_RECORDED_CHAT`, `OWN_ADDRESS`, `NOT_A_SECURE_CHAT`, `KEY_CHANGED`, `NOT_VERIFIED`, `KEY_DIFFERS`,
     * `STORE_REFUSED`).
     */
    suspend fun confirm(
        vm: ChatViewModel,
        candidate: String,
        shownKey: String,
        groupId: String = GID,
    ): String {
        confirmNow(vm, candidate, shownKey, groupId)
        HandshakeHarness.awaitTrue { confirmationOutcome(vm) != null }
        return confirmationOutcome(vm)!!
    }

    /** The value of `groupAdminConfirmation`: null, [CONFIRMED], or the refusal reason's name. */
    fun confirmationOutcome(vm: ChatViewModel): String? {
        val flow =
            stateFlow(vm, "groupAdminConfirmation")
                ?: missing("ChatViewModel.groupAdminConfirmation: StateFlow<AdminConfirmOutcome?>")
        val outcome = flow.value ?: return null
        return if (outcome.javaClass.simpleName == CONFIRMED) CONFIRMED else property(outcome, "reason").toString()
    }

    // ------------------------------------------------------------ history passes

    /** The process-wide count of history passes (`historyPassCounter` in ChatViewModel's companion). */
    fun passCounter(): Long =
        (
            staticField("historyPassCounter")
                ?: missing("ChatViewModel.Companion.historyPassCounter: AtomicLong (the count of history passes)")
        ).let { (it.get(null) as AtomicLong).get() }

    /** The number the pass counter showed when [groupId]'s admin was confirmed in this process, or null. */
    fun confirmedAtPass(groupId: String = GID): Long? =
        (
            staticField("adminConfirmedAtPass")
                ?: missing("ChatViewModel.Companion.adminConfirmedAtPass: Map<String, Long> (the pass number per confirmed group)")
        ).let { (it.get(null) as Map<*, *>)[groupId] as Long? }

    /** The pass signal: the companion's process-wide flow that makes every live chat view model begin a pass. */
    fun passSignal(): StateFlow<Long> {
        val holder =
            staticField("historyPassSignal")
                ?: missing("ChatViewModel.Companion.historyPassSignal: MutableStateFlow<Long> (the pass signal)")
        @Suppress("UNCHECKED_CAST")
        return holder.get(null) as StateFlow<Long>
    }

    /**
     * Begins a history pass as the pass does before its loop: takes the next pass number and reads the wallet
     * generation (`ChatViewModel.beginHistoryPass()`). The value is handed to [receiveInPass]; [passNumber] reads it.
     *
     * KI-25 pair 1b (ZC-704): then it runs what the pass runs before its loop, the hand-over of kept invites
     * (`handOverKeptReinvitesBeforeLoop(pass)`), when the view model has it. On the commit before the fix a pass runs
     * nothing there, so nothing is run here either; the source scan of class 21 (r) is what says the call is missing.
     */
    fun beginPass(vm: ChatViewModel): Any {
        val begin =
            method(ChatViewModel::class.java, "beginHistoryPass", 0)
                ?: missing("ChatViewModel.beginHistoryPass() (takes the next history-pass number, reads the wallet generation)")
        val pass = invoke(begin, vm) ?: error("beginHistoryPass returned nothing")
        val beforeLoop: Method? = method(ChatViewModel::class.java, "handOverKeptReinvitesBeforeLoop", 1)
        if (beforeLoop != null) invoke(beforeLoop, vm, pass)
        return pass
    }

    /**
     * KI-25 pair 1b (ZC-704, R19) — ends the history pass [pass] as the pass does after its loop: the hook
     * `ChatViewModel.endHistoryPass(pass, complete)`. [complete] says whether the pass read the whole history (the
     * pass's own test, `historyScanComplete && memoReadFailures == 0`).
     */
    fun endPass(
        vm: ChatViewModel,
        pass: Any,
        complete: Boolean,
    ) {
        if (!endPassIfBuilt(vm, pass, complete)) {
            missingKept("ChatViewModel.endHistoryPass(pass: HistoryPass, complete: Boolean) (the hook at the end of a history pass)")
        }
    }

    /**
     * [endPass] for the cases the spec's table says hold on the commit before the fix too: there a pass has no hook at
     * its end, and the pass ends with nothing more. @return false when the view model has no hook.
     */
    fun endPassIfBuilt(
        vm: ChatViewModel,
        pass: Any,
        complete: Boolean,
    ): Boolean {
        val hook = method(ChatViewModel::class.java, "endHistoryPass", 2) ?: return false
        invoke(hook, vm, pass, complete)
        return true
    }

    /**
     * KI-25 pair 1b (ZC-704) — one relay delivery as the relay reader makes it (`observeNostrInbound`): the event is
     * marked seen first (`markNostrEventSeen(eventId)`), then the group memo is handed over with its seal's sender, its
     * event id and the rumor's time ([AnnounceDriver.receive], which alone skips the mark).
     */
    fun relayDeliver(
        vm: ChatViewModel,
        memo: String,
        sender: String,
        eventId: String,
        time: Instant,
    ) {
        (field(ChatViewModel::class.java, "zchatPreferences")!!.get(vm) as ZchatPreferences).markNostrEventSeen(eventId)
        AnnounceDriver.receive(vm, memo, time, sender = sender, eventId = eventId)
    }

    /** KI-25 pair 1b — the height [vm]'s wallet has scanned to from now on (`fullyScannedTip()` reads it). */
    fun setScannedTip(
        vm: ChatViewModel,
        height: Long,
    ) {
        setField(vm, "synchronizerProvider", ScanPassHarness.synchronizerProvider(scannedSynchronizer(height)))
    }

    /** KI-25 pair 1b — no group has a recorded confirmation number any more, as in a new process. */
    fun forgetConfirmedPasses() {
        (staticField("adminConfirmedAtPass")?.get(null) as? MutableMap<*, *>)?.clear()
    }

    /**
     * KI-25 pair 1b (ZC-704) — the stored list of [groupId] as the store's JSON holds it: the entries under
     * `kept_admin_changes` of `announceStateToJson(state)`, in stored order (none when the key is absent).
     */
    fun keptChanges(
        p: AdminCheckPhone,
        groupId: String = GID,
    ): List<JSONObject> = keptChangesOf(announceStateToJson(p.base.state(groupId)))

    /** The entries under `kept_admin_changes` of a stored announce state's JSON. */
    fun keptChangesOf(stateJson: String): List<JSONObject> =
        JSONObject(stateJson).optJSONArray(KEPT_KEY)?.let { a -> (0 until a.length()).map { a.getJSONObject(it) } }.orEmpty()

    /**
     * KI-25 pair 1b — puts [entries] into the stored list of [groupId], after those it holds, through the store's own
     * reader (`announceStateFromJson`); the rest of the state stays. On a build whose reader does not know the field
     * nothing is added, and the case's next look at [keptChanges] says so.
     */
    fun putKept(
        p: AdminCheckPhone,
        entries: List<JSONObject>,
        groupId: String = GID,
    ) {
        val json = JSONObject(announceStateToJson(p.base.state(groupId)))
        val list = json.optJSONArray(KEPT_KEY) ?: JSONArray()
        entries.forEach { list.put(it) }
        p.base.states[groupId] = announceStateFromJson(json.put(KEPT_KEY, list).toString())
    }

    /** One stored entry in the spec's JSON form (R19, "what is stored"). */
    @Suppress("LongParameterList")
    fun keptEntry(
        id: String,
        kind: String,
        epoch: Int,
        payload: String,
        from: String,
        at: Long,
        kept: Long,
        by: String = from,
        height: Long? = null,
        failedRuns: Int = 0,
        accepted: Boolean = false,
    ): JSONObject =
        JSONObject().apply {
            put("id", id)
            put("t", kind)
            put("e", epoch)
            put("p", payload)
            put("from", from)
            put("by", by)
            put("at", at)
            put("kept", kept)
            height?.let { put("h", it) }
            if (failedRuns != 0) put("f", failedRuns)
            if (accepted) put("acc", true)
        }

    /** The JSON key of the stored list in a group's announce state. */
    const val KEPT_KEY = "kept_admin_changes"

    /** Fails the calling test: the fix of pair 1b has not added [what] yet. */
    fun missingKept(what: String): Nothing = throw AssertionError("KI-25 pair 1b (ZC-704): no such operation: $what")

    /** The number of a pass [beginPass] returned. */
    fun passNumber(pass: Any): Long = pass as? Long ?: property(pass, "number") as Long

    /**
     * One on-chain group memo as the pass [pass] hands it over (`ChatViewModel.processGroupMessageInPass`): the memo
     * of a received transaction [txId] with block time [time] and mined height [minedHeight].
     */
    @Suppress("LongParameterList")
    fun receiveInPass(
        vm: ChatViewModel,
        pass: Any,
        memo: String,
        txId: TransactionId?,
        time: Instant?,
        minedHeight: Long?,
    ) {
        val method =
            method(ChatViewModel::class.java, "processGroupMessageInPass", 7)
                ?: missing(
                    "ChatViewModel.processGroupMessageInPass(memo, txId, timestamp, isOwnSend, ownRecipient, minedHeight, pass)"
                )
        invoke(method, vm, memo, txId, time, false, null, minedHeight, pass)
    }

    /**
     * Collects [vm]'s real `conversationRenderFlow` over its wallet history ([AdminCheckPhone.history], which
     * [chatVm] gave it) in [scope], on [scope]'s dispatcher (the wiring of ExpiredMessageLeavesOpenChatTest: the flow by
     * reflection, `renderDispatcher` on the test's virtual time). Each run of the flow is one history pass, and
     * [AdminCheckHistory.memoReads] grows by one entry per transaction it reads. The returned job is the collector: the
     * caller cancels and joins it before the case ends.
     *
     * Under `runTest`, run the passes with `runCurrent()`: `advanceUntilIdle()` does not run work that only a
     * `backgroundScope` collector is waiting for. Checked at `00f1a349b` on a healthy group: two view models over one
     * phone each made one pass when a transaction with a kick was added, and the kick was applied.
     */
    fun collectHistory(
        vm: ChatViewModel,
        scope: CoroutineScope,
        clock: () -> Long = { T + 86_400_000L },
        // KI-25 pair 1b: what the wallet says about its history scan (false: a restore or a rescan still reads it).
        scanComplete: Boolean = true,
    ): Job {
        val repository = field(ChatViewModel::class.java, "transactionRepository")!!.get(vm) as TransactionRepository

        @Suppress("UNCHECKED_CAST")
        val self = (field(ChatViewModel::class.java, "_currentUserAddress")!!.get(vm) as MutableStateFlow<String?>).value!!
        setIfUnset(vm, "hiddenMessages") { MutableStateFlow<Set<String>>(emptySet()) }
        setIfUnset(vm, "pendingMessages") { MutableStateFlow<List<ChatMessage>>(emptyList()) }
        setIfUnset(vm, "paidRequestIds") { MutableStateFlow<Set<String>>(emptySet()) }
        setIfUnset(vm, "restoreRecheck") { MutableStateFlow(0) }
        setIfUnset(vm, "peerStatuses") { MutableStateFlow<Map<String, Any>>(emptyMap()) }
        setIfUnset(vm, "decryptedTextCache") { ConcurrentHashMap<String, String>() }
        setIfUnset(vm, "decryptFailedPeers") { ConcurrentHashMap.newKeySet<String>() }
        setIfUnset(vm, "unsavedPaidMarks") { ConcurrentHashMap.newKeySet<String>() }
        setIfUnset(vm, "addressCache") { NoOpAddressCache.proxy }
        setIfUnset(vm, "_blockHeight") { MutableStateFlow<Long?>(null) }
        setField(vm, "synchronizerProvider", ScanPassHarness.synchronizerProvider(scannedSynchronizer()))
        setField(vm, "ttlClock", clock)
        setField(vm, "renderDispatcher", scope.coroutineContext[ContinuationInterceptor] as CoroutineDispatcher)
        val render =
            ChatViewModel::class.java.declaredMethods
                .firstOrNull { it.name == "conversationRenderFlow" && it.parameterCount == 2 }
                ?.apply { isAccessible = true }
                ?: error("fixture: ChatViewModel has no conversationRenderFlow(scannedTransactions, userAddress)")

        @Suppress("UNCHECKED_CAST")
        val flow = invoke(render, vm, repository.transactions.filterNotNull().map { it to scanComplete }, self) as Flow<List<Conversation>>
        return scope.launch { flow.collect { } }
    }

    private fun scannedSynchronizer(height: Long = ScanPassHarness.SCANNED): Synchronizer =
        Proxy.newProxyInstance(Synchronizer::class.java.classLoader, arrayOf(Synchronizer::class.java)) { p, method, args ->
            when (method.name) {
                "getFullyScannedHeight", "getNetworkHeight" -> MutableStateFlow(BlockHeight.new(height))
                "hashCode" -> System.identityHashCode(p)
                "equals" -> p === args?.get(0)
                "toString" -> "AdminCheckSynchronizer"
                else -> error("fixture: unexpected Synchronizer.${method.name}")
            }
        } as Synchronizer

    // ------------------------------------------------ relays, scopes and waiting

    /**
     * A relay publisher that records every publish in [published] (the memo to the recipient's relay key) and reports
     * [acks] acks. As a real publish does, it ends without sending when its sender was cancelled.
     */
    fun relayRecorder(
        published: MutableList<Pair<String, String>>,
        acks: Int = 1,
    ) = NostrChatBridge.registerPublisher { plaintext, recipient ->
        currentCoroutineContext().ensureActive()
        published += plaintext to recipient
        NostrChatBridge.PublishResult(acks = acks, messageId = "synthetic-relay-event-${published.size}")
    }

    /**
     * Makes the process-wide send scope (`ChatViewModel.processSendScope()`, the companion's `heldGroupAcceptScope`) a
     * scope on [dispatcher] that the test owns: what is launched there waits for the test's scheduler, while the view
     * model's own scope runs on the test's Main dispatcher. Call it after the view models are built (the harness gives
     * each new view model its own stand-in) and cancel the returned scope before the case ends.
     */
    fun sendScopeOn(dispatcher: CoroutineDispatcher): CoroutineScope {
        val holder = staticField("heldGroupAcceptScope") ?: error("fixture: ChatViewModel has no process-wide send scope")
        return CoroutineScope(SupervisorJob() + dispatcher).also { holder.set(null, it) }
    }

    /**
     * Waits until every job in [vm]'s own scope has ended (finished or cancelled); the process-wide send scope is not
     * waited for. The limit runs in real time, so it also holds under `runTest`.
     */
    suspend fun awaitOwnJobs(vm: ChatViewModel) {
        val jobs =
            vm.viewModelScope.coroutineContext.job.children
                .toList()
        withContext(Dispatchers.Default) { withTimeout(HandshakeHarness.TIMEOUT_MS) { jobs.forEach { it.join() } } }
    }

    /** [confirmationOutcome] once there is one; the wait runs in real time, so it also holds under `runTest`. */
    suspend fun awaitOutcome(vm: ChatViewModel): String {
        withContext(Dispatchers.Default) {
            withTimeout(HandshakeHarness.TIMEOUT_MS) { while (confirmationOutcome(vm) == null) delay(5) }
        }
        return confirmationOutcome(vm)!!
    }

    /** Gives [vm] its own window on its wallet history ([AdminCheckHistoryView]); call it before [collectHistory]. */
    fun ownHistory(vm: ChatViewModel): AdminCheckHistoryView {
        val holder = field(ChatViewModel::class.java, "transactionRepository")!!
        return AdminCheckHistoryView(holder.get(vm) as TransactionRepository).also { holder.set(vm, it) }
    }

    // ------------------------------------------------------------ the key request

    /**
     * The quoted key sync as the Pay tap runs it (`GroupViewModel.requestGroupKeySync(groupId, quoted)`, private), with
     * a plan that names [recipients], of which [paid] were quoted as on-chain messages. Both exist at `00f1a349b`.
     */
    suspend fun requestKeySyncQuoted(
        vm: GroupViewModel,
        f: GroupPaidOnceFixture,
        recipients: List<String>,
        paid: Set<String>,
        groupId: String = GID,
    ) {
        val quotedType =
            GroupViewModel::class.java.declaredClasses.firstOrNull { it.simpleName == "QuotedFanOut" }
                ?: missing("GroupViewModel.QuotedFanOut(recipients: List<String>, paidRecipients: Set<String>)")
        val quoted =
            quotedType.declaredConstructors
                .firstOrNull { it.parameterTypes.contentEquals(arrayOf(List::class.java, Set::class.java)) }
                ?.apply { isAccessible = true }
                ?.newInstance(recipients, paid)
                ?: missing("GroupViewModel.QuotedFanOut(recipients: List<String>, paidRecipients: Set<String>)")
        val method =
            GroupViewModel::class.java.declaredMethods
                .filter { !it.isSynthetic && (it.name == "requestGroupKeySync" || it.name.startsWith("requestGroupKeySync$")) }
                .firstOrNull { it.parameterTypes.contentEquals(arrayOf(String::class.java, quotedType)) }
                ?.apply { isAccessible = true }
                ?: missing("GroupViewModel.requestGroupKeySync(groupId: String, quoted: QuotedFanOut)")
        f.awaitNewJobs(vm) { invoke(method, vm, groupId, quoted) }
    }

    // ---------------------------------------------------------------- reflection

    /** Fails the calling test: the fix has not added [what] yet. */
    fun missing(what: String): Nothing = throw AssertionError("KI-25 pair 1 (ZC-022): no such operation: $what")

    /** The function [name] of [type] with [parameters] parameters (a suspend function's continuation not counted). */
    private fun method(
        type: Class<*>,
        name: String,
        parameters: Int,
    ): Method? =
        generateSequence(type) { it.superclass }
            .flatMap { it.declaredMethods.asSequence() }
            .filter { !it.isSynthetic && !Modifier.isStatic(it.modifiers) }
            .filter { it.name == name || it.name.startsWith("$name$") }
            .firstOrNull { it.parameterCount - (if (isSuspend(it)) 1 else 0) == parameters }
            ?.apply { isAccessible = true }

    private fun isSuspend(method: Method): Boolean = method.parameterTypes.lastOrNull() == Continuation::class.java

    private fun invoke(
        method: Method,
        target: Any,
        vararg args: Any?,
    ): Any? =
        try {
            method.invoke(target, *args)
        } catch (e: InvocationTargetException) {
            throw e.targetException
        }

    private suspend fun invokeMaybeSuspend(
        method: Method,
        target: Any,
        vararg args: Any?,
    ): Any? =
        if (isSuspend(method)) {
            suspendCoroutineUninterceptedOrReturn<Any?> { continuation -> invoke(method, target, *args, continuation) }
        } else {
            invoke(method, target, *args)
        }

    private fun field(
        type: Class<*>,
        name: String,
    ): Field? =
        generateSequence(type) { it.superclass }
            .flatMap { it.declaredFields.asSequence() }
            .firstOrNull { it.name == name }
            ?.apply { isAccessible = true }

    private fun staticField(name: String): Field? =
        ChatViewModel::class.java.declaredFields
            .firstOrNull { it.name == name && Modifier.isStatic(it.modifiers) }
            ?.apply { isAccessible = true }

    /** The StateFlow a view model shows as [name] (its getter, else the field of that name, else `_name`). */
    private fun stateFlow(
        vm: Any,
        name: String,
    ): StateFlow<Any?>? {
        val getter = "get" + name.replaceFirstChar { it.uppercase() }
        val value =
            vm.javaClass.methods
                .firstOrNull { (it.name == getter || it.name.startsWith("$getter$")) && it.parameterCount == 0 }
                ?.let { invoke(it.apply { isAccessible = true }, vm) }
                ?: (field(vm.javaClass, name) ?: field(vm.javaClass, "_$name"))?.get(vm)
        @Suppress("UNCHECKED_CAST")
        return value as? StateFlow<Any?>
    }

    private fun constantName(
        value: Any?,
        operation: String,
    ): String = (value as? Enum<*>)?.name ?: throw AssertionError("$operation answered $value, which is not an enum constant")

    private fun setField(
        target: Any,
        name: String,
        value: Any,
    ) {
        (field(target.javaClass, name) ?: error("fixture: ${target.javaClass.simpleName} has no field $name")).set(target, value)
    }

    /** Gives a field its initialiser's value when the constructor-free allocation left it null. */
    private fun setIfUnset(
        vm: ChatViewModel,
        name: String,
        value: () -> Any,
    ) {
        val field = field(ChatViewModel::class.java, name) ?: return
        if (Modifier.isStatic(field.modifiers) || field.get(vm) != null) return
        field.set(vm, value())
    }
}

/**
 * KI-25 pair 1 (ZC-022) — the main sources as the source scans of the admin-check tests read them (the pattern of
 * [PaidSendCallSiteLockTest]): a member function's code without comments, and the block a call's trailing lambda
 * spans. A function the fix has not added yet fails the calling test with "no such operation".
 */
internal object AdminCheckSource {
    private const val CHAT = "co/electriccoin/zcash/ui/screen/chat"
    private const val MODIFIERS = "private|internal|public|protected|override|suspend|inline|operator"

    /** The group's pass number is recorded: `adminConfirmedAtPass[…] = …` or `adminConfirmedAtPass.put(…)`. */
    val NUMBER_RECORDED = Regex("""adminConfirmedAtPass\s*(?:\[[^\]]*]\s*=[^=]|\.\s*(?:put|set|merge|compute)\s*\()""")

    /** The pass signal is raised: `historyPassSignal.update { … }`, or an assignment to (or `++` on) its `value`. */
    val SIGNAL_RAISED =
        Regex("""historyPassSignal\s*\.\s*(?:update\s*\{|updateAndGet\s*\{|getAndUpdate\s*\{|value\s*(?:\+\+|\+=|=[^=]))""")

    /** The guard's test (chosen name, see [AdminCheck]). */
    const val GUARD = "passMayApplyAdminChange("

    // KI-25 pair 1b (ZC-704, R19): the chosen names of the new ChatViewModel members (see [AdminCheck]).

    /** The hook at the end of a history pass. */
    const val HOOK = "endHistoryPass"

    /** The run of a group's stored changes. */
    const val RUN = "runKeptAdminChanges"

    /** The keep step of the dispatcher's two admin branches. */
    const val KEEP = "keepRelayAdminChange"

    /** The keep of a re-invite. */
    const val KEEP_INVITE = "keepRelayReinvite"

    /** The hand-over of one group's kept invites to ZC-294's slot. */
    const val HAND_OVER = "handOverKeptReinvites"

    /** What a history pass runs before its loop. */
    const val HAND_OVER_BEFORE_LOOP = "handOverKeptReinvitesBeforeLoop"

    /** Where [name] is called in [code] (the index of each call; a declaration `fun name(` is not a call). */
    fun callsOf(
        code: String,
        name: String,
    ): List<Int> =
        Regex("""(?<!fun )\b${Regex.escape(name)}\s*\(""")
            .findAll(code)
            .map { it.range.first }
            .toList()

    /** Per main source file, how often [name] is called there (files with no call left out). */
    fun callSites(name: String): Map<String, Int> =
        mainSources().mapValues { (_, code) -> callsOf(code, name).size }.filterValues { it > 0 }

    /** The code (comments left out) of the member function [name] of the main source file [file] of the chat screens. */
    fun memberOf(
        file: String,
        name: String,
    ): String {
        val lines = File(mainJava, "$CHAT/$file").readLines()
        val declaration = Regex("^ {4}(?:(?:$MODIFIERS)\\s+)*fun\\s+(?:<[^>]*>\\s*)?$name\\s*\\(")
        val starts = lines.indices.filter { declaration.containsMatchIn(lines[it]) }
        if (starts.isEmpty()) AdminCheck.missingKept("${file.substringAfterLast('/')}: no member function $name")
        return starts.joinToString("\n") { start ->
            val end = (start + 1 until lines.size).firstOrNull { nextMember.containsMatchIn(lines[it]) } ?: lines.size
            code(lines.subList(start, end))
        }
    }

    /** The next thing declared at class level: a KDoc, an annotation or a declaration at exactly four spaces. */
    private val nextMember =
        Regex("^ {4}(?:/\\*\\*|@|(?:(?:$MODIFIERS|data|sealed|enum|const|lateinit|companion)\\s+)*(?:fun|val|var|class|object)\\b)")
    private val memberFun = Regex("^ {4}(?:(?:$MODIFIERS)\\s+)*fun\\s")

    val mainJava: File by lazy {
        val start = File(System.getProperty("user.dir") ?: ".").absoluteFile
        generateSequence(start) { it.parentFile }
            .take(8)
            .flatMap { sequenceOf(it, it.resolve("ui-lib")) }
            .map { it.resolve("src/main/java") }
            .firstOrNull { it.resolve(CHAT).isDirectory }
            ?: throw AssertionError("could not locate ui-lib/src/main/java from ${start.path}")
    }

    private val chatViewModelLines: List<String> by lazy { File(mainJava, "$CHAT/viewmodel/ChatViewModel.kt").readLines() }

    /** The code (comments left out) of the ChatViewModel member function [name], declaration to the next member. */
    fun chatMember(name: String): String {
        val declaration = Regex("^ {4}(?:(?:$MODIFIERS)\\s+)*fun\\s+(?:<[^>]*>\\s*)?$name\\s*\\(")
        val starts = chatViewModelLines.indices.filter { declaration.containsMatchIn(chatViewModelLines[it]) }
        if (starts.isEmpty()) AdminCheck.missing("ChatViewModel.$name (no such member function in ChatViewModel.kt)")
        return starts.joinToString("\n") { start -> code(chatViewModelLines.subList(start, memberEnd(start))) }
    }

    /** The code of the ChatViewModel member function in which [marker] first stands (the dispatcher, by a branch of it). */
    fun chatMemberContaining(marker: Regex): String {
        val at =
            chatViewModelLines.indices.firstOrNull { marker.containsMatchIn(chatViewModelLines[it]) }
                ?: throw AssertionError("ChatViewModel.kt holds no ${marker.pattern}")
        val start =
            (at downTo 0).firstOrNull { memberFun.containsMatchIn(chatViewModelLines[it]) }
                ?: throw AssertionError("no member function around ${marker.pattern}")
        return code(chatViewModelLines.subList(start, memberEnd(start)))
    }

    /** Every main source file's code (comments left out), by file name. */
    fun mainSources(): Map<String, String> =
        mainJava
            .walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .associate { it.name to code(it.readLines()) }

    private fun memberEnd(start: Int): Int =
        (start + 1 until chatViewModelLines.size).firstOrNull { nextMember.containsMatchIn(chatViewModelLines[it]) }
            ?: chatViewModelLines.size

    /** [lines] without comment lines and trailing line comments. */
    fun code(lines: List<String>): String =
        lines
            .filterNot { line -> line.trim().let { it.startsWith("//") || it.startsWith("*") || it.startsWith("/*") } }
            .joinToString("\n") { it.substringBefore("//") }

    /**
     * [code] with the inside of every string and character literal blanked (same length, same line breaks), so that
     * brackets and names in a log text are not taken for code.
     */
    @Suppress("CyclomaticComplexMethod", "NestedBlockDepth")
    fun structure(code: String): String {
        val out = StringBuilder(code)

        fun blank(
            from: Int,
            until: Int,
        ) {
            for (k in from until minOf(until, code.length)) if (code[k] != '\n') out[k] = ' '
        }
        var i = 0
        while (i < code.length) {
            val c = code[i]
            if (c == '\'' && code.getOrNull(i + 2) == '\'') {
                blank(i + 1, i + 2)
                i += 3
            } else if (c == '\'' && code.getOrNull(i + 1) == '\\' && code.getOrNull(i + 3) == '\'') {
                blank(i + 1, i + 3)
                i += 4
            } else if (c == '"') {
                val quote = if (code.startsWith("\"\"\"", i)) "\"\"\"" else "\""
                var j = i + quote.length
                while (j < code.length && !code.startsWith(quote, j)) {
                    if (quote.length == 1 && code[j] == '\\') {
                        j += 2
                    } else if (code.startsWith("\${", j)) {
                        // A template expression: from its opening brace to the closing one, over the strings inside it.
                        j++
                        var depth = 0
                        do {
                            when (code[j]) {
                                '{' -> depth++
                                '}' -> depth--
                                '"' -> j = code.indexOf('"', j + 1).let { if (it < 0) code.length - 1 else it }
                            }
                            j++
                        } while (j < code.length && depth > 0)
                    } else {
                        j++
                    }
                }
                blank(i + quote.length, j)
                i = j + quote.length
            } else {
                i++
            }
        }
        return out.toString()
    }

    /** In [structure], the index of the bracket that closes the one at [open], or null. */
    fun closing(
        structure: String,
        open: Int,
    ): Int? {
        val opener = structure[open]
        val closer =
            when (opener) {
                '(' -> ')'
                '{' -> '}'
                else -> return null
            }
        var depth = 0
        for (k in open until structure.length) {
            if (structure[k] == opener) depth++
            if (structure[k] == closer && --depth == 0) return k
        }
        return null
    }

    /**
     * In [structure], the block (`{` to `}`) of the trailing lambda of the call that begins at [call] (`name(args) { … }`,
     * or `name { … }`), or null when the call has none.
     */
    fun lambdaOf(
        structure: String,
        call: Int,
    ): IntRange? {
        var k = call
        while (k < structure.length && structure[k] != '(' && structure[k] != '{' && structure[k] != '\n') k++
        if (structure.getOrNull(k) == '(') {
            k = (closing(structure, k) ?: return null) + 1
            while (k < structure.length && structure[k].isWhitespace()) k++
        }
        if (structure.getOrNull(k) != '{') return null
        return closing(structure, k)?.let { k..it }
    }
}
