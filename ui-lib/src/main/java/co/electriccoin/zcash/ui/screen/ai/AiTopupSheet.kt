@file:Suppress("DEPRECATION")

package co.electriccoin.zcash.ui.screen.ai

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.design.component.AmountReadingChooser
import co.electriccoin.zcash.ui.design.component.AmountUnit
import co.electriccoin.zcash.ui.design.component.QrState
import co.electriccoin.zcash.ui.design.component.ZashiQr
import co.electriccoin.zcash.ui.design.component.rememberAmountTextFieldSelection
import co.electriccoin.zcash.ui.design.util.AmountFieldState
import co.electriccoin.zcash.ui.design.util.rememberDesiredFormatLocale
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.screen.chat.view.chatColors
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Top-up bottom sheet — shown when user taps "Top up" in the AI tab.
 *
 * UX:
 *   1. Pick a tier ($5 / $10 / $20 / $100) or type a custom USD amount
 *   2. Show the shielded address + memo + amount as copyable text
 *   3. User pays from any Zcash wallet (Zashi, YWallet, etc.)
 *   4. Backend watcher detects the deposit (Phase 2) — until then, manual admin credit
 *
 * Note: the AI tab balance refreshes itself on resume — once the watcher lands or the
 * admin credits, the next foreground will reflect the new balance.
 */
@Composable
fun AiTopupSheet(
    address: String,
    memo: String,
    tiers: List<Int>,
    onDismiss: () -> Unit,
    zecUsdPrice: Double? = null,
    buildZip321Uri: ((address: String, amountZec: BigDecimal, memo: String) -> String)? = null,
    onPayInWallet: ((zip321Uri: String) -> Unit)? = null,
    /** Opens the top-up HISTORY sheet (past deposits + credit status). Hidden when null. */
    onShowHistory: (() -> Unit)? = null,
) {
    val cc = chatColors()
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    var selectedAmount by remember { mutableStateOf<Int?>(null) }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(cc.surface)
                // Scrollable: the (bigger) QR + emphasized memo block can exceed a small screen's
                // dialog height once a tier is selected.
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
        ) {
            Text(
                text = stringResource(R.string.ai_topup_sheet_title),
                color = cc.primary,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.ai_topup_sheet_sub, 15),
                color = cc.textSecondary,
                fontSize = 12.sp,
            )
            Spacer(modifier = Modifier.height(16.dp))

            Text(stringResource(R.string.ai_amount_usd), color = cc.textTertiary, fontSize = 11.sp)
            Spacer(modifier = Modifier.height(6.dp))
            // F1: the custom field's reading state; the amount that counts is its settled text ("" while a pasted
            // amount waits for a reading). Read with the app's language, as every other amount field.
            // QA2 m8: the holder edits the state it is handed and stores what the machine took through customAmountState.
            val customAmountState = remember { mutableStateOf(AmountFieldState()) }
            var customAmount by customAmountState
            val customSelection = rememberAmountTextFieldSelection(customAmount) { it.shown }
            val customAmountText = customAmount.settled
            val formatLocale = rememberDesiredFormatLocale()
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                tiers.forEach { tier ->
                    val isSelected = selectedAmount == tier && customAmount.shown.isEmpty()
                    Button(
                        onClick = {
                            selectedAmount = tier
                            customAmount = AmountFieldState()
                        },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isSelected) cc.primary else cc.bgInput,
                            contentColor = if (isSelected) cc.textOnAccent else cc.textPrimary,
                        ),
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 8.dp),
                        shape = RoundedCornerShape(8.dp),
                    ) {
                        Text(
                            text = "\$$tier",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                // QA2 m4 (ZC-1759): the field reports the selection before each edit.
                value = customSelection.valueFor(customAmount.shown),
                onValueChange = { fieldValue ->
                    customSelection.onValueChange(
                        fieldValue,
                        // The rules live in UsdAmountInput.kt so they can be tested without Compose.
                        edit = { state, raw, selectionBefore -> acceptUsdAmountInputResult(state, raw, formatLocale, selectionBefore) },
                        onState = { taken ->
                            customAmountState.value = taken
                            selectedAmount = customUsdTier(taken.settled)
                        }
                    )
                },
                placeholder = { Text(stringResource(R.string.ai_custom_amount_usd), color = cc.textTertiary, fontSize = 13.sp) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = cc.bgInput,
                    unfocusedContainerColor = cc.bgInput,
                    focusedTextColor = cc.textPrimary,
                    unfocusedTextColor = cc.textPrimary,
                    focusedBorderColor = cc.borderActive,
                    unfocusedBorderColor = cc.borderDefault,
                ),
                isError = customAmount.isQuestionOpen,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal,
                ),
            )
            AmountReadingChooser(
                raw = customAmount.shown,
                options = customAmount.ask,
                notUsable = customAmount.notUsable,
                error = customAmount.error,
                unit = AmountUnit.USD,
                locale = formatLocale,
                onChoose = {
                    customAmount = customAmount.choose(it, MAX_USD_DECIMALS)
                    selectedAmount = customUsdTier(customAmount.settled)
                },
            )

            // Validation hint: a typed-but-too-small amount yields no tier (selectedAmount == null) —
            // tell the user the minimum instead of silently snapping the displayed instructions to $1.
            val customBelowMin = customAmountText.isNotEmpty() &&
                (customAmountText.toDoubleOrNull()?.let { it > 0.0 && it < 1.0 } == true)
            if (customBelowMin) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = stringResource(R.string.ai_min_topup),
                    color = cc.warning,
                    fontSize = 11.sp,
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            val tier = selectedAmount
            if (tier != null) {
                // Compute ZEC amount for the tier if we have a live price; otherwise show "—".
                val amountZec: BigDecimal? = zecUsdPrice
                    ?.takeIf { it > 0 }
                    ?.let { BigDecimal(tier).divide(BigDecimal.valueOf(it), 8, RoundingMode.HALF_UP) }
                val zip321Uri: String? = if (amountZec != null && buildZip321Uri != null) {
                    runCatching { buildZip321Uri(address, amountZec, memo) }.getOrNull()
                } else null

                Text(stringResource(R.string.ai_amount), color = cc.textTertiary, fontSize = 11.sp)
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = if (amountZec != null) {
                        stringResource(R.string.ai_tier_zec, tier.toString(), amountZec.stripTrailingZeros().toPlainString())
                    } else {
                        stringResource(R.string.ai_tier_zec_unavailable, tier.toString())
                    },
                    color = cc.textPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(modifier = Modifier.height(12.dp))

                // PRIMARY: pay straight from this wallet (on top, per user request).
                if (zip321Uri != null && onPayInWallet != null) {
                    Button(
                        onClick = { onPayInWallet(zip321Uri) },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = cc.primary,
                            contentColor = cc.textOnAccent,
                        ),
                        shape = RoundedCornerShape(10.dp),
                    ) {
                        Text(stringResource(R.string.ai_pay_with_wallet), fontWeight = FontWeight.Bold)
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                }

                // SECONDARY: copy the address + memo to pay from a DIFFERENT wallet.
                Text(stringResource(R.string.ai_or_send_other), color = cc.textTertiary, fontSize = 11.sp)
                Spacer(modifier = Modifier.height(4.dp))
                CopyableField(label = address, onCopy = {
                    clipboard.setText(AnnotatedString(address))
                    // Copying JUST the address is the classic way to lose a top-up: without the memo
                    // the watcher can't attribute the deposit. Hammer the reminder home right here.
                    Toast.makeText(
                        context,
                        context.getString(R.string.ai_address_copied_toast),
                        Toast.LENGTH_LONG,
                    ).show()
                }, cc = cc)
                Spacer(modifier = Modifier.height(10.dp))

                // The memo is what binds the deposit to THIS account — make it impossible to miss:
                // red, bold label + emphasized field (vs. the muted 11sp grey it used to be).
                Text(
                    text = stringResource(R.string.ai_memo_required),
                    color = cc.error,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp,
                )
                Text(
                    text = stringResource(R.string.ai_memo_required_note),
                    color = cc.error,
                    fontSize = 11.sp,
                )
                Spacer(modifier = Modifier.height(4.dp))
                CopyableField(label = memo, onCopy = {
                    clipboard.setText(AnnotatedString(memo))
                    Toast.makeText(context, context.getString(R.string.ai_memo_copied), Toast.LENGTH_SHORT).show()
                }, cc = cc, emphasized = true)
                Spacer(modifier = Modifier.height(12.dp))

                // TERTIARY: QR for scanning with an external wallet — the shared, theme-aware
                // ZashiQr used on the Receive screen (tap-to-fullscreen, no white slab).
                if (zip321Uri != null) {
                    Text(stringResource(R.string.ai_scan_qr), color = cc.textTertiary, fontSize = 11.sp)
                    Spacer(modifier = Modifier.height(8.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(),
                        contentAlignment = Alignment.Center,
                    ) {
                        ZashiQr(
                            state = QrState(
                                qrData = zip321Uri,
                                contentDescription = stringRes(R.string.ai_payment_qr_cd),
                            ),
                            qrSize = 260.dp,
                        )
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                }

                Text(
                    text = stringResource(R.string.ai_credits_note),
                    color = cc.textTertiary,
                    fontSize = 11.sp,
                )
            } else {
                Text(
                    text = stringResource(R.string.ai_pick_amount),
                    color = cc.textTertiary,
                    fontSize = 12.sp,
                )
            }

            Spacer(modifier = Modifier.height(20.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Past deposits + their credit status (top-up payments are filtered out of the
                // chat list, so this is where users verify a payment actually credited).
                if (onShowHistory != null) {
                    TextButton(onClick = onShowHistory) {
                        Text(stringResource(R.string.ai_topup_history), color = cc.textSecondary, fontWeight = FontWeight.SemiBold)
                    }
                } else {
                    Spacer(modifier = Modifier.size(1.dp))
                }
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.ai_close), color = cc.primary, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

/**
 * Read-only value + Copy button. [emphasized] is used for the REQUIRED memo: bigger, bolder text
 * and a red border so it can't be visually skimmed past like a footnote.
 */
@Composable
private fun CopyableField(
    label: String,
    onCopy: () -> Unit,
    cc: co.electriccoin.zcash.ui.screen.chat.view.ChatColors,
    emphasized: Boolean = false,
) {
    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        OutlinedTextField(
            value = label,
            onValueChange = {},
            readOnly = true,
            modifier = Modifier.weight(1f),
            shape = RoundedCornerShape(8.dp),
            textStyle = if (emphasized) {
                TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            } else {
                TextStyle.Default
            },
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = cc.bgInput,
                unfocusedContainerColor = cc.bgInput,
                focusedTextColor = cc.textPrimary,
                unfocusedTextColor = cc.textPrimary,
                focusedBorderColor = if (emphasized) cc.error else cc.borderActive,
                unfocusedBorderColor = if (emphasized) cc.error.copy(alpha = 0.7f) else cc.borderDefault,
            ),
        )
        Spacer(modifier = Modifier.padding(2.dp))
        TextButton(onClick = onCopy) {
            Text(
                text = stringResource(R.string.ai_copy),
                color = if (emphasized) cc.error else cc.primary,
                fontWeight = if (emphasized) FontWeight.Bold else null,
            )
        }
    }
}
