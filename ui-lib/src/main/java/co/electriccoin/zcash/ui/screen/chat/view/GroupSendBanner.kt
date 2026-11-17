package co.electriccoin.zcash.ui.screen.chat.view

import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.screen.chat.model.ChatSendCopy
import co.electriccoin.zcash.ui.screen.chat.viewmodel.GroupSendResult

/**
 * The text of the group chat's banner for [result], in the phone's language. [string] and [plural] read the string
 * and plurals resources (Resources.getString / getQuantityString); GroupDetailView passes the screen's resources, a
 * test passes the English files.
 *
 * Round 8 D8 (REVIEW_7C F3): moved out of GroupDetailView unchanged, except that a failure a refusal no balance can
 * fix caused (sealed Orchard, Keystone, Ironwood still confirming, a public crossing; see groupSendRefusal) names that
 * refusal instead of "check your balance and try again" / "…and tap Resend", and a rotation it stopped is not called
 * "retrying automatically". The refusal's sentence is shown through ChatSendCopy.localizedResource.
 */
internal fun groupSendBannerText(
    result: GroupSendResult,
    string: (id: Int) -> String,
    plural: (id: Int, quantity: Int, args: List<Any>) -> String,
): String {
    fun localized(text: String): String = ChatSendCopy.localizedResource(text)?.let(string) ?: text
    return when (result) {
        // R6-1: say WHO has not joined yet, in the phone's language; nothing was sent or spent.
        // Round 8 P5 (PHONE_TRIAGE F5a): a member left out because they can't read new messages yet (owed the key, no
        // secure session) is still in the group, so "nobody else is in this group" is not said while there is one.
        is GroupSendResult.NoActiveRecipients ->
            when {
                // ZC-757: a rebuilt group counts a member only once they wrote here since; "has not joined" would be false.
                result.rebuilt -> string(R.string.group_send_rebuilt_nobody_wrote)
                result.notJoined.isEmpty() && result.cannotRead.isEmpty() -> string(R.string.group_send_nobody_else)
                result.notJoined.isEmpty() ->
                    plural(R.plurals.group_send_cannot_read, result.cannotRead.size, listOf(result.cannotRead.joinToString(", ")))
                else ->
                    listOfNotNull(
                        plural(R.plurals.group_send_not_joined, result.notJoined.size, listOf(result.notJoined.joinToString(", "))),
                        result.cannotRead.takeIf { it.isNotEmpty() }?.let {
                            plural(R.plurals.group_members_cannot_read, it.size, listOf(it.size))
                        },
                    ).joinToString("\n")
            }
        // Round 8 P6 (PHONE_TRIAGE F5b): this phone is no longer in the group; nothing was sent or spent.
        is GroupSendResult.NotInGroup -> string(if (result.removed) R.string.group_you_were_removed else R.string.group_you_left)
        // ZC-757 (G1, G6): a rebuilt group before its new key, or after a new key no member was sent; nothing was sent.
        is GroupSendResult.NeedsNewKey ->
            string(if (result.noReader) R.string.group_rebuilt_no_reader else R.string.group_send_needs_new_key)
        // R6-3: say how much to cut, in the phone's language; nothing was sent or spent.
        is GroupSendResult.MessageTooLong -> plural(R.plurals.group_send_too_long, result.overBy, listOf(result.overBy))
        // R6-3 (round 5 report §5 item 6): every banner in the phone's language. Round 8 D8: or the refusal itself.
        is GroupSendResult.AllFailed -> result.refusal?.let(::localized) ?: string(R.string.group_send_all_failed)
        // R5-1: declining the cost is not a failure — say so, and say the text is safe.
        is GroupSendResult.CostDeclined -> string(R.string.group_message_cost_not_approved)
        is GroupSendResult.PartialDelivery ->
            plural(R.plurals.group_send_partial, result.total, listOf(result.sent, result.total))
        is GroupSendResult.InviteFailed ->
            if (result.refusal != null) {
                plural(R.plurals.group_invite_refused, result.addresses.size, listOf(result.addresses.size)) +
                    "\n" + localized(result.refusal)
            } else {
                plural(R.plurals.group_invite_failed, result.addresses.size, listOf(result.addresses.size))
            }
        // B1 (GPT-6 Q3): the submit started and may still be broadcast. Not failed: nothing is kept or offered to send
        // again. C4 (B1 review item 3): worded for a message or an invite, counting members, and naming the members of
        // the same send that provably got nothing.
        // Round 8 MA3 (member announce): an "Introduce them" run, counted in announces: sent, may have been sent (never
        // paid again), not sent (nothing spent for them).
        is GroupSendResult.MaybeSent -> {
            if (result.kind == GroupSendResult.MaybeSentKind.INTRODUCE) {
                return listOfNotNull(
                    result.sent.takeIf { it > 0 }?.let { plural(R.plurals.group_introduce_outcome_sent, it, listOf(it)) },
                    result.members.takeIf { it > 0 }?.let { plural(R.plurals.group_introduce_outcome_maybe, it, listOf(it)) },
                    result.notSent.takeIf { it > 0 }?.let { plural(R.plurals.group_introduce_outcome_failed, it, listOf(it)) },
                ).joinToString("\n")
            }
            val invite = result.kind == GroupSendResult.MaybeSentKind.INVITE
            listOfNotNull(
                plural(
                    if (invite) R.plurals.group_send_maybe_sent_invite else R.plurals.group_send_maybe_sent_message,
                    result.members,
                    listOf(result.members),
                ),
                result.notSent.takeIf { it > 0 }?.let {
                    plural(
                        if (invite) R.plurals.group_send_maybe_sent_invite_not_sent else R.plurals.group_send_maybe_sent_message_not_sent,
                        it,
                        listOf(it),
                    )
                },
            ).joinToString("\n")
        }
        // ZC-420-G: sent, and for these members only a public relay took the copy (ZChat 2.12.4 does not read it); shown
        // after the send and again at each group open while a copy is still not confirmed. No count, so no plural.
        is GroupSendResult.RelayUnconfirmed ->
            string(R.string.group_send_relay_unconfirmed).format(result.names.joinToString(", "))
        // D3 soft-warn: the kick/rotation committed, but some members haven't received the new key yet (they can
        // still be read by a removed member until repaired). C1: members whose key memo MAY have been sent are named
        // apart: not re-sent, not confirmed. Round 8 D8: a refusal is named, and then nothing claims a retry. Round 8 P5
        // (PHONE_TRIAGE F5a): members with no secure session are named apart too; nothing can send them the key until a
        // key exchange with them completes.
        is GroupSendResult.RemovalIncomplete ->
            listOfNotNull(
                result.pendingMembers.takeIf { it > 0 }?.let {
                    plural(
                        if (result.refusal != null) R.plurals.group_removal_incomplete_refused else R.plurals.group_removal_incomplete,
                        it,
                        listOf(it),
                    )
                },
                result.refusal?.let(::localized),
                result.noSessionMembers.takeIf { it > 0 }?.let {
                    plural(R.plurals.group_removal_no_session, it, listOf(it))
                },
                result.unconfirmedMembers.takeIf { it > 0 }?.let {
                    plural(R.plurals.group_removal_unconfirmed, it, listOf(it))
                },
            ).joinToString("\n")
    }
}
