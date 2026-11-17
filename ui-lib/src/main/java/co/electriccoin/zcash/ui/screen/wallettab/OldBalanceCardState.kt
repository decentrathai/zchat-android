package co.electriccoin.zcash.ui.screen.wallettab

import androidx.compose.runtime.Immutable
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.common.model.OrchardSweepDecision
import co.electriccoin.zcash.ui.common.model.WalletAccount
import co.electriccoin.zcash.ui.design.util.StringResource
import co.electriccoin.zcash.ui.design.util.stringRes

/**
 * The Wallet tab's "Old balance" card (sweep spec §1.1, E1). It is shown whenever the sealed Orchard pool
 * holds anything, so every refusal that points to it is true. Its button opens the "Move old balance"
 * sheet: "Move old balance" when a move can be prepared, "Details" otherwise. Only the card for a balance
 * too small to move can be dismissed (owner decision 3, 2026-09-10).
 */
@Immutable
data class OldBalanceCardState(
    val title: StringResource,
    val body: StringResource,
    val action: StringResource,
    val isDismissible: Boolean,
)

/**
 * The card for [decision], or null when there is none: no Orchard, or a too-small balance the user
 * dismissed while it was exactly [dismissedAtZatoshi]. A different balance brings the card back.
 */
fun oldBalanceCard(
    decision: OrchardSweepDecision,
    dismissedAtZatoshi: Long?,
): OldBalanceCardState? {
    val title = stringRes(R.string.move_old_balance_card_title)
    val details = stringRes(R.string.move_old_balance_details)
    return when (decision) {
        OrchardSweepDecision.None -> null
        is OrchardSweepDecision.Available ->
            OldBalanceCardState(
                title = title,
                body = stringRes(R.string.move_old_balance_card_body_available, stringRes(decision.orchard)),
                action = stringRes(R.string.move_old_balance_action),
                isDismissible = false,
            )
        is OrchardSweepDecision.WaitForSync ->
            OldBalanceCardState(
                title = title,
                body = stringRes(R.string.move_old_balance_card_body_wait, stringRes(decision.orchard)),
                action = details,
                isDismissible = false,
            )
        is OrchardSweepDecision.Keystone ->
            OldBalanceCardState(
                title = title,
                body = stringRes(R.string.move_old_balance_card_body_keystone, stringRes(decision.orchard)),
                action = details,
                isDismissible = false,
            )
        is OrchardSweepDecision.TooSmall ->
            if (dismissedAtZatoshi == decision.orchard.value) {
                null
            } else {
                OldBalanceCardState(
                    title = title,
                    body = stringRes(R.string.move_old_balance_card_body_too_small, stringRes(decision.orchard)),
                    action = details,
                    isDismissible = true,
                )
            }
    }
}

/** The key a card dismissal is remembered under: one per account, never a secret. */
fun oldBalanceDismissalKey(account: WalletAccount): String =
    account.sdkAccount.accountUuid.value
        .joinToString(separator = "") { byte -> "%02x".format(byte) }
