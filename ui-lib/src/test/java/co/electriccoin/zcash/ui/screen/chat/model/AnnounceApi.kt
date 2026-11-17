package co.electriccoin.zcash.ui.screen.chat.model

import co.electriccoin.zcash.ui.screen.chat.datasource.ZchatPreferences

/*
 * Round 8 MA1 (group member-announce, MEMBER_ANNOUNCE_DESIGN.md v2 §2, §3, §5.0, §6.0) — the test-side view of the
 * new API. The RED commit reached it by reflection (a missing member failed with "MA1: <symbol> missing"); now that
 * the API exists, the Ma* names are aliases of the product types and every call is direct. The assertions of the
 * tests are unchanged.
 */

typealias MaOp = AnnounceOp

typealias MaBy = ActivatedBy

typealias MaLedgerState = AnnounceLedgerState

typealias MaAnn = GroupMemberAnnounce

typealias MaEntry = MemberAnnounceEntry

typealias MaState = AnnounceState

typealias MaMerge = AnnounceMergeResult

typealias MaPair = AnnouncePair

typealias MaLedger = AnnounceLedgerEntry

typealias MaNumbering = AnnounceNumbering

@Suppress("TooManyFunctions", "LongParameterList")
object AnnounceApi {
    // ── wire (ZMSGGroupProtocol, GroupModels) ────────────────────────────────────────────────────────────────

    fun groupMemberAnnounceSignedData(
        groupId: String,
        op: MaOp,
        subject: String,
        n: Long,
        epoch: Int,
        nostrPubkey: String?
    ): String = ZMSGGroupProtocol.groupMemberAnnounceSignedData(groupId, op, subject, n, epoch, nostrPubkey)

    fun createGroupMemberAnnounceMessage(
        groupId: String,
        op: MaOp,
        subject: String,
        n: Long,
        epoch: Int,
        signature: String,
        nostrPubkey: String? = null
    ): String? = ZMSGGroupProtocol.createGroupMemberAnnounceMessage(groupId, op, subject, n, epoch, signature, nostrPubkey)

    fun parseGroupMemberAnnouncePayload(
        groupId: String,
        payload: String
    ): MaAnn? = ZMSGGroupProtocol.parseGroupMemberAnnouncePayload(groupId, payload)

    fun createGroupRosterQueryMessage(groupId: String): String = ZMSGGroupProtocol.createGroupRosterQueryMessage(groupId)

    /** True when the strict GQ parser accepts [payload] for [groupId]. */
    fun parseGroupRosterQueryPayload(
        groupId: String,
        payload: String
    ): Boolean = ZMSGGroupProtocol.parseGroupRosterQueryPayload(groupId, payload) != null

    fun groupLeaveSignedData(
        groupId: String,
        leaver: String,
        ts: Long
    ): String = ZMSGGroupProtocol.groupLeaveSignedData(groupId, leaver, ts)

    fun createGroupLeaveMessage(
        groupId: String,
        leaver: String,
        signature: String?,
        ts: Long
    ): String = ZMSGGroupProtocol.createGroupLeaveMessage(groupId, leaver, signature, ts)

    fun leaveSignature(payload: GroupLeavePayload): String? = payload.signature

    fun isGroupIdShaped(groupId: String): Boolean = GroupInfo.isGroupIdShaped(groupId)

    fun announcerRebuilt(info: GroupInfo): Boolean = info.announcerRebuilt

    // ── pure logic (GroupMemberAnnounce.kt) ──────────────────────────────────────────────────────────────────

    fun applyMemberAnnounce(
        state: MaState,
        roster: List<GroupMember>,
        ann: MaAnn,
        isSelf: Boolean,
        isCreator: Boolean,
        canon: (String) -> String,
        nickname: (String) -> String?
    ): MaMerge =
        co.electriccoin.zcash.ui.screen.chat.model
            .applyMemberAnnounce(state, roster, ann, isSelf, isCreator, canon, nickname)

    fun nextAnnounceNumber(
        storedN: Long,
        nowMs: Long,
        highestOwnSentN: Long
    ): Long =
        co.electriccoin.zcash.ui.screen.chat.model
            .nextAnnounceNumber(storedN, nowMs, highestOwnSentN)

    fun assignAnnounceNumbers(
        groupId: String,
        state: MaState,
        roster: List<GroupMember>,
        counter: Long,
        nowMs: Long,
        highestOwnSentN: Long,
        epoch: Int,
        canon: (String) -> String,
        isSelf: (String) -> Boolean,
        keyOf: (String) -> String?,
        hasSecureLink: (String) -> Boolean
    ): MaNumbering =
        co.electriccoin.zcash.ui.screen.chat.model.assignAnnounceNumbers(
            groupId,
            state,
            roster,
            counter,
            nowMs,
            highestOwnSentN,
            epoch,
            canon,
            isSelf,
            keyOf,
            hasSecureLink
        )

    fun owedMemberAnnounces(
        state: MaState,
        roster: List<GroupMember>,
        ledger: List<MaLedger>,
        canon: (String) -> String,
        isSelf: (String) -> Boolean,
        keyOf: (String) -> String?,
        hasSecureLink: (String) -> Boolean
    ): List<MaPair> =
        co.electriccoin.zcash.ui.screen.chat.model
            .owedMemberAnnounces(state, roster, ledger, canon, isSelf, keyOf, hasSecureLink)

    /** The new ledger, or null when the compare-and-set is refused. */
    fun casAnnounceLedger(
        ledger: List<MaLedger>,
        pair: MaPair,
        expected: MaLedger?,
        new: MaLedger?
    ): List<MaLedger>? =
        co.electriccoin.zcash.ui.screen.chat.model
            .casAnnounceLedger(ledger, pair, expected, new)

    fun announceKeyHash(publicKey: String): String =
        co.electriccoin.zcash.ui.screen.chat.model
            .announceKeyHash(publicKey)

    fun announceStateToJson(state: MaState): String =
        co.electriccoin.zcash.ui.screen.chat.model
            .announceStateToJson(state)

    fun announceStateFromJson(json: String?): MaState =
        co.electriccoin.zcash.ui.screen.chat.model
            .announceStateFromJson(json)

    fun announceLedgerToJson(ledger: List<MaLedger>): String =
        co.electriccoin.zcash.ui.screen.chat.model
            .announceLedgerToJson(ledger)

    fun announceLedgerFromJson(json: String?): List<MaLedger> =
        co.electriccoin.zcash.ui.screen.chat.model
            .announceLedgerFromJson(json)

    // ── storage (ZchatPreferences) ───────────────────────────────────────────────────────────────────────────

    fun <T> withGroupLock(
        zp: ZchatPreferences,
        groupId: String,
        block: () -> T
    ): T = zp.withGroupLock(groupId, block)

    fun getGroupAnnounceState(
        zp: ZchatPreferences,
        groupId: String
    ): MaState = zp.getGroupAnnounceState(groupId)

    fun saveGroupAnnounceState(
        zp: ZchatPreferences,
        groupId: String,
        state: MaState
    ): Boolean = zp.saveGroupAnnounceState(groupId, state)

    fun getGroupAnnounceLedger(
        zp: ZchatPreferences,
        groupId: String
    ): List<MaLedger> = zp.getGroupAnnounceLedger(groupId)

    fun casGroupAnnounce(
        zp: ZchatPreferences,
        groupId: String,
        pair: MaPair,
        expected: MaLedger?,
        new: MaLedger?
    ): Boolean = zp.casGroupAnnounce(groupId, pair, expected, new)

    fun getGroupAnnounceCounter(
        zp: ZchatPreferences,
        groupId: String
    ): Long = zp.getGroupAnnounceCounter(groupId)

    fun raiseGroupAnnounceCounter(
        zp: ZchatPreferences,
        groupId: String,
        n: Long
    ): Boolean = zp.raiseGroupAnnounceCounter(groupId, n)

    fun isGp1Capable(
        zp: ZchatPreferences,
        id: String
    ): Boolean = zp.isGp1Capable(id)

    fun markGp1Capable(
        zp: ZchatPreferences,
        ids: Set<String>
    ): Boolean = zp.markGp1Capable(ids)
}
