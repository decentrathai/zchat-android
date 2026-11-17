package co.electriccoin.zcash.ui.screen.chat.model

import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.time.Instant

/*
 * Round 8 MA1 — group member announce (GP), the pure part. MEMBER_ANNOUNCE_DESIGN.md v2, §2 (wire), §3 (who is
 * eligible), §4.2 (one announced form), §5.0 (announce state), §5.1 (the receiver's merge), §6.0 (the admin's ledger).
 *
 * Why: a joiner knows only the inviter and itself, so a member's group message reaches only the creator (PHONE_TRIAGE
 * F4). The admin will tell each member, in one signed GP memo per other member, "S is in the group" (A) or "S is no
 * longer in the group" (R). The chain scan replays every group memo on every pass, so every rule here gives the same
 * result when it runs again on old input.
 *
 * Nothing in this file touches the network, the wallet or storage, and nothing in the app calls it yet (MA2 wires the
 * receiver, MA3 the announcer). Every lookup (canonical address, stored key, secure link, self) is passed in.
 */

/** A (the member is in the group) or R (the member left or was removed). The wire writes the name. */
enum class AnnounceOp { A, R }

/** How a member row became ACTIVE: through an authenticated step (signed GA, sealed relay message, verified GM,
 *  or a verified GP) or not. Only AUTH members are ever announced or told (design §3, review M6). */
enum class ActivatedBy { AUTH, UNAUTH }

/** Delivery state of one (recipient, subject) pair in the admin's ledger (design §6.0). */
enum class AnnounceLedgerState {
    /** A relay ack from a recipient that advertised gp1: it can read GP, so this counts as delivered. */
    RELAY_FINAL,

    /** A relay ack from a recipient without gp1: re-sent free later, never counted as delivered. */
    RELAY_UNCONFIRMED,

    /** An on-chain copy is being paid for right now (written before the submit). */
    CHAIN_PENDING,

    /** An on-chain copy was submitted (txids known). */
    CHAIN_SENT,

    /** An on-chain copy may have been sent; it is never paid again. */
    MAYBE_SENT,

    /** The memo does not fit 512 bytes; never quoted. */
    TOO_BIG,
}

/** One parsed, not yet authenticated, member announce ([ZMSGGroupProtocol.parseGroupMemberAnnouncePayload]). */
data class GroupMemberAnnounce(
    val groupId: String,
    val op: AnnounceOp,
    val subject: String,
    val n: Long,
    val epoch: Int,
    val signature: String,
    // MA4 only, relay copies only: the subject's NOSTR pubkey (64 lowercase hex).
    val nostrPubkey: String? = null,
)

/** A parsed roster query (`GQ`, `{"v":1}`). */
data class GroupRosterQueryPayload(
    val groupId: String,
    val version: Int,
)

/**
 * The announce record of one member, keyed by its canonical address in [AnnounceState.members] (design §5.0).
 * [n] / [op] / [epoch] are the last applied (receiver) or current (announcer) announce; 0 / null = none.
 */
data class MemberAnnounceEntry(
    val n: Long = 0,
    val op: AnnounceOp? = null,
    val epoch: Int = 0,
    val activatedAtMs: Long = 0,
    val activatedBy: ActivatedBy? = null,
    val leftAtMs: Long = 0,
    val removedAtEpoch: Int = 0,
    // Announcer only: the one address form this member is announced in, set on its first announce, never changed.
    val annForm: String? = null,
    // Round 8 MA2: how the last leave or removal applied to this member was authenticated (a sealed or signed GL, a
    // verified kick, a verified R: AUTH; an unsigned on-chain GL: UNAUTH); null when none. MA3's T2 reads it.
    val leftBy: ActivatedBy? = null,
    // Announcer only, Round 8 MA-V1 (design §8): the scan saw our own sent invite to this member, a proof that it
    // joined through us for a row from before the member announce whose invite status was never tracked.
    val invitedByUs: Boolean = false,
)

/**
 * ZC-294 — the creator's newest group invite that arrived while this phone was still in the group, kept for the case
 * that the removal it answers arrives after it: its id ([inviteId], a txid or a relay event id), its source time
 * ([sourceMs]: the block time, or the relay rumor's time), the invite's payload as received ([inviteJson]; it can hold a
 * group key, and the state lives in the encrypted group-members store) and, over the relays, the seal's sender
 * ([sealSender]; null on-chain).
 */
data class PendingReinvite(
    val inviteId: String,
    val sourceMs: Long,
    val inviteJson: String,
    val sealSender: String? = null,
)

/**
 * ZC-704 (KI-25 R19) — one change of a group's admin that reached this phone over the relays and is kept in the
 * group's announce state for later: a removal ([KICK]) or a key change ([KEY]) the gate refused while the phone could
 * not confirm the group's admin, the same when it was accepted and its key could not be saved ([accepted]), or a
 * re-invite ([INVITE]) for the day the removal it answers is applied.
 *
 * [id] is the relay event id; [epoch] the change's epoch (an invite's key epoch); [payload] the payload as it came;
 * [from] the seal's sender as the relay reader passed it; [by] the chat whose stored key verified the signature (the
 * seal's sender for an accepted entry and for an invite); [at] the rumor's time and [kept] this phone's clock at the
 * keeping, both in milliseconds; [height] the height this phone had scanned to then (none for an invite);
 * [failedRuns] the runs in which its handler threw. [keptReadAsNow] is never stored: the reader sets it when the
 * stored [kept] time was missing, not positive or in the future and it gave the entry the time of the read, so that
 * the end of the next history pass writes that time down and the entry ages from then on.
 */
data class KeptAdminChange(
    val id: String,
    val kind: String,
    val epoch: Int,
    val payload: String,
    val from: String,
    val by: String = from,
    val at: Long = 0,
    val kept: Long,
    val height: Long? = null,
    val failedRuns: Int = 0,
    val accepted: Boolean = false,
    val keptReadAsNow: Boolean = false,
) {
    companion object {
        // The wire's own codes of the three kinds.
        const val KICK = "GK"
        const val KEY = "GY"
        const val INVITE = "GI"

        // The bounds, at the write and at the read: entries per group, and the longest id, address and payload.
        const val MAX_ENTRIES = 48
        const val MAX_ID_CHARS = 128
        const val MAX_ADDRESS_CHARS = 512
        const val MAX_PAYLOAD_CHARS = 2_048
    }
}

/**
 * Per-group announce state, stored outside the roster JSON (ZchatPreferences `group_ann_state:<gid>`), so a GI reopen
 * never replaces it and `deleteGroup` keeps [hw], [usedInviteIds] and [seenActiveInviteIds] (design §5.0, ZC-294).
 */
data class AnnounceState(
    // The highest applied n.
    val hw: Long = 0,
    // True once a GP was applied: from then on only a verified GP adds members on a member's phone (MA2).
    val rosterAnnounced: Boolean = false,
    val selfJoinN: Long = 0,
    val selfJoinEpoch: Int = 0,
    val inactiveSince: Long? = null,
    // Txids / event ids of invites already used, so an old invite can never reopen the group.
    val usedInviteIds: Set<String> = emptySet(),
    val members: Map<String, MemberAnnounceEntry> = emptyMap(),
    // Round 8 MA2 (design §4.2, test 24): per member key ([announceKeyHash] of its stored E2E key), the source time of
    // the last signed GROUP_ACCEPT whose declared address was adopted; an older accept is never adopted again.
    val adoptedAcceptAtMs: Map<String, Long> = emptyMap(),
    // Round 8 MA2 (design §8): the one-time "the creator now shares every member's address" notice was shown.
    val rosterNoticeShown: Boolean = false,
    // Round 8 MA3 (design §6.2): the announcer's "Not now" on the introduce banner: the owed on-chain pairs
    // ([announcePairKey]) it hid; the banner comes back once a pair outside this set is owed.
    val introduceHidden: Set<String> = emptySet(),
    // Round 8 MA3 (design §6.1): on a member's phone, when this phone last asked the creator for the roster (GQ, over
    // the relays), and the highest key epoch of a verified GP that made it ask (it asks once per newer epoch).
    val rosterQueryAtMs: Long = 0,
    val rosterQueryEpoch: Int = 0,
    // GR-OPEN G2 (fold-specs/groups SPEC §2.2, review F): this phone joined the group with the creator's plaintext
    // relay invite (or reopened it with one), so it may act on the creator's sealed admin controls
    // (ZMSGGroupProtocol.sealedAdminControlAuthorized). False for a group joined through a key exchange and for every
    // group this phone had before this field existed.
    val plainKeyJoin: Boolean = false,
    // ZC-294: ids of invites that arrived while this phone was still in the group. Such an invite is neither used nor
    // lost: it may still offer Join when a removal older than it arrives afterwards. A deleted group is not recreated
    // by one of them (the tombstone keeps them).
    val seenActiveInviteIds: Set<String> = emptySet(),
    // ZC-294: the creator's newest invite of those, offered through the re-invite checks when this phone is removed.
    val pendingReinvite: PendingReinvite? = null,
    // ZC-704 (KI-25 R19): the admin's removals, key changes and re-invites that came over the relays while this phone
    // could not confirm the group's admin (or whose key could not be saved), kept for the end of a history pass. A
    // deleted group's tombstone does not keep them.
    val keptAdminChanges: List<KeptAdminChange> = emptyList(),
)

/** What [applyMemberAnnounce] returns: the new state and roster, and whether the announce changed anything. */
data class AnnounceMergeResult(
    val state: AnnounceState,
    val roster: List<GroupMember>,
    val applied: Boolean,
)

/**
 * One announce the admin owes: tell [recipient] (canonical [rAddr], key hash [rKey]) that [subject] (the announced
 * form; canonical [sAddr], key hash [sKey]) is in ([AnnounceOp.A]) or out ([AnnounceOp.R]), with number [n] signed at
 * [epoch].
 */
data class AnnouncePair(
    val recipient: String,
    val rAddr: String,
    val rKey: String?,
    val subject: String,
    val sAddr: String,
    val sKey: String?,
    val op: AnnounceOp,
    val n: Long,
    val epoch: Int,
)

/**
 * One (recipient, subject) record of the admin's ledger (`group_ann_ledger:<gid>`): the last op delivered or being
 * delivered to that pair. `rKey` / `sKey` are [announceKeyHash] of the stored E2E keys, `rAddr` / `sAddr` canonical
 * addresses; an entry matches a pair when (rKey or rAddr) and (sKey or sAddr) match (design §6.0).
 */
data class AnnounceLedgerEntry(
    val rKey: String?,
    val rAddr: String,
    val sKey: String?,
    val sAddr: String,
    val op: AnnounceOp,
    val state: AnnounceLedgerState,
    val txids: List<String> = emptyList(),
    val at: Long = 0,
)

/** What [assignAnnounceNumbers] returns: the new state and the new value of the announce counter. */
data class AnnounceNumbering(
    val state: AnnounceState,
    val counter: Long,
)

/** A ledger state that counts as "delivered" for the debt rule. */
private val DELIVERED =
    setOf(
        AnnounceLedgerState.CHAIN_SENT,
        AnnounceLedgerState.CHAIN_PENDING,
        AnnounceLedgerState.MAYBE_SENT,
        AnnounceLedgerState.RELAY_FINAL,
    )

/** A ledger state that records an on-chain copy; a relay state never replaces one for the same op. */
private val CHAIN =
    setOf(AnnounceLedgerState.CHAIN_PENDING, AnnounceLedgerState.CHAIN_SENT, AnnounceLedgerState.MAYBE_SENT)

private val RELAY = setOf(AnnounceLedgerState.RELAY_FINAL, AnnounceLedgerState.RELAY_UNCONFIRMED)

/** The longest signature a P-256 ECDSA DER signature gives in base64 (72 bytes): the size check's worst case. */
private const val MAX_SIGNATURE_CHARS = 96

/** SHA-256 (hex) of a stored E2E public key: how the ledger names a member by key without storing the key again. */
fun announceKeyHash(publicKey: String): String =
    MessageDigest
        .getInstance("SHA-256")
        .digest(publicKey.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

/**
 * `n = max(storedN + 1, nowMs, highestOwnSentN + 1)` (design §2.1): only grows, never below the wall clock, and always
 * above the highest number this admin ever sent on-chain (which a restore rebuilds).
 */
fun nextAnnounceNumber(
    storedN: Long,
    nowMs: Long,
    highestOwnSentN: Long
): Long = maxOf(storedN + 1, nowMs, highestOwnSentN + 1)

/** True when a GP naming [subject] at [n] / [epoch] fits one 512-byte memo with the longest possible signature. */
fun memberAnnounceFits(
    groupId: String,
    subject: String,
    n: Long,
    epoch: Int
): Boolean =
    ZMSGGroupProtocol.createGroupMemberAnnounceMessage(
        groupId,
        AnnounceOp.A,
        subject,
        n,
        epoch,
        "/".repeat(MAX_SIGNATURE_CHARS),
    ) != null

/**
 * The receiver's merge of one VERIFIED member announce (design §5.1 step 3-6). The caller has already checked the
 * group, parsed strictly and authenticated the GP against the creator's key; [isSelf] / [isCreator] say whether the
 * subject is this phone / the group's creator, [canon] canonicalizes an address, [nickname] reads the contact book.
 *
 * | Entry | Row absent | INVITED | ACTIVE | LEFT (or KICKED) |
 * |---|---|---|---|---|
 * | A | add ACTIVE | → ACTIVE | no row change | → ACTIVE only if e >= st.removedAtEpoch and n > st.leftAtMs |
 * | R | add a LEFT tombstone | → LEFT | → LEFT | no row change |
 *
 * - An announce with `n <= st.n` is dropped, so a replay changes nothing.
 * - An A naming this phone is ignored; anything naming the creator is ignored.
 * - An R naming this phone applies only when `n > max(selfJoinN, st.n)` and `e >= selfJoinEpoch`: the own row becomes
 *   LEFT (never deleted, never added). Marking the group inactive is the caller's (MA2).
 * - When an entry applies: st.n / op / epoch are set, `hw = max(hw, n)`, and `rosterAnnounced` becomes true; an A
 *   also sets `activatedAtMs = n` and `activatedBy = AUTH`.
 */
@Suppress("LongParameterList", "ReturnCount")
fun applyMemberAnnounce(
    state: AnnounceState,
    roster: List<GroupMember>,
    ann: GroupMemberAnnounce,
    isSelf: Boolean,
    isCreator: Boolean,
    canon: (String) -> String,
    nickname: (String) -> String?,
): AnnounceMergeResult {
    val unchanged = AnnounceMergeResult(state, roster, applied = false)
    if (isCreator) return unchanged
    if (isSelf && ann.op == AnnounceOp.A) return unchanged

    val key = canon(ann.subject)
    val st = state.members[key] ?: MemberAnnounceEntry()
    if (ann.n <= st.n) return unchanged

    val rows = roster.indices.filter { canon(roster[it].address) == key }
    val newRoster: List<GroupMember>
    if (isSelf) {
        // R naming this phone (an A was returned above).
        if (ann.n <= maxOf(state.selfJoinN, st.n) || ann.epoch < state.selfJoinEpoch) return unchanged
        newRoster = roster.mapIndexed { i, m -> if (i in rows) m.copy(status = MemberStatus.LEFT) else m }
    } else if (rows.isEmpty()) {
        val added =
            GroupMember(
                address = ann.subject,
                joinedAt = Instant.ofEpochMilli(ann.n),
                status = if (ann.op == AnnounceOp.A) MemberStatus.ACTIVE else MemberStatus.LEFT,
                isAdmin = false,
                nickname = nickname(ann.subject),
            )
        newRoster = roster + added
    } else {
        val out = rows.all { roster[it].status == MemberStatus.LEFT || roster[it].status == MemberStatus.KICKED }
        newRoster =
            when (ann.op) {
                AnnounceOp.A -> {
                    if (out && (ann.epoch < st.removedAtEpoch || ann.n <= st.leftAtMs)) return unchanged
                    // A member still in under one form keeps its other, departed forms departed (no second recipient).
                    val back = if (out) setOf(MemberStatus.LEFT, MemberStatus.KICKED) else emptySet()
                    roster.mapIndexed { i, m ->
                        if (i in rows && (m.status == MemberStatus.INVITED || m.status in back)) {
                            m.copy(status = MemberStatus.ACTIVE)
                        } else {
                            m
                        }
                    }
                }
                AnnounceOp.R ->
                    roster.mapIndexed { i, m ->
                        if (i in rows && (m.status == MemberStatus.ACTIVE || m.status == MemberStatus.INVITED)) {
                            m.copy(status = MemberStatus.LEFT)
                        } else {
                            m
                        }
                    }
            }
    }

    val entry =
        if (ann.op == AnnounceOp.A) {
            st.copy(n = ann.n, op = ann.op, epoch = ann.epoch, activatedAtMs = ann.n, activatedBy = ActivatedBy.AUTH)
        } else {
            st.copy(n = ann.n, op = ann.op, epoch = ann.epoch)
        }
    val newState =
        state.copy(
            hw = maxOf(state.hw, ann.n),
            rosterAnnounced = true,
            members = state.members + (key to entry),
        )
    return AnnounceMergeResult(newState, newRoster, applied = true)
}

/** The rows the announcer may tell (and, for A, announce): design §3. */
private fun eligibleRows(
    state: AnnounceState,
    roster: List<GroupMember>,
    canon: (String) -> String,
    isSelf: (String) -> Boolean,
    keyOf: (String) -> String?,
    hasSecureLink: (String) -> Boolean,
): List<GroupMember> =
    roster.filter { m ->
        m.status == MemberStatus.ACTIVE &&
            !m.isAdmin &&
            !isSelf(m.address) &&
            state.members[canon(m.address)]?.activatedBy == ActivatedBy.AUTH &&
            hasSecureLink(m.address) &&
            keyOf(m.address) != null
    }

/**
 * The announcer's numbering pass (design §4, §4.2): gives each member whose current op changed a new number from the
 * counter ([nextAnnounceNumber]), and returns the new state and counter. Pure; the caller saves both under the group
 * lock before anything is sent.
 *
 * - A: an ACTIVE, non-admin, non-self member activated by AUTH, with a secure link, a stored key and a memo that fits.
 *   Members sharing a stored key are one subject, announced in the form already announced, else the first in roster
 *   order. After an R, an A needs an activation newer than the member's leave (`activatedAtMs > leftAtMs`: a
 *   re-invite and a new join). Both are source times (the block time, or the sender's relay time), and it is the rule
 *   by which the creator's phone accepted the comeback, so every accepted comeback is announced. The R's own number is
 *   not used here: it comes from the announcer's clock, which may run ahead of a quick comeback (ZC-293). Only a
 *   record with no leave time at all (`leftAtMs == 0`) is still judged by the R's number, as before.
 * - R: a member announced A whose rows are all LEFT or KICKED. Rows missing from the roster are left alone.
 * - A member whose op is unchanged keeps its number: a re-send never makes a new number here.
 */
@Suppress("LongParameterList", "CyclomaticComplexMethod")
fun assignAnnounceNumbers(
    groupId: String,
    state: AnnounceState,
    roster: List<GroupMember>,
    counter: Long,
    nowMs: Long,
    highestOwnSentN: Long,
    epoch: Int,
    canon: (String) -> String,
    isSelf: (String) -> Boolean,
    keyOf: (String) -> String?,
    hasSecureLink: (String) -> Boolean,
): AnnounceNumbering {
    var next = counter
    val members = state.members.toMutableMap()

    // A: one subject per stored key.
    val byKey = eligibleRows(state, roster, canon, isSelf, keyOf, hasSecureLink).groupBy { announceKeyHash(keyOf(it.address)!!) }
    for (rows in byKey.values) {
        val chosen = rows.firstOrNull { members[canon(it.address)]?.op == AnnounceOp.A } ?: rows.first()
        val c = canon(chosen.address)
        val st = members.getValue(c)
        if (st.op == AnnounceOp.A) continue
        if (st.op == AnnounceOp.R && st.activatedAtMs <= (if (st.leftAtMs > 0) st.leftAtMs else st.n)) continue
        val form = st.annForm ?: chosen.address
        val n = nextAnnounceNumber(next, nowMs, highestOwnSentN)
        if (!memberAnnounceFits(groupId, form, n, epoch)) continue
        next = n
        members[c] = st.copy(n = n, op = AnnounceOp.A, epoch = epoch, annForm = form)
    }

    // R: every member announced in whose rows are all out of the group. Round 8 MA3 (design §4, review M3): not for a
    // leave the announcer could not verify (an unsigned on-chain GL): that row waits for "Remove for everyone".
    for ((c, st) in members.toMap()) {
        if (st.op != AnnounceOp.A) continue
        if (st.leftBy == ActivatedBy.UNAUTH) continue
        val rows = roster.filter { canon(it.address) == c }
        if (rows.isEmpty()) continue
        if (rows.any { it.status != MemberStatus.LEFT && it.status != MemberStatus.KICKED }) continue
        val n = nextAnnounceNumber(next, nowMs, highestOwnSentN)
        next = n
        members[c] = st.copy(n = n, op = AnnounceOp.R, epoch = epoch)
    }

    return AnnounceNumbering(state.copy(members = members), next)
}

private fun matches(
    e: AnnounceLedgerEntry,
    rAddr: String,
    rKey: String?,
    sAddr: String,
    sKey: String?,
): Boolean {
    val r = (rKey != null && e.rKey == rKey) || e.rAddr == rAddr
    val s = (sKey != null && e.sKey == sKey) || e.sAddr == sAddr
    return r && s
}

/** The ledger entries that name [pair] (by key or address, on each side), latest first. */
private fun entriesFor(
    ledger: List<AnnounceLedgerEntry>,
    pair: AnnouncePair
): List<AnnounceLedgerEntry> =
    ledger.filter { matches(it, pair.rAddr, pair.rKey, pair.sAddr, pair.sKey) }.sortedByDescending { it.at }

/** Round 8 MA3 — the ledger entry now naming [pair] (by key or address, on each side): the latest one, or null. */
fun announceLedgerEntryFor(
    ledger: List<AnnounceLedgerEntry>,
    pair: AnnouncePair
): AnnounceLedgerEntry? = entriesFor(ledger, pair).firstOrNull()

/** Round 8 MA3 — a stable name for one (recipient, subject, op) pair, by canonical addresses (quotes, "Not now"). */
fun announcePairKey(pair: AnnouncePair): String = "${pair.rAddr}|${pair.sAddr}|${pair.op.name}"

/** True when the ledger says [op] was already delivered (or is being paid for) to the pair, or can never be sent. */
private fun settled(
    entries: List<AnnounceLedgerEntry>,
    op: AnnounceOp
): Boolean {
    if (entries.any { it.op == op && it.state == AnnounceLedgerState.TOO_BIG }) return true
    val lastDelivered = entries.firstOrNull { it.state in DELIVERED } ?: return false
    return lastDelivered.op == op
}

/**
 * The (recipient, subject) pairs that still owe the subject's current announce (design §3, §6.0). Pure.
 *
 * - Subjects: members the announcer numbered ([MemberAnnounceEntry.op] and `annForm` set), in their announced form;
 *   an A subject still needs a secure link and a stored key.
 * - Recipients: ACTIVE, non-admin, non-self members activated by AUTH with a secure link and a stored key, one per
 *   stored key; never a recipient with the subject's key or address. An R is also owed to the removed member itself
 *   (its other device), through its announced form, when it has a secure link (T2 / T3), but only while every roster
 *   row of that member is LEFT or KICKED (ZC-291): a member invited again (INVITED) or back (ACTIVE, its A not numbered
 *   yet) is never sent its own removal, which would take a phone that has just joined out again. The other recipients
 *   keep the R until an A replaces it.
 * - The debt rule: a pair is owed only while the op last delivered to it (CHAIN_SENT, CHAIN_PENDING, MAYBE_SENT or
 *   RELAY_FINAL) differs from the current op; a TOO_BIG entry for the op is never owed. A new n with the same op is not.
 *
 * Which owed pairs go over the relays and which may be quoted on-chain is the caller's (MA3).
 */
@Suppress("LongParameterList")
fun owedMemberAnnounces(
    state: AnnounceState,
    roster: List<GroupMember>,
    ledger: List<AnnounceLedgerEntry>,
    canon: (String) -> String,
    isSelf: (String) -> Boolean,
    keyOf: (String) -> String?,
    hasSecureLink: (String) -> Boolean,
): List<AnnouncePair> {
    val recipients =
        eligibleRows(state, roster, canon, isSelf, keyOf, hasSecureLink)
            .distinctBy { announceKeyHash(keyOf(it.address)!!) }
    val owed = mutableListOf<AnnouncePair>()
    for ((sAddr, st) in state.members) {
        val op = st.op ?: continue
        val form = st.annForm ?: continue
        val sKey = keyOf(form)?.let { announceKeyHash(it) }
        if (op == AnnounceOp.A && (sKey == null || !hasSecureLink(form))) continue

        val targets = recipients.filter { canon(it.address) != sAddr && announceKeyHash(keyOf(it.address)!!) != sKey }.map { it.address }
        val stillOut =
            roster
                .filter { canon(it.address) == sAddr }
                .all { it.status == MemberStatus.LEFT || it.status == MemberStatus.KICKED }
        val toSelf = if (op == AnnounceOp.R && stillOut && !isSelf(form) && hasSecureLink(form)) listOf(form) else emptyList()
        for (r in targets + toSelf) {
            val pair =
                AnnouncePair(
                    recipient = r,
                    rAddr = canon(r),
                    rKey = keyOf(r)?.let { announceKeyHash(it) },
                    subject = form,
                    sAddr = sAddr,
                    sKey = sKey,
                    op = op,
                    n = st.n,
                    epoch = st.epoch,
                )
            if (!settled(entriesFor(ledger, pair), op)) owed += pair
        }
    }
    return owed
}

/**
 * The ledger's compare-and-set (design §6.0, review F8), pure: ZchatPreferences.casGroupAnnounce runs it under the
 * group lock. Returns the new ledger, or null when refused.
 *
 * - The entry now naming [pair] (the latest match) must equal [expected] (null = none).
 * - A RELAY_* state never replaces a CHAIN_PENDING, CHAIN_SENT or MAYBE_SENT entry of the same op.
 * - CHAIN_PENDING is written only while the pair is owed for that op.
 * - [new] null removes the pair's entries (a proven non-send puts the pair back to owed).
 * - The result keeps one entry per pair.
 */
@Suppress("ReturnCount")
fun casAnnounceLedger(
    ledger: List<AnnounceLedgerEntry>,
    pair: AnnouncePair,
    expected: AnnounceLedgerEntry?,
    new: AnnounceLedgerEntry?,
): List<AnnounceLedgerEntry>? {
    val current = entriesFor(ledger, pair)
    if (current.firstOrNull() != expected) return null
    if (new != null) {
        if (new.state in RELAY && current.any { it.op == new.op && it.state in CHAIN }) return null
        if (new.state == AnnounceLedgerState.CHAIN_PENDING && settled(current, new.op)) return null
    }
    val rest = ledger.filter { !matches(it, pair.rAddr, pair.rKey, pair.sAddr, pair.sKey) }
    return if (new == null) rest else rest + new
}

// ── JSON codecs (stored in ZchatPreferences; unknown keys are ignored, an unreadable value reads as empty) ───────────

private inline fun <reified T : Enum<T>> enumOrNull(name: String?): T? =
    name?.let { n -> enumValues<T>().firstOrNull { it.name == n } }

private fun JSONObject.optStringOrNull(key: String): String? = if (has(key) && !isNull(key)) optString(key) else null

fun announceStateToJson(state: AnnounceState): String {
    val members = JSONObject()
    for ((address, e) in state.members) {
        members.put(
            address,
            JSONObject().apply {
                put("n", e.n)
                e.op?.let { put("o", it.name) }
                put("e", e.epoch)
                put("activated_at", e.activatedAtMs)
                e.activatedBy?.let { put("activated_by", it.name) }
                put("left_at", e.leftAtMs)
                put("removed_at_epoch", e.removedAtEpoch)
                e.annForm?.let { put("ann_form", it) }
                e.leftBy?.let { put("left_by", it.name) }
                if (e.invitedByUs) put("invited_by_us", true)
            },
        )
    }
    return JSONObject()
        .apply {
            put("hw", state.hw)
            put("roster_announced", state.rosterAnnounced)
            put("self_join_n", state.selfJoinN)
            put("self_join_epoch", state.selfJoinEpoch)
            state.inactiveSince?.let { put("inactive_since", it) }
            put("used_invite_ids", JSONArray(state.usedInviteIds.sorted()))
            put("members", members)
            if (state.adoptedAcceptAtMs.isNotEmpty()) put("adopted_accept_at", JSONObject(state.adoptedAcceptAtMs))
            if (state.rosterNoticeShown) put("roster_notice_shown", true)
            if (state.introduceHidden.isNotEmpty()) put("introduce_hidden", JSONArray(state.introduceHidden.sorted()))
            if (state.rosterQueryAtMs != 0L) put("roster_query_at", state.rosterQueryAtMs)
            if (state.rosterQueryEpoch != 0) put("roster_query_epoch", state.rosterQueryEpoch)
            if (state.plainKeyJoin) put("plain_key_join", true)
            if (state.seenActiveInviteIds.isNotEmpty()) put("seen_active_invite_ids", JSONArray(state.seenActiveInviteIds.sorted()))
            state.pendingReinvite?.let { kept ->
                put(
                    "pending_reinvite",
                    JSONObject().apply {
                        put("id", kept.inviteId)
                        put("source_ms", kept.sourceMs)
                        put("invite", kept.inviteJson)
                        kept.sealSender?.let { put("sender", it) }
                    },
                )
            }
            if (state.keptAdminChanges.isNotEmpty()) put("kept_admin_changes", keptAdminChangesToJson(state.keptAdminChanges))
        }.toString()
}

/** ZC-704 (KI-25 R19) — the stored form of the kept admin changes; `h`, `f` and `acc` are written only when set. */
private fun keptAdminChangesToJson(kept: List<KeptAdminChange>): JSONArray {
    val array = JSONArray()
    for (e in kept) {
        array.put(
            JSONObject().apply {
                put("id", e.id)
                put("t", e.kind)
                put("e", e.epoch)
                put("p", e.payload)
                put("from", e.from)
                put("by", e.by)
                put("at", e.at)
                put("kept", e.kept)
                e.height?.let { put("h", it) }
                if (e.failedRuns != 0) put("f", e.failedRuns)
                if (e.accepted) put("acc", true)
            },
        )
    }
    return array
}

@Suppress("TooGenericExceptionCaught", "SwallowedException")
fun announceStateFromJson(json: String?): AnnounceState {
    if (json.isNullOrEmpty()) return AnnounceState()
    return try {
        val o = JSONObject(json)
        val ids = o.optJSONArray("used_invite_ids")
        val m = o.optJSONObject("members")
        AnnounceState(
            hw = o.optLong("hw", 0L),
            rosterAnnounced = o.optBoolean("roster_announced", false),
            selfJoinN = o.optLong("self_join_n", 0L),
            selfJoinEpoch = o.optInt("self_join_epoch", 0),
            inactiveSince = if (o.has("inactive_since")) o.getLong("inactive_since") else null,
            usedInviteIds = ids?.let { a -> (0 until a.length()).map { a.getString(it) }.toSet() } ?: emptySet(),
            members =
                m?.keys()?.asSequence()?.associateWith { address ->
                    val e = m.getJSONObject(address)
                    MemberAnnounceEntry(
                        n = e.optLong("n", 0L),
                        op = enumOrNull<AnnounceOp>(e.optStringOrNull("o")),
                        epoch = e.optInt("e", 0),
                        activatedAtMs = e.optLong("activated_at", 0L),
                        activatedBy = enumOrNull<ActivatedBy>(e.optStringOrNull("activated_by")),
                        leftAtMs = e.optLong("left_at", 0L),
                        removedAtEpoch = e.optInt("removed_at_epoch", 0),
                        annForm = e.optStringOrNull("ann_form"),
                        leftBy = enumOrNull<ActivatedBy>(e.optStringOrNull("left_by")),
                        invitedByUs = e.optBoolean("invited_by_us", false),
                    )
                } ?: emptyMap(),
            adoptedAcceptAtMs =
                o.optJSONObject("adopted_accept_at")?.let { a -> a.keys().asSequence().associateWith { a.getLong(it) } }
                    ?: emptyMap(),
            rosterNoticeShown = o.optBoolean("roster_notice_shown", false),
            introduceHidden =
                o.optJSONArray("introduce_hidden")?.let { a -> (0 until a.length()).map { a.getString(it) }.toSet() } ?: emptySet(),
            rosterQueryAtMs = o.optLong("roster_query_at", 0L),
            rosterQueryEpoch = o.optInt("roster_query_epoch", 0),
            plainKeyJoin = o.optBoolean("plain_key_join", false),
            seenActiveInviteIds =
                o.optJSONArray("seen_active_invite_ids")?.let { a -> (0 until a.length()).map { a.getString(it) }.toSet() }
                    ?: emptySet(),
            pendingReinvite = pendingReinviteFromJson(o.optJSONObject("pending_reinvite")),
            keptAdminChanges = keptAdminChangesFromJson(o.optJSONArray("kept_admin_changes")),
        )
    } catch (e: Exception) {
        AnnounceState()
    }
}

/**
 * ZC-294 — the kept re-invite of a stored announce state, or null when there is none or it cannot be read (no id, no
 * positive source time, or an invite that is not a JSON object): the rest of the state is read either way.
 */
private fun pendingReinviteFromJson(o: JSONObject?): PendingReinvite? {
    if (o == null) return null
    val id = o.optString("id", "")
    val sourceMs = o.optLong("source_ms", 0L)
    val invite = o.optString("invite", "")
    val readable = id.isNotEmpty() && sourceMs > 0L && runCatching { JSONObject(invite) }.isSuccess
    return if (readable) PendingReinvite(id, sourceMs, invite, o.optStringOrNull("sender")?.takeIf { it.isNotEmpty() }) else null
}

/**
 * ZC-704 (KI-25 R19) — the kept admin changes of a stored announce state. It stands by itself, like the kept
 * re-invite's reader, so the rest of the state is read whatever this field holds. Of what is stored only the first
 * [KeptAdminChange.MAX_ENTRIES] values are looked at, and a value that is no readable entry reads as no entry.
 */
private fun keptAdminChangesFromJson(array: JSONArray?): List<KeptAdminChange> {
    if (array == null) return emptyList()
    val now = System.currentTimeMillis()
    return (0 until minOf(array.length(), KeptAdminChange.MAX_ENTRIES)).mapNotNull { i ->
        runCatching { keptAdminChangeFromJson(array.optJSONObject(i), now) }.getOrNull()
    }
}

/**
 * One kept admin change, or null: no id, an unknown kind, an epoch below 1 (below 0 for an invite, whose key epoch
 * can be 0), no payload, no sender, or an id, address or payload over its length. Defaults: no signer chat reads as
 * the sender, no failed-run count as 0, no height as none, no `acc` as kept as refused; a `kept` time that is missing,
 * not positive or after [now] reads as [now].
 */
private fun keptAdminChangeFromJson(
    o: JSONObject?,
    now: Long,
): KeptAdminChange? {
    if (o == null) return null
    val id = o.optStringOrNull("id").orEmpty()
    val kind = o.optStringOrNull("t").orEmpty()
    val epoch = o.optInt("e", -1)
    val payload = o.optStringOrNull("p").orEmpty()
    val from = o.optStringOrNull("from").orEmpty()
    val by = o.optStringOrNull("by").orEmpty().ifEmpty { from }
    val lowestEpoch =
        when (kind) {
            KeptAdminChange.INVITE -> 0
            KeptAdminChange.KICK, KeptAdminChange.KEY -> 1
            else -> return null
        }
    val readable =
        id.isNotEmpty() &&
            id.length <= KeptAdminChange.MAX_ID_CHARS &&
            epoch >= lowestEpoch &&
            payload.isNotEmpty() &&
            payload.length <= KeptAdminChange.MAX_PAYLOAD_CHARS &&
            from.isNotEmpty() &&
            from.length <= KeptAdminChange.MAX_ADDRESS_CHARS &&
            by.length <= KeptAdminChange.MAX_ADDRESS_CHARS
    if (!readable) return null
    val storedKept = o.optLong("kept", 0L)
    val keptKnown = storedKept in 1..now
    return KeptAdminChange(
        id = id,
        kind = kind,
        epoch = epoch,
        payload = payload,
        from = from,
        by = by,
        at = o.optLong("at", 0L),
        kept = if (keptKnown) storedKept else now,
        height = if (o.has("h") && !o.isNull("h")) o.optLong("h", 0L).takeIf { it > 0L } else null,
        failedRuns = o.optInt("f", 0).coerceAtLeast(0),
        accepted = o.optBoolean("acc", false),
        keptReadAsNow = !keptKnown,
    )
}

fun announceLedgerToJson(ledger: List<AnnounceLedgerEntry>): String {
    val array = JSONArray()
    for (e in ledger) {
        array.put(
            JSONObject().apply {
                e.rKey?.let { put("r_key", it) }
                put("r_addr", e.rAddr)
                e.sKey?.let { put("s_key", it) }
                put("s_addr", e.sAddr)
                put("op", e.op.name)
                put("state", e.state.name)
                put("txids", JSONArray(e.txids))
                put("at", e.at)
            },
        )
    }
    return array.toString()
}

/** An entry whose op or state this build does not know is left out (it can only come from a later build). */
@Suppress("TooGenericExceptionCaught", "SwallowedException")
fun announceLedgerFromJson(json: String?): List<AnnounceLedgerEntry> {
    if (json.isNullOrEmpty()) return emptyList()
    return try {
        val array = JSONArray(json)
        (0 until array.length()).mapNotNull { i ->
            val o = array.getJSONObject(i)
            val op = enumOrNull<AnnounceOp>(o.optStringOrNull("op")) ?: return@mapNotNull null
            val state = enumOrNull<AnnounceLedgerState>(o.optStringOrNull("state")) ?: return@mapNotNull null
            val txids = o.optJSONArray("txids")
            AnnounceLedgerEntry(
                rKey = o.optStringOrNull("r_key"),
                rAddr = o.getString("r_addr"),
                sKey = o.optStringOrNull("s_key"),
                sAddr = o.getString("s_addr"),
                op = op,
                state = state,
                txids = txids?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList(),
                at = o.optLong("at", 0L),
            )
        }
    } catch (e: Exception) {
        emptyList()
    }
}
