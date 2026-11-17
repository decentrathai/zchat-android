package co.electriccoin.zcash.ui.screen.chat.view

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cash.z.ecc.android.sdk.model.Zatoshi
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.design.util.rememberDesiredFormatLocale
import co.electriccoin.zcash.ui.screen.chat.model.PaidAction
import co.electriccoin.zcash.ui.screen.chat.model.PaidActionConfirmation
import co.electriccoin.zcash.ui.screen.chat.model.PaidActionCopy

/**
 * R2-2 (b) — the question a paid chat or group tap asks before it pays: what it does, how many on-chain
 * transactions it sends and the least they cost in all (SR-A: or, for an exact quote, the exact price and its parts).
 * Nothing is paid unless the user taps [onConfirm].
 */
@Composable
fun PaidActionConfirmDialog(
    confirmation: PaidActionConfirmation,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val locale = rememberDesiredFormatLocale()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.paid_action_title)) },
        text = {
            Column {
                Text(
                    stringResource(PaidActionCopy.actionText(confirmation), *PaidActionCopy.actionArgs(confirmation)),
                    fontSize = 15.sp,
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    when {
                        // ZC-1272: one question for the whole chat names each leg and the most it can pay.
                        confirmation.setupLegCost != null ->
                            context.resources.getQuantityString(
                                R.plurals.paid_action_cost_chat_legs,
                                confirmation.setupLegs,
                                confirmation.setupLegs,
                                PaidActionCopy.zec(confirmation.totalCost, locale),
                                PaidActionCopy.zec(confirmation.setupLegCost, locale),
                                PaidActionCopy.zec(confirmation.messageLegCost ?: Zatoshi(0), locale),
                            )
                        // SR-A S-A3: an exact price names its parts and says it is exact, never "at least".
                        confirmation.exact && confirmation.onChainMessages == 1 ->
                            stringResource(
                                R.string.paid_action_cost_exact,
                                PaidActionCopy.zec(confirmation.totalCost, locale),
                                PaidActionCopy.zec(confirmation.networkFee ?: Zatoshi(0), locale),
                                PaidActionCopy.zec(confirmation.zchatFee, locale),
                                PaidActionCopy.zec(confirmation.delivered, locale),
                            )
                        // SR-A S-A3b: our owed key exchange, then the link, as one exact total.
                        confirmation.exact ->
                            context.resources.getQuantityString(
                                R.plurals.paid_action_cost_exact_many,
                                confirmation.onChainMessages,
                                confirmation.onChainMessages,
                                PaidActionCopy.zec(confirmation.totalCost, locale),
                                PaidActionCopy.zec(confirmation.networkFee ?: Zatoshi(0), locale),
                            )
                        else ->
                            context.resources.getQuantityString(
                                R.plurals.paid_action_cost,
                                confirmation.onChainMessages,
                                confirmation.onChainMessages,
                                PaidActionCopy.zec(confirmation.totalCost, locale),
                            )
                    },
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                )
                // Round 8 MA3 (member announce, design §6.4, §9): a group message paid for 3 or more members is that many
                // transactions, one after another; say about how long it takes.
                if (confirmation.action == PaidAction.GROUP_MESSAGE && confirmation.onChainMessages >= SLOW_NOTE_FROM) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.group_consent_slow_note, introduceMinutesLeft(confirmation.onChainMessages)),
                        fontSize = 13.sp,
                    )
                }
            }
        },
        // QA2 Q2-44: the money button is a filled button that names the quote's total in the words of the line above:
        // exact for an exact quote, "at most" for a whole chat's legs, "at least" for a floor.
        confirmButton = {
            Button(onClick = onConfirm) {
                val total = PaidActionCopy.zec(confirmation.totalCost, locale)
                Text(
                    when {
                        confirmation.setupLegCost != null -> stringResource(R.string.paid_action_confirm_at_most, total)
                        confirmation.exact -> stringResource(R.string.paid_action_confirm_exact, total)
                        else -> stringResource(R.string.paid_action_confirm, total)
                    },
                )
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.paid_action_cancel)) } },
    )
}

/** Round 8 MA3 — from this many paid members on, a group message's quote says how long the sends take. */
private const val SLOW_NOTE_FROM = 3
