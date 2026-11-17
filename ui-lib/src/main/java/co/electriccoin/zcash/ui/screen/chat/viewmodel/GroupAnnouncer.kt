package co.electriccoin.zcash.ui.screen.chat.viewmodel

import android.util.Log
import co.electriccoin.zcash.ui.common.util.redactAddress
import co.electriccoin.zcash.ui.nostr.NostrChatBridge
import co.electriccoin.zcash.ui.screen.chat.crypto.E2EEncryption
import co.electriccoin.zcash.ui.screen.chat.crypto.E2EKeyVersion
import co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferences
import co.electriccoin.zcash.ui.screen.chat.model.ActivatedBy
import co.electriccoin.zcash.ui.screen.chat.model.AnnounceLedgerEntry
import co.electriccoin.zcash.ui.screen.chat.model.AnnounceLedgerState
import co.electriccoin.zcash.ui.screen.chat.model.AnnounceOp
import co.electriccoin.zcash.ui.screen.chat.model.AnnouncePair
import co.electriccoin.zcash.ui.screen.chat.model.AnnounceState
import co.electriccoin.zcash.ui.screen.chat.model.GroupInfo
import co.electriccoin.zcash.ui.screen.chat.model.GroupMember
import co.electriccoin.zcash.ui.screen.chat.model.MemberAnnounceEntry
import co.electriccoin.zcash.ui.screen.chat.model.MemberStatus
import co.electriccoin.zcash.ui.screen.chat.model.ZMSGGroupProtocol
import co.electriccoin.zcash.ui.screen.chat.model.announceKeyHash
import co.electriccoin.zcash.ui.screen.chat.model.announceLedgerEntryFor
import co.electriccoin.zcash.ui.screen.chat.model.announcePairKey
import co.electriccoin.zcash.ui.screen.chat.model.assignAnnounceNumbers
import co.electriccoin.zcash.ui.screen.chat.model.owedMemberAnnounces
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

/**
 * Round 8 MA3 (MEMBER_ANNOUNCE_DESIGN.md v2 §4, §6.1) — the announcer's process-wide pass state, shared by every
 * ChatViewModel and GroupViewModel: at most one relay pass per group at a time (a pass asked for while one runs runs
 * once more after it), the GQ / GR answer rate limit, and the on-chain announce payments in flight.
 */
internal object GroupAnnounceRuntime {
    private const val ROSTER_QUERY_ANSWER_INTERVAL_MS = 10 * 60 * 1000L

    /** The spacing between two relay sends of one pass (design §4, review L5): a random 1-20 s. */
    @Volatile var spacingMs: () -> Long = ::defaultSpacingMs

    fun defaultSpacingMs(): Long = Random.nextLong(1_000L, 20_001L)

    // groupId -> the rerun asked for while its pass runs: 0 none, 1 a pass, 2 a pass that also re-sends every R.
    private val running = HashMap<String, Int>()
    private val answered = ConcurrentHashMap<String, Long>()

    /** "groupId|rAddr|sAddr|op" of the on-chain announce payments running now (see settleStrandedPayments). */
    val paying: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** The passes waiting for the relays to be ready (one per GroupAnnouncer at most). */
    val relayWaiters: MutableSet<kotlinx.coroutines.Job> = ConcurrentHashMap.newKeySet()

    /**
     * Round 10b R10B-2 (F-R9B-1): "groupId|rAddr|sAddr|op" of the announces whose last relay send was tried and not
     * acked, set by the send and cleared by an ack or a ledger write. In memory, for the screen only: the group screen
     * says a member "couldn't be reached" only after a try, never for a pair no pass has reached yet.
     */
    val relayNotAcked: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** True when the caller may start [groupId]'s pass now; false when one runs (it then runs once more after it). */
    fun tryStart(
        groupId: String,
        resendR: Boolean
    ): Boolean =
        synchronized(running) {
            val current = running[groupId]
            if (current == null) {
                running[groupId] = 0
                true
            } else {
                running[groupId] = maxOf(current, if (resendR) 2 else 1)
                false
            }
        }

    /** The pass of [groupId] ended: null when nothing more is asked (it is no longer running), else the rerun's R flag. */
    fun finishOrRerun(groupId: String): Boolean? =
        synchronized(running) {
            when (val next = running[groupId]) {
                null, 0 -> {
                    running.remove(groupId)
                    null
                }
                else -> {
                    running[groupId] = 0
                    next == 2
                }
            }
        }

    /** The pass of [groupId] stopped early (failure or cancellation): it no longer runs. */
    fun abandon(groupId: String) {
        synchronized(running) { running.remove(groupId) }
    }

    /** One GQ / GR re-send per (requester, group) per 10 minutes (design §4 T5, review F11). */
    fun mayAnswer(
        groupId: String,
        requester: String,
        nowMs: Long
    ): Boolean {
        val key = "$groupId|$requester"
        synchronized(answered) {
            val last = answered[key]
            if (last != null && nowMs - last < ROSTER_QUERY_ANSWER_INTERVAL_MS) return false
            answered[key] = nowMs
            return true
        }
    }

    /** For tests: forget every pass, answer and payment, and send a pass's relay copies with no spacing. */
    fun resetForTests() {
        synchronized(running) { running.clear() }
        answered.clear()
        paying.clear()
        relayNotAcked.clear()
        relayWaiters.toList().forEach { it.cancel() }
        relayWaiters.clear()
        spacingMs = { 0L }
    }
}

/**
 * Round 8 MA3 (MEMBER_ANNOUNCE_DESIGN.md v2 §3, §4, §6) — the announcer's side of the member announce (GP), used by
 * ChatViewModel (T1 / T2 / T5, the rebuilt ledger) and GroupViewModel (T4, the introduce tap, the removal).
 *
 * [relaySend] is the ViewModel's free relay send (trySendGroupMemoOverNostr: true only on a relay ack); [relayRoute] its
 * rep-tolerant NOSTR-key lookup (findPeerNostrPubkeyAnyRep). Nothing here spends: a pass sends only through
 * [relaySend], never through an on-chain path. On-chain copies are paid by GroupViewModel, by a tap, under a quote.
 *
 * Every roster, announce-state, counter and ledger read-modify-write runs under the group's process-wide lock
 * ([ZchatPreferences.groupLock]); no relay send and no suspend call happens under it.
 */
internal class GroupAnnouncer(
    private val prefs: ZchatPreferences,
    private val relaySend: suspend (address: String, memo: String) -> Boolean,
    private val relayRoute: (address: String) -> String?,
) {
    private val waitingForRelays: MutableSet<String> = ConcurrentHashMap.newKeySet()

    @Volatile private var relayWaiter: kotlinx.coroutines.Job? = null

    private inline fun <T> locked(
        groupId: String,
        block: () -> T
    ): T = synchronized(ZchatPreferences.groupLock(groupId)) { block() }

    fun info(groupId: String): GroupInfo? =
        runCatching { prefs.getGroupInfo(groupId)?.let { ZMSGGroupProtocol.deserializeGroupInfo(it) } }.getOrNull()

    fun roster(groupId: String): List<GroupMember> =
        runCatching { prefs.getGroupMembers(groupId)?.let { ZMSGGroupProtocol.deserializeGroupMembers(it) } }.getOrNull().orEmpty()

    fun state(groupId: String): AnnounceState =
        runCatching { prefs.getGroupAnnounceState(groupId) as Any? }.getOrNull() as? AnnounceState ?: AnnounceState()

    fun ledger(groupId: String): List<AnnounceLedgerEntry> =
        (runCatching { prefs.getGroupAnnounceLedger(groupId) as Any? }.getOrNull() as? List<*>)
            ?.filterIsInstance<AnnounceLedgerEntry>()
            .orEmpty()

    fun entryFor(
        groupId: String,
        pair: AnnouncePair
    ): AnnounceLedgerEntry? = announceLedgerEntryFor(ledger(groupId), pair)

    /** The compare-and-set of one ledger pair (commit()); false when refused or the store cannot write it. */
    fun cas(
        groupId: String,
        pair: AnnouncePair,
        expected: AnnounceLedgerEntry?,
        new: AnnounceLedgerEntry?,
    ): Boolean =
        runCatching { prefs.casGroupAnnounce(groupId, pair, expected, new) }
            .getOrDefault(false)
            .also { written -> if (written) GroupAnnounceRuntime.relayNotAcked -= relayTryKey(groupId, pair) }

    /** Round 10b R10B-2 (F-R9B-1): a relay send of [pair] was tried and not acked since its last ack or ledger write. */
    fun relayTriedNotAcked(
        groupId: String,
        pair: AnnouncePair
    ): Boolean = relayTryKey(groupId, pair) in GroupAnnounceRuntime.relayNotAcked

    private fun relayTryKey(
        groupId: String,
        pair: AnnouncePair
    ): String = "$groupId|${announcePairKey(pair)}"

    /** Design §3: the one announcer predicate (GroupViewModel.isGroupAdmin, ChatViewModel.isGroupAnnouncer). */
    fun isAnnouncer(
        groupId: String,
        info: GroupInfo
    ): Boolean = prefs.isGroupSelfCreated(groupId) || prefs.isSelfAddress(info.creatorAddress)

    fun canon(address: String): String = prefs.resolvePeerAddress(address)

    /** The E2E key we hold for [address] (or for its canonical form). */
    fun keyOf(address: String): String? = prefs.getE2EPeerPublicKey(address) ?: prefs.getE2EPeerPublicKey(canon(address))

    fun keyHash(address: String): String? = keyOf(address)?.let { announceKeyHash(it) }

    /**
     * Design §3 / review L1 — a secure link with [address]: a session key can be derived, no first-contact key exchange
     * waits for Accept, and the user was not warned that its key changed.
     */
    fun hasSecureLink(address: String): Boolean {
        val ourPrivate = prefs.getE2EPrivateKey(address) ?: return false
        val peerPublic = keyOf(address) ?: return false
        val session =
            runCatching {
                E2EEncryption.deriveSharedSecret(ourPrivate, peerPublic, E2EKeyVersion.fromValue(prefs.getE2EKeyVersion(address)))
            }.getOrNull()
        return session != null &&
            runCatching { prefs.getInboundKexAwaitingAccept(address) }.getOrNull() == null &&
            !prefs.isE2EKeyChanged(address)
    }

    /**
     * Design §3 — a member row the announcer may tell and announce: joined through an authenticated step ([activation]
     * is AUTH, which covers a row from before the member announce only with a proof, see [backfilled]), with a secure
     * link and a stored key. The caller checks ACTIVE, not the admin, not us.
     */
    fun isEligible(
        state: AnnounceState,
        row: GroupMember,
    ): Boolean =
        activation(state, row) == ActivatedBy.AUTH &&
            hasSecureLink(row.address) &&
            keyOf(row.address) != null

    /**
     * How [row] counts as activated for the member announce: the record an activation step wrote, or, for a row the
     * backfill judges ([judgedByBackfill]), the backfill's verdict ([backfillVerdict]) now.
     */
    private fun activation(
        state: AnnounceState,
        row: GroupMember,
    ): ActivatedBy? {
        val entry = state.members[canon(row.address)]
        return if (judgedByBackfill(entry)) backfillVerdict(row, entry) else entry?.activatedBy
    }

    /** True once [address] (its canonical form or its relay key) advertised the gp1 capability. */
    fun isGp1(address: String): Boolean =
        runCatching { prefs.isGp1Capable(canon(address)) || relayRoute(address)?.let { prefs.isGp1Capable(it) } == true }
            .getOrDefault(false)

    /**
     * Design §8 (VERIFIED_MA_FINDINGS #1) — a record the backfill judges: none yet (a row from before the member
     * announce), or the backfill's own UNAUTH verdict, which is judged again at each pass so a proof that arrives later
     * (the scan replays our own invite, a secure link completes) counts. Every activation step records its time and the
     * backfill never does, so an UNAUTH with no activation time is the backfill's.
     */
    private fun judgedByBackfill(entry: MemberAnnounceEntry?): Boolean =
        entry?.activatedBy == null || (entry.activatedBy == ActivatedBy.UNAUTH && entry.activatedAtMs == 0L)

    /**
     * Design §8 (VERIFIED_MA_FINDINGS #1) — a row from before the member announce counts as joined through an
     * authenticated step only with a secure link AND a proof that it joined through us: we invited it (its row's invite
     * status, or our own sent invite seen by the scan: [MemberAnnounceEntry.invitedByUs]), or its address is the one a
     * verified signed accept declared (an alias target). A secure link alone proves nothing: a 2.12.4 creator added the
     * claimed sender of any group message as ACTIVE, self-asserted on-chain when unsigned. Anything else is UNAUTH (the
     * row note tells the admin); a group message from it whose author verifies later makes it AUTH (ChatViewModel).
     */
    private fun backfillVerdict(
        row: GroupMember,
        entry: MemberAnnounceEntry?,
    ): ActivatedBy {
        val proof =
            row.inviteStatus != null ||
                entry?.invitedByUs == true ||
                runCatching { prefs.isPeerAddressAliasTarget(canon(row.address)) }.getOrDefault(false)
        return if (proof && hasSecureLink(row.address)) ActivatedBy.AUTH else ActivatedBy.UNAUTH
    }

    /**
     * Design §8 — the ACTIVE member rows the backfill judges ([judgedByBackfill]) get its verdict ([backfillVerdict]).
     * The record is written by [number]. A row made ACTIVE by an activation step since MA2 keeps that step's record.
     */
    private fun backfilled(
        state: AnnounceState,
        roster: List<GroupMember>,
    ): AnnounceState {
        var members = state.members
        for (m in roster) {
            if (m.status != MemberStatus.ACTIVE || m.isAdmin || prefs.isSelfAddress(m.address)) continue
            val c = canon(m.address)
            val entry = members[c]
            if (!judgedByBackfill(entry)) continue
            val verdict = backfillVerdict(m, entry)
            if (entry?.activatedBy == verdict) continue
            members = members + (c to (entry ?: MemberAnnounceEntry()).copy(activatedBy = verdict))
        }
        return if (members == state.members) state else state.copy(members = members)
    }

    /**
     * The numbering step of a pass (design §4 step 1): [assignAnnounceNumbers] over the roster, the state and the
     * counter, saved (commit()) under the group lock before anything is sent. @return false when [groupId] is not ours
     * to announce or not active.
     */
    fun number(groupId: String): Boolean =
        locked(groupId) {
            val info = info(groupId) ?: return@locked false
            if (!info.isActive || !isAnnouncer(groupId, info)) return@locked false
            val roster = roster(groupId)
            val stored = state(groupId)
            val state = backfilled(stored, roster)
            val counter = runCatching { prefs.getGroupAnnounceCounter(groupId) }.getOrDefault(0L)
            val numbering =
                assignAnnounceNumbers(
                    groupId = groupId,
                    state = state,
                    roster = roster,
                    counter = counter,
                    nowMs = System.currentTimeMillis(),
                    highestOwnSentN = counter,
                    epoch = prefs.getGroupKeyEpoch(groupId),
                    canon = ::canon,
                    isSelf = prefs::isSelfAddress,
                    keyOf = ::keyOf,
                    hasSecureLink = ::hasSecureLink,
                )
            if (numbering.counter > counter &&
                !runCatching { prefs.raiseGroupAnnounceCounter(groupId, numbering.counter) }.getOrDefault(false)
            ) {
                Log.w(TAG, "Announce numbers of $groupId not saved: the counter did not reach the disk")
                return@locked true
            }
            if (numbering.state != stored && !runCatching { prefs.saveGroupAnnounceState(groupId, numbering.state) }.getOrDefault(false)) {
                Log.w(TAG, "Announce numbers of $groupId not saved: the state did not reach the disk")
            }
            true
        }

    /** Every current (recipient, subject) pair with its op, owed or not (the T5 re-send, the R re-send, the kick's quote). */
    fun allPairs(groupId: String): List<AnnouncePair> = pairs(groupId, emptyList())

    /** The pairs that still owe the subject's current announce (design §6.0 debt rule). */
    fun owedPairs(groupId: String): List<AnnouncePair> = pairs(groupId, ledger(groupId))

    private fun pairs(
        groupId: String,
        ledger: List<AnnounceLedgerEntry>
    ): List<AnnouncePair> =
        owedMemberAnnounces(
            state = state(groupId),
            roster = roster(groupId),
            ledger = ledger,
            canon = ::canon,
            isSelf = prefs::isSelfAddress,
            keyOf = ::keyOf,
            hasSecureLink = ::hasSecureLink,
        )

    /**
     * The GP memo telling [pair]'s recipient about its subject, signed for that recipient with our per-peer key (#187),
     * at the key epoch we hold now. Null when we hold no key for the recipient, or the memo would not fit 512 bytes.
     */
    fun memo(
        groupId: String,
        pair: AnnouncePair
    ): String? {
        val ourPrivate = prefs.getE2EPrivateKey(pair.recipient) ?: prefs.getE2EPrivateKey(pair.rAddr) ?: return null
        val epoch = prefs.getGroupKeyEpoch(groupId)
        val signedData = ZMSGGroupProtocol.groupMemberAnnounceSignedData(groupId, pair.op, pair.subject, pair.n, epoch, null)
        val signature = runCatching { E2EEncryption.sign(ourPrivate, signedData) }.getOrNull() ?: return null
        return ZMSGGroupProtocol.createGroupMemberAnnounceMessage(groupId, pair.op, pair.subject, pair.n, epoch, signature)
    }

    /** A ledger entry for [pair] in [state] at [at]. */
    fun entry(
        pair: AnnouncePair,
        state: AnnounceLedgerState,
        at: Long = System.currentTimeMillis(),
        txids: List<String> = emptyList(),
    ): AnnounceLedgerEntry = AnnounceLedgerEntry(pair.rKey, pair.rAddr, pair.sKey, pair.sAddr, pair.op, state, txids, at)

    /**
     * Ask for a relay pass of [groupId] (design §4): at most one per group at a time; one asked for while another runs
     * runs once after it. [resendR] also re-sends every current R free (T4). [force] runs it on a group this phone
     * rebuilt, which otherwise never announces by itself (the introduce tap, the removal: §4.1). [onDone] runs after it.
     * Launched in [scope] (the ViewModel's), never under a lock.
     */
    fun requestPass(
        scope: CoroutineScope,
        groupId: String,
        resendR: Boolean = false,
        force: Boolean = false,
        onDone: () -> Unit = {},
    ) {
        if (!GroupAnnounceRuntime.tryStart(groupId, resendR)) return
        scope.launch {
            var rerun: Boolean? = resendR
            try {
                while (rerun != null) {
                    try {
                        relayPass(scope, groupId, resendR = rerun, force = force)
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        Log.e(TAG, "Announce pass of $groupId failed (${e.javaClass.simpleName})")
                    }
                    rerun = GroupAnnounceRuntime.finishOrRerun(groupId)
                }
            } finally {
                if (rerun != null) GroupAnnounceRuntime.abandon(groupId)
            }
            onDone()
        }
    }

    /**
     * One pass (design §4, §6.1): number, then send every owed pair whose recipient we can reach over the relays, only
     * through [relaySend], spaced by a random 1-20 s. A pair not yet sent, one whose op changed, and a RELAY_UNCONFIRMED
     * one after a day or once its recipient advertised gp1 are sent; a relay ack is RELAY_FINAL for a gp1 recipient,
     * RELAY_UNCONFIRMED otherwise. Pairs with no relay route wait for the introduce tap. With the relays not ready the
     * pass waits and runs again when they are.
     */
    private suspend fun relayPass(
        scope: CoroutineScope,
        groupId: String,
        resendR: Boolean,
        force: Boolean,
    ) {
        val info = info(groupId) ?: return
        if (!info.isActive || !isAnnouncer(groupId, info)) return
        if (info.announcerRebuilt && !force) {
            Log.d(TAG, "Announce pass of $groupId skipped: this phone rebuilt the group (only the introduce tap announces)")
            return
        }
        if (!number(groupId)) return
        if (!NostrChatBridge.isOutboundReady()) {
            waitForRelays(scope, groupId)
            return
        }
        val now = System.currentTimeMillis()
        val ledger = ledger(groupId)
        val sends = mutableListOf<Pair<AnnouncePair, Boolean>>()
        for (pair in owedPairs(groupId)) {
            if (relayRoute(pair.recipient) == null) continue
            val current = announceLedgerEntryFor(ledger, pair)
            val due =
                current == null ||
                    current.op != pair.op ||
                    current.state != AnnounceLedgerState.RELAY_UNCONFIRMED ||
                    now - current.at >= RELAY_RESEND_INTERVAL_MS ||
                    isGp1(pair.recipient)
            if (due) sends += pair to true
        }
        if (resendR) {
            for (pair in allPairs(groupId)) {
                if (pair.op != AnnounceOp.R || relayRoute(pair.recipient) == null) continue
                if (sends.none { it.first.rAddr == pair.rAddr && it.first.sAddr == pair.sAddr }) sends += pair to false
            }
        }
        sendOverRelays(groupId, sends)
    }

    /**
     * Design §4 T5 — re-send [requester] the current announce of every subject, over the relays only, with the ledger
     * untouched: the answer to an authorized GR, or to a GQ from an ACTIVE member. Nothing on a group this phone
     * rebuilt, and nothing when the relays are not ready.
     */
    suspend fun resendTo(
        groupId: String,
        requester: String,
    ) {
        val info = info(groupId) ?: return
        if (!info.isActive || !isAnnouncer(groupId, info) || info.announcerRebuilt) return
        if (!NostrChatBridge.isOutboundReady() || relayRoute(requester) == null) return
        val requesterCanon = canon(requester)
        val requesterKey = keyHash(requester)
        val pairs = allPairs(groupId).filter { it.rAddr == requesterCanon || (requesterKey != null && it.rKey == requesterKey) }
        sendOverRelays(groupId, pairs.map { it to false })
    }

    private suspend fun sendOverRelays(
        groupId: String,
        sends: List<Pair<AnnouncePair, Boolean>>,
    ) {
        var first = true
        for ((pair, record) in sends) {
            val memo = memo(groupId, pair)
            if (memo == null) {
                if (record && prefs.getE2EPrivateKey(pair.recipient) != null) {
                    cas(groupId, pair, entryFor(groupId, pair), entry(pair, AnnounceLedgerState.TOO_BIG))
                }
                continue
            }
            if (!first) delay(GroupAnnounceRuntime.spacingMs())
            first = false
            if (!relaySend(pair.recipient, memo)) {
                GroupAnnounceRuntime.relayNotAcked += relayTryKey(groupId, pair)
                Log.d(
                    TAG,
                    "GP ${pair.op} for ${pair.subject.redactAddress()} to ${pair.recipient.redactAddress()} not acked (retried later)"
                )
                continue
            }
            GroupAnnounceRuntime.relayNotAcked -= relayTryKey(groupId, pair)
            if (!record) continue
            val state = if (isGp1(pair.recipient)) AnnounceLedgerState.RELAY_FINAL else AnnounceLedgerState.RELAY_UNCONFIRMED
            locked(groupId) {
                val current = entryFor(groupId, pair)
                if (current?.state in CHAIN_STATES && current?.op == pair.op) return@locked
                if (!cas(groupId, pair, current, entry(pair, state))) {
                    Log.w(TAG, "GP relay delivery to ${pair.recipient.redactAddress()} not recorded (store refused)")
                }
            }
        }
    }

    /** The relays are not ready: run [groupId]'s pass again once they are (one waiter per ViewModel, off its scope). */
    private fun waitForRelays(
        scope: CoroutineScope,
        groupId: String
    ) {
        waitingForRelays += groupId
        if (relayWaiter?.isActive == true) return
        val waiter =
            CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main).launch {
                NostrChatBridge.outboundReady.first { it }
                val due = waitingForRelays.toList()
                waitingForRelays.clear()
                if (scope.coroutineContext[kotlinx.coroutines.Job]?.isActive != false) {
                    due.forEach { requestPass(scope, it) }
                }
            }
        relayWaiter = waiter
        GroupAnnounceRuntime.relayWaiters += waiter
        waiter.invokeOnCompletion { GroupAnnounceRuntime.relayWaiters -= waiter }
    }

    /** Stop waiting for the relays (the ViewModel is cleared). */
    fun close() {
        relayWaiter?.cancel()
        waitingForRelays.clear()
    }

    companion object {
        private const val TAG = "ZCHAT_GROUP"
        private const val RELAY_RESEND_INTERVAL_MS = 24 * 60 * 60 * 1000L
        private val CHAIN_STATES =
            setOf(AnnounceLedgerState.CHAIN_PENDING, AnnounceLedgerState.CHAIN_SENT, AnnounceLedgerState.MAYBE_SENT)
    }
}
