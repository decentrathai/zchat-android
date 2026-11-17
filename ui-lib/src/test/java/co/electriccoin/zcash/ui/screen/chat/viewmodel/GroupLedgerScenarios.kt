package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.screen.chat.model.GroupMessageType
import co.electriccoin.zcash.ui.screen.chat.model.InviteStatus
import co.electriccoin.zcash.ui.screen.chat.model.MemberStatus
import co.electriccoin.zcash.ui.screen.chat.model.ZMSGGroupProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.fail

// F8-G (ZC-112 part 2, ZC-055, ZC-082, ZC-083, ZC-619): the steps the group ledger tests share. Each drives the real
// GroupViewModel entry point a user reaches (Rotate key, Remove, Add member, send a message, Resend, Introduce them, a
// group open) and answers its price question the way the test says.

/** Rotate key on [s]: "Rotate anyway" if the no-session question is asked, and Pay on the price question. */
internal suspend fun GroupLedgerWorld.rotate(s: GroupScreen) {
    run(s) { s.vm.rotateGroupKey(MA3.GID) }
    if (s.vm.rekeyUnkeyableConfirmation.value != null) run(s) { s.vm.confirmRekeyUnkeyable() }
    if (s.vm.paidActionConfirmation.value != null) confirm(s)
}

/** Remove [member] on [s], with every question answered yes. */
internal suspend fun GroupLedgerWorld.remove(
    s: GroupScreen,
    member: String,
) {
    run(s) { s.vm.kickMember(MA3.GID, member) }
    if (s.vm.rekeyUnkeyableConfirmation.value != null) run(s) { s.vm.confirmRekeyUnkeyable() }
    if (s.vm.paidActionConfirmation.value != null) confirm(s)
}

/** Send [text] to the group from [s], with Pay on the price question. */
internal suspend fun GroupLedgerWorld.sendMessage(
    s: GroupScreen,
    text: String,
) {
    run(s) { s.vm.sendGroupMessage(MA3.GID, text) }
    if (s.vm.paidActionConfirmation.value != null) confirm(s)
}

/** Add [member] to the group from [s] (an on-chain invite), with Pay on the price question. */
internal suspend fun GroupLedgerWorld.addMember(
    s: GroupScreen,
    member: String,
) {
    run(s) { s.vm.addMemberToGroup(MA3.GID, member) }
    if (s.vm.paidActionConfirmation.value != null) confirm(s)
}

/** Resend on [member]'s "Invite failed" badge, with Pay on the price question. Returns whether a price was asked. */
internal suspend fun GroupLedgerWorld.resendInvite(
    s: GroupScreen,
    member: String,
): Boolean {
    run(s) { s.vm.resendInvite(MA3.GID, member) }
    val asked = s.vm.paidActionConfirmation.value != null
    if (asked) confirm(s)
    return asked
}

/** A group open on [s] (the screen load, the history scan, the owed-key pass under the standing consent). */
internal suspend fun GroupLedgerWorld.open(s: GroupScreen) = run(s) { s.vm.loadGroupDetail(MA3.GID) }

/** The stored transactions that carry a memo of [type] to [recipient]. */
internal fun GroupLedgerWorld.storedOf(
    type: GroupMessageType,
    recipient: String? = null,
): List<GroupLedgerSeam.Stored> =
    synchronized(seam.stored) {
        seam.stored.filter {
            ZMSGGroupProtocol.parseMessageType(it.memo.memo) == type && (recipient == null || it.memo.recipient == recipient)
        }
    }

/** The proposals so far that carry a memo of [type] to [recipient]. */
internal fun GroupLedgerWorld.proposalsOf(
    type: GroupMessageType,
    recipient: String? = null,
): Int =
    synchronized(seam.proposals) {
        seam.proposals.count { ZMSGGroupProtocol.parseMessageType(it.memo) == type && (recipient == null || it.recipient == recipient) }
    }

/** The key number a stored GROUP_KEY memo carries. */
internal fun keyEpochOf(memo: String): Int? =
    ZMSGGroupProtocol.parsePayload(memo)?.let { ZMSGGroupProtocol.parseGroupKeyPayload(MA3.GID, it)?.epoch }

/**
 * A rotation whose three key memos were stored and never mined, proven: every key memo of the new number is a failed
 * entry. Returns the "Key not delivered" badges [s] shows, or fails with what the phone holds instead.
 */
internal suspend fun GroupLedgerWorld.keyMemosProvenNotSent(
    s: GroupScreen,
    chat: ChatViewModel,
): Map<String, Any> {
    storedNeverMined()
    rotate(s)
    assertEquals("fixture sanity: the rotation paid one key memo per member", 3, storedOf(GroupMessageType.GROUP_KEY).size)
    s.vm.consumeGroupSendEvent()
    walletLists()
    twoPassesPastExpiry(chat)
    val badges = keyBadges(s)
    if (badges.isEmpty()) {
        fail(
            "three key messages of a rotation were stored, never mined and are past their expiry on two passes at rising " +
                "tips, but Group Settings shows no \"Key not delivered\" badge (ledger: ${ledger.all()}): the roster " +
                "says every member has the new key, for good",
        )
    }
    return badges
}

/** An invite to [member] (added to the group now) that was stored and never mined, with the wallet listing it. */
internal suspend fun GroupLedgerWorld.inviteThatNeverMines(
    s: GroupScreen,
    member: String,
) {
    storedNeverMined()
    addMember(s, member)
    assertEquals("fixture sanity: the invite was paid on-chain once", 1, storedOf(GroupMessageType.GROUP_INVITE, member).size)
    assertEquals("fixture sanity: the row is invited and shown as sent", InviteStatus.SENT, row(member).inviteStatus)
    assertEquals(MemberStatus.INVITED, row(member).status)
    s.vm.consumeGroupSendEvent()
    walletLists()
}

/** The ledger entries of [kind] for [member] (a member subject). */
internal fun GroupLedgerWorld.entries(
    kind: String,
    member: String? = null,
): List<EntryView> = ledger.of(kind).filter { member == null || it.subject == memberSubject(member) }
