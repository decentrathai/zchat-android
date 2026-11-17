package co.electriccoin.zcash.ui.screen.chat.viewmodel

import android.util.Log
import co.electriccoin.zcash.ui.common.datasource.StoredTransaction
import co.electriccoin.zcash.ui.common.sendattempt.AttemptAliveException
import co.electriccoin.zcash.ui.common.sendattempt.LedgerWriteFailedException
import co.electriccoin.zcash.ui.common.sendattempt.SendAttemptClaim
import co.electriccoin.zcash.ui.common.sendattempt.SendAttemptEntry
import co.electriccoin.zcash.ui.common.sendattempt.SendAttemptEnvironment
import co.electriccoin.zcash.ui.common.sendattempt.SendAttemptGate
import co.electriccoin.zcash.ui.common.sendattempt.SendAttemptKind
import co.electriccoin.zcash.ui.common.sendattempt.SendAttemptLedger
import co.electriccoin.zcash.ui.common.sendattempt.SendAttemptLedgerRules
import co.electriccoin.zcash.ui.common.sendattempt.SendAttemptProcess
import co.electriccoin.zcash.ui.common.sendattempt.SendAttemptState
import co.electriccoin.zcash.ui.common.sendattempt.SendAttemptTag
import co.electriccoin.zcash.ui.screen.chat.model.GroupMember
import co.electriccoin.zcash.ui.screen.chat.model.GroupResendOffer
import co.electriccoin.zcash.ui.screen.chat.model.MemberStatus
import co.electriccoin.zcash.ui.screen.chat.model.ZMSGGroupProtocol
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicLong

/**
 * F8-G (fix-all 2.13.0, train T6b; ZC-112 part 2, ZC-055, ZC-082, ZC-083, ZC-619): the pure rules of the group side of
 * the send-attempt ledger: the keys of the group kinds, the write-ahead record of a key rotation, which member rows
 * show "Key not delivered" or "Message not delivered", and the roster an interrupted rotation is completed with.
 * Nothing here sends, pays or writes.
 *
 * Keys (kind, subject, identity):
 * - an invite: INVITE, `groupId|member`, `epoch:<key number the invite carried>`;
 * - a key message: KEY_MEMO (the creator's rotation, re-send or Resend) or KEY_GRANT (the answer to a member's
 *   request), `groupId|member`, `epoch:<key number>`;
 * - one member's copy of a group message: GROUP_MESSAGE, `groupId|member`, `<key number>:<sequence number>`;
 * - a member announce: ANNOUNCE, `groupId`, the pair's name;
 * - a rotation in progress: ROTATION, `groupId`, `epoch:<new key number>` (a local record: never judged by the chain).
 * `member` is the canonical address.
 */
internal object GroupSendAttemptRules {
    private val lastMarker = AtomicLong(0L)

    /** A marker for a new attempt: unique and rising in this process. */
    fun nextMarker(): Long = lastMarker.updateAndGet { maxOf(System.currentTimeMillis(), it + 1L) }

    fun memberSubject(
        groupId: String,
        memberCanon: String,
    ): String = "$groupId|$memberCanon"

    fun groupOf(entry: SendAttemptEntry): String = entry.subject.substringBefore('|')

    fun memberOf(entry: SendAttemptEntry): String = entry.subject.substringAfter('|', "")

    fun epochIdentity(epoch: Int): String = "epoch:$epoch"

    fun epochOf(identity: String): Int? = identity.takeIf { it.startsWith("epoch:") }?.removePrefix("epoch:")?.toIntOrNull()

    fun messageIdentity(
        epoch: Int,
        seq: Long,
    ): String = "$epoch:$seq"

    /** The key number and the sequence number of a message copy's identity, or null when it is not one. */
    fun messageOf(identity: String): Pair<Int, Long>? {
        val epoch = identity.substringBefore(':').toIntOrNull() ?: return null
        val seq = identity.substringAfter(':', "").toLongOrNull() ?: return null
        return epoch to seq
    }

    fun invite(
        groupId: String,
        memberCanon: String,
        epoch: Int,
        retryOf: Long? = null,
    ) = SendAttemptTag(SendAttemptKind.INVITE, memberSubject(groupId, memberCanon), epochIdentity(epoch), retryOf)

    fun key(
        kind: SendAttemptKind,
        groupId: String,
        memberCanon: String,
        epoch: Int,
        retryOf: Long? = null,
    ) = SendAttemptTag(kind, memberSubject(groupId, memberCanon), epochIdentity(epoch), retryOf)

    fun message(
        groupId: String,
        memberCanon: String,
        epoch: Int,
        seq: Long,
        retryOf: Long? = null,
    ) = SendAttemptTag(SendAttemptKind.GROUP_MESSAGE, memberSubject(groupId, memberCanon), messageIdentity(epoch, seq), retryOf)

    fun announce(
        groupId: String,
        pairKey: String,
        retryOf: Long? = null,
    ) = SendAttemptTag(SendAttemptKind.ANNOUNCE, groupId, pairKey, retryOf)

    fun rotationKey(
        groupId: String,
        epoch: Int,
    ): String = SendAttemptLedgerRules.key(SendAttemptKind.ROTATION, groupId, epochIdentity(epoch))

    fun isKeyKind(kind: SendAttemptKind): Boolean = kind == SendAttemptKind.KEY_MEMO || kind == SendAttemptKind.KEY_GRANT

    /**
     * The other key kind's ledger key for the same member and key number, or null for a kind that has none. A key
     * message and a key grant deliver the same key, so a delivery of one settles a failed attempt of the other.
     */
    fun siblingKey(
        kind: SendAttemptKind,
        subject: String,
        identity: String,
    ): String? =
        when (kind) {
            SendAttemptKind.KEY_MEMO -> SendAttemptLedgerRules.key(SendAttemptKind.KEY_GRANT, subject, identity)
            SendAttemptKind.KEY_GRANT -> SendAttemptLedgerRules.key(SendAttemptKind.KEY_MEMO, subject, identity)
            else -> null
        }

    /** A rotation in progress: the key number it mints and the member it removes (null for a plain rotation). */
    data class Rotation(
        val epoch: Int,
        val kicked: String?,
    )

    fun rotationPayload(rotation: Rotation): String =
        JSONObject()
            .put("epoch", rotation.epoch)
            .apply { rotation.kicked?.let { put("kicked", it) } }
            .toString()

    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    fun rotationOf(entry: SendAttemptEntry): Rotation? =
        try {
            if (entry.kind != SendAttemptKind.ROTATION) {
                null
            } else {
                val json = JSONObject(entry.payload ?: "")
                Rotation(json.getInt("epoch"), json.optString("kicked").takeIf { it.isNotEmpty() })
            }
        } catch (e: Exception) {
            null
        }

    fun rotationEntry(
        groupId: String,
        rotation: Rotation,
        startedAt: Long,
    ) = SendAttemptEntry(
        kind = SendAttemptKind.ROTATION,
        subject = groupId,
        identity = epochIdentity(rotation.epoch),
        startedAtMillis = startedAt,
        processId = SendAttemptProcess.ID,
        payload = rotationPayload(rotation),
    )

    /**
     * "Key not delivered": the members (canonical address) whose key message or key grant for the CURRENT key number
     * was proven never to have reached the chain. Left out: a member that does not hold the group key any more, a key
     * whose Resend is running ([inFlight]), and a member whose other key kind for that number is on its way or mined
     * (that delivery settles this one). With both kinds failed, the key message's entry is the one offered.
     */
    fun keyOffers(
        entries: List<SendAttemptEntry>,
        groupId: String,
        currentEpoch: Int,
        keyHolders: Set<String>,
        inFlight: Set<String>,
    ): Map<String, GroupResendOffer> {
        val identity = epochIdentity(currentEpoch)
        val mine = entries.filter { isKeyKind(it.kind) && groupOf(it) == groupId && it.identity == identity }
        val offers = LinkedHashMap<String, GroupResendOffer>()
        for (kind in listOf(SendAttemptKind.KEY_MEMO, SendAttemptKind.KEY_GRANT)) {
            for (entry in mine) {
                val member = memberOf(entry)
                if (entry.kind != kind || entry.state != SendAttemptState.NOT_SENT) continue
                if (member !in keyHolders || member in offers) continue
                val sibling = siblingKey(entry.kind, entry.subject, entry.identity)
                if (entry.key in inFlight || sibling in inFlight) continue
                if (mine.any { it.key == sibling && it.state != SendAttemptState.NOT_SENT }) continue
                offers[member] = GroupResendOffer(entry.key, entry.startedAtMillis)
            }
        }
        return offers
    }

    /**
     * "Message not delivered": per ACTIVE member (canonical address), the oldest copy of one of our group messages
     * that was proven never to have reached the chain, while the key of that message is still held. A copy whose
     * Resend is running is left out.
     */
    fun messageOffers(
        entries: List<SendAttemptEntry>,
        groupId: String,
        activeMembers: Set<String>,
        epochKeyHeld: (Int) -> Boolean,
        inFlight: Set<String>,
    ): Map<String, GroupResendOffer> =
        entries
            .asSequence()
            .filter { it.kind == SendAttemptKind.GROUP_MESSAGE && it.state == SendAttemptState.NOT_SENT && groupOf(it) == groupId }
            .filter { memberOf(it) in activeMembers && it.key !in inFlight }
            .filter { entry -> messageOf(entry.identity)?.let { epochKeyHeld(it.first) } == true }
            .groupBy { memberOf(it) }
            .mapValues { (_, copies) -> copies.minBy { it.startedAtMillis }.let { GroupResendOffer(it.key, it.startedAtMillis) } }

    /**
     * ZC-082 — the roster an interrupted rotation is completed with: every row of the removed member is LEFT, and every
     * other row that holds the group key (not ours) is owed the key of [epoch], except the members in [covered]: they
     * have a ledger entry for a key message of that number (on its way, mined, or failed with its own badge), which
     * owns them. The removed member is never owed the key, whatever its row says. A LEFT row stays as it is.
     *
     * F8G-X3: a covered row is written as a finished rotation writes a member it sent the key to: up to date, with no
     * owed mark. An OLDER mark left on it would outlive the key message's entry (deleted ten blocks after it mined,
     * once the rotation's record is gone) and make the owed-key pass pay the same key again. A covered member whose
     * key message was proven not sent keeps its "Key not delivered" badge, which comes from that entry, not the row.
     */
    fun healedRoster(
        roster: List<GroupMember>,
        epoch: Int,
        kickedCanon: String?,
        covered: Set<String>,
        canon: (String) -> String,
        isSelf: (String) -> Boolean,
    ): List<GroupMember> =
        roster.map { member ->
            val c = canon(member.address)
            when {
                kickedCanon != null && c == kickedCanon -> member.copy(status = MemberStatus.LEFT, pendingKeyEpoch = null)
                member.status == MemberStatus.LEFT -> member
                isSelf(member.address) || !ZMSGGroupProtocol.holdsGroupKey(member) -> member
                c in covered -> member.copy(pendingKeyEpoch = null, pendingKeyTxIds = null)
                else -> member.copy(pendingKeyEpoch = epoch, pendingKeyTxIds = null)
            }
        }

    /** The members (canonical) that have a key-message or key-grant entry of [groupId] at [epoch], in any state. */
    fun coveredByKeyEntry(
        entries: List<SendAttemptEntry>,
        groupId: String,
        epoch: Int,
    ): Set<String> =
        entries
            .filter { isKeyKind(it.kind) && groupOf(it) == groupId && it.identity == epochIdentity(epoch) }
            .mapTo(HashSet()) { memberOf(it) }
}

/**
 * KI23-X1 — the one process-wide claim on PAYING a group key to one member. The automatic answer to the member's key
 * request (ChatViewModel's grantGroupKeyRequestOnce) and the creator's Resend key (GroupViewModel) take it under the
 * same key, [co.electriccoin.zcash.ui.screen.chat.model.GroupKeyGrantPolicy.grantKey] (group, canonical member, key
 * number), and hold it for their whole attempt, block waits included. So the two never run at once: the member's paid
 * count is never changed by one while the other relies on it, and a put-back can only undo its own write. Every
 * screen and ViewModel of the process shares it. A payer that cannot take it sends and pays nothing.
 */
internal object GroupKeyPaymentClaim {
    private val held: MutableSet<String> =
        java.util.concurrent.ConcurrentHashMap
            .newKeySet()

    /** Takes the claim on [grantKey]; false while another payer of that key holds it. */
    fun tryTake(grantKey: String): Boolean = held.add(grantKey)

    fun release(grantKey: String) {
        held.remove(grantKey)
    }
}

/** F8-G: what [GroupSendAttemptCapture.gate] answers. */
internal enum class GroupGateAnswer { PROCEED, PENDING, UNREADABLE }

/**
 * F8-G (ZC-112; SPEC §3.2) — one on-chain attempt of a group memo in the send-attempt ledger, for
 * GroupViewModel.sendGroupMemoWithRetry: the same steps, in the same order, as ChatViewModel's capture.
 *
 * [gate] only reads. [begin] writes the entry of a first attempt in one checked commit (over a failed entry it writes
 * nothing and only checks that it still stands); [stored] commits the ids before the first submit and, for a Resend,
 * replaces the failed entry in that one write. Every group kind is fail closed: [begin] and [stored] throw when the
 * ledger does not take the write, so nothing is handed to the SDK without its entry and nothing is submitted without
 * its ids.
 */
internal class GroupSendAttemptCapture(
    private val environment: SendAttemptEnvironment,
    private val tag: SendAttemptTag,
    private val chainTip: () -> Long?,
) {
    private val ledger: SendAttemptLedger = environment.ledger
    private var seen: Long? = null
    private var claim: SendAttemptClaim? = null
    private var entered = false

    /** The ids commit failed (or lost its entry): the stored transaction was not submitted. */
    var idsCommitFailed = false
        private set

    /** The ids of this attempt are in the ledger. */
    var idsCommitted = false
        private set

    /** [begin] found another attempt of the key (or the failed state it retries gone). */
    var lostToAnother = false
        private set

    /** [begin] could not write the entry, or no account is known: nothing was handed to the SDK. */
    var writeRefused = false
        private set

    @Suppress("TooGenericExceptionCaught")
    fun gate(): GroupGateAnswer =
        try {
            when (val answer = ledger.gate(tag.key, tag.retryOf)) {
                is SendAttemptGate.Proceed -> {
                    seen = answer.seen
                    GroupGateAnswer.PROCEED
                }
                SendAttemptGate.Pending -> {
                    Log.d(TAG, "${tag.kind} waits: an earlier attempt of this key is unresolved or waits for Resend")
                    GroupGateAnswer.PENDING
                }
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.e(TAG, "${tag.kind}: the send-attempt ledger could not be read (${e.javaClass.simpleName})")
            GroupGateAnswer.UNREADABLE
        }

    /** Step 1, right before the SDK is asked. */
    fun begin() {
        try {
            val taken =
                ledger.begin(tag, GroupSendAttemptRules.nextMarker(), chainTip(), SendAttemptProcess.ID, environment.accountHash(), seen)
            claim = taken
            SendAttemptProcess.enter(taken.flightId)
            entered = true
        } catch (e: AttemptAliveException) {
            lostToAnother = true
            throw e
        } catch (e: LedgerWriteFailedException) {
            Log.e(TAG, "${tag.kind}: the send attempt could not be recorded (${e.javaClass.simpleName})")
            writeRefused = true
            throw e
        }
    }

    /** Step 2, after the SDK stored every transaction and before the first submit. */
    fun stored(transactions: List<StoredTransaction>) {
        val taken = claim ?: return
        try {
            ledger.recordStored(
                taken,
                transactions.map { it.txId },
                transactions.mapNotNull { t -> t.expiryHeight?.let { t.txId to it } }.toMap(),
            )
            idsCommitted = true
        } catch (e: LedgerWriteFailedException) {
            Log.e(TAG, "${tag.kind}: the stored ids could not be recorded; nothing is submitted")
            idsCommitFailed = true
            throw e
        }
    }

    /** Step 3: a first attempt that provably sent nothing leaves no entry; a Resend has none of its own to remove. */
    fun discard() {
        val taken = claim ?: return
        if (taken.replaces == null) ledger.discard(taken.key, taken.startedAt)
    }

    /** The loop iteration ended, by any path; only an attempt that entered leaves. */
    fun close() {
        val taken = claim ?: return
        if (!entered) return
        entered = false
        SendAttemptProcess.leave(taken.flightId)
        ledger.finish(taken)
    }

    private companion object {
        const val TAG = "ZCHAT_PAY"
    }
}
