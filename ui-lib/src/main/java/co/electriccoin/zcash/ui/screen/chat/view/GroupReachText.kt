package co.electriccoin.zcash.ui.screen.chat.view

import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.screen.chat.model.GroupConversation
import co.electriccoin.zcash.ui.screen.chat.model.GroupMember
import co.electriccoin.zcash.ui.screen.chat.model.GroupMessageCost
import co.electriccoin.zcash.ui.screen.chat.model.MemberStatus
import co.electriccoin.zcash.ui.screen.chat.model.PaidActionCopy
import java.util.Locale

/**
 * R5-1 (round 4 report §5B) — the composer's cost line for [cost]: what THIS message costs on-chain right now, from the
 * send path's own recipient list and per-memo sizer. [string] and [plural] read the string and plurals resources
 * (Resources.getString / getQuantityString); GroupDetailView passes the screen's resources, a test the English files.
 * [locale] writes the ZEC figure (Round 8 P9, ES-2): GroupDetailView passes rememberDesiredFormatLocale().
 *
 * Round 8 P7 (PHONE_TRIAGE F4): on a phone that is not the group's admin ([GroupMessageCost.knownOnly]) the message goes
 * only to the members this phone has heard from, so the free line never says "every member is reachable", and a paid
 * line ends by saying that members not heard from yet won't get it.
 *
 * ZC-290: when nothing is paid and some member can't read the message yet ([GroupMessageCost.cannotRead], left out of
 * the send), the free line counts the members it goes to ([GroupMessageCost.recipients]) and never speaks for "every
 * member": GroupDetailView shows "N can't read new messages yet" right under it.
 */
internal fun groupMessageCostText(
    cost: GroupMessageCost,
    string: (id: Int) -> String,
    plural: (id: Int, quantity: Int, args: List<Any>) -> String,
    locale: Locale,
): String {
    val zec = PaidActionCopy.zec(cost.totalCost, locale)
    val paid =
        when {
            cost.onChainMessages == 0 && cost.cannotRead > 0 ->
                return plural(
                    if (cost.knownOnly) R.plurals.group_message_cost_free_known_to else R.plurals.group_message_cost_free_to,
                    cost.recipients,
                    listOf(cost.recipients),
                )
            // QA2 Q2-62 (W-137): on a member's phone the free line counts the members this phone has heard from.
            cost.onChainMessages == 0 && cost.knownOnly ->
                return plural(R.plurals.group_message_cost_free_known_count, cost.recipients, listOf(cost.recipients))
            cost.onChainMessages == 0 ->
                return string(R.string.group_message_cost_free)
            cost.onChainMessages == cost.recipients ->
                plural(R.plurals.group_message_cost_all, cost.onChainMessages, listOf(cost.onChainMessages, zec))
            else ->
                plural(R.plurals.group_message_cost_some, cost.onChainMessages, listOf(cost.onChainMessages, cost.recipients, zec))
        }
    return if (cost.knownOnly) paid + " " + string(R.string.group_message_cost_known_suffix) else paid
}

/**
 * R6-3 (round 5 report §5 item 6) — the member count under the group's name, in the phone's language: every member not
 * removed, plus how many have joined while some have not. Round 8 P7 (PHONE_TRIAGE F4): on a phone that is not the
 * group's admin ([knownOnly]) the roster holds only the members it has heard from, so they are "known members".
 */
internal fun groupMemberCountLabel(
    conversation: GroupConversation,
    knownOnly: Boolean,
    plural: (id: Int, quantity: Int, args: List<Any>) -> String,
): String {
    val total = conversation.members.count { it.status != MemberStatus.LEFT }
    val active = conversation.activeMemberCount
    val totalText = plural(if (knownOnly) R.plurals.group_member_count_known else R.plurals.group_member_count, total, listOf(total))
    return if (active < total) {
        totalText + " · " + plural(R.plurals.group_member_active, active, listOf(active))
    } else {
        totalText
    }
}

/**
 * Round 9 R9-5 (REVIEW_R8 F11 = device D-F14) — the member count under the group's name in Group Settings: every member
 * not removed, as "N members", or "N known members" when this phone's roster holds only the members it has heard from
 * ([knownOnly], from GroupMessageCost.knownOnly like the group screen's header).
 */
internal fun groupSettingsMemberCountLabel(
    members: List<GroupMember>,
    knownOnly: Boolean,
    plural: (id: Int, quantity: Int, args: List<Any>) -> String,
): String {
    val total = members.count { it.status != MemberStatus.LEFT }
    return plural(if (knownOnly) R.plurals.group_member_count_known else R.plurals.group_member_count, total, listOf(total))
}
