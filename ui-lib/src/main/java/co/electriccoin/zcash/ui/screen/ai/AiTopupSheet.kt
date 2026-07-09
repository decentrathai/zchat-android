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
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import co.electriccoin.zcash.ui.design.util.AndroidQrCodeImageGenerator
import co.electriccoin.zcash.ui.design.util.JvmQrCodeGenerator
import co.electriccoin.zcash.ui.design.util.QrCodeColors
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
                .padding(20.dp),
        ) {
            Text(
                text = "Top up Shielded AI",
                color = cc.primary,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Pay ZEC → AI credits. 15% margin over Venice's costs.",
                color = cc.textSecondary,
                fontSize = 12.sp,
            )
            Spacer(modifier = Modifier.height(16.dp))

            Text("Amount (USD)", color = cc.textTertiary, fontSize = 11.sp)
            Spacer(modifier = Modifier.height(6.dp))
            var customAmountText by remember { mutableStateOf("") }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                tiers.forEach { tier ->
                    val isSelected = selectedAmount == tier && customAmountText.isEmpty()
                    Button(
                        onClick = {
                            selectedAmount = tier
                            customAmountText = ""
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
                value = customAmountText,
                onValueChange = { raw ->
                    // Allow only digits + at most one dot — keep it integer-friendly for USD.
                    val cleaned = raw.filter { it.isDigit() || it == '.' }
                    val normalized = cleaned.indexOf('.').let { idx ->
                        if (idx < 0) cleaned else cleaned.substring(0, idx + 1) + cleaned.substring(idx + 1).filter { it.isDigit() }
                    }
                    customAmountText = normalized
                    val parsed = normalized.toDoubleOrNull()
                    // Downstream tiers are whole USD (Int). Require >= $1.00 and round to the nearest
                    // dollar — never silently coerce a sub-dollar amount to $1 (which made the field show
                    // "0.50" while the instructions claimed "$1 USD"). Sub-$1 input shows a hint instead.
                    selectedAmount = when {
                        parsed == null -> null
                        parsed < 1.0 -> null
                        // Clamp on the Long BEFORE narrowing to Int — Math.round returns a Long, and
                        // a huge fat-fingered amount (> 2^31) would otherwise wrap to a bogus/negative
                        // tier. Upper bound mirrors AiApiClient.MAX_USD.
                        else -> Math.round(parsed).coerceIn(1L, 1_000_000L).toInt()
                    }
                },
                placeholder = { Text("Custom amount (USD)", color = cc.textTertiary, fontSize = 13.sp) },
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
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    keyboardType = androidx.compose.ui.text.input.KeyboardType.Number,
                ),
            )

            // Validation hint: a typed-but-too-small amount yields no tier (selectedAmount == null) —
            // tell the user the minimum instead of silently snapping the displayed instructions to $1.
            val customBelowMin = customAmountText.isNotEmpty() &&
                (customAmountText.toDoubleOrNull()?.let { it > 0.0 && it < 1.0 } == true)
            if (customBelowMin) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "Minimum top-up is \$1.00.",
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

                Text("Amount", color = cc.textTertiary, fontSize = 11.sp)
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = if (amountZec != null) {
                        "\$$tier USD ≈ ${amountZec.stripTrailingZeros().toPlainString()} ZEC"
                    } else {
                        "\$$tier USD worth of ZEC (current ZEC/USD price unavailable)"
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
                        Text("Pay with this wallet", fontWeight = FontWeight.Bold)
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                }

                // SECONDARY: copy the address + memo to pay from a DIFFERENT wallet.
                Text("Or send from another wallet — shielded address", color = cc.textTertiary, fontSize = 11.sp)
                Spacer(modifier = Modifier.height(4.dp))
                CopyableField(label = address, onCopy = {
                    clipboard.setText(AnnotatedString(address))
                    Toast.makeText(context, "Address copied", Toast.LENGTH_SHORT).show()
                }, cc = cc)
                Spacer(modifier = Modifier.height(10.dp))

                Text("Memo (REQUIRED — credits won't apply without it)", color = cc.textTertiary, fontSize = 11.sp)
                Spacer(modifier = Modifier.height(4.dp))
                CopyableField(label = memo, onCopy = {
                    clipboard.setText(AnnotatedString(memo))
                    Toast.makeText(context, "Memo copied", Toast.LENGTH_SHORT).show()
                }, cc = cc)
                Spacer(modifier = Modifier.height(12.dp))

                // TERTIARY: QR for scanning with an external wallet.
                if (zip321Uri != null) {
                    Text("Or scan this QR with any ZEC wallet", color = cc.textTertiary, fontSize = 11.sp)
                    Spacer(modifier = Modifier.height(8.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(),
                        contentAlignment = Alignment.Center,
                    ) {
                        TopupQrCode(uri = zip321Uri)
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                }

                Text(
                    text = "Credits land within a few confirmations. Memo carries your account ID — " +
                        "do not edit it.",
                    color = cc.textTertiary,
                    fontSize = 11.sp,
                )
            } else {
                Text(
                    text = "Pick an amount to reveal the payment instructions.",
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
                        Text("Top-up history", color = cc.textSecondary, fontWeight = FontWeight.SemiBold)
                    }
                } else {
                    Spacer(modifier = Modifier.size(1.dp))
                }
                TextButton(onClick = onDismiss) {
                    Text("Close", color = cc.primary, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@Composable
private fun TopupQrCode(uri: String) {
    // Render at a fixed 240dp — enough resolution to scan reliably from across the room
    // without making the dialog unwieldy on small phones.
    val sizePx = 720
    val colors = QrCodeColors(
        background = androidx.compose.ui.graphics.Color.White,
        foreground = androidx.compose.ui.graphics.Color.Black,
        border = androidx.compose.ui.graphics.Color.Unspecified,
    )
    val bitmap = remember(uri) {
        val pixels = JvmQrCodeGenerator.generate(uri, sizePx)
        AndroidQrCodeImageGenerator.generate(pixels, sizePx, colors).asImageBitmap()
    }
    androidx.compose.foundation.Image(
        bitmap = bitmap,
        contentDescription = "Payment QR code",
        modifier = Modifier
            .size(240.dp)
            .background(androidx.compose.ui.graphics.Color.White)
            .padding(8.dp),
    )
}

@Composable
private fun CopyableField(
    label: String,
    onCopy: () -> Unit,
    cc: co.electriccoin.zcash.ui.screen.chat.view.ChatColors,
) {
    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        OutlinedTextField(
            value = label,
            onValueChange = {},
            readOnly = true,
            modifier = Modifier.weight(1f),
            shape = RoundedCornerShape(8.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = cc.bgInput,
                unfocusedContainerColor = cc.bgInput,
                focusedTextColor = cc.textPrimary,
                unfocusedTextColor = cc.textPrimary,
                focusedBorderColor = cc.borderActive,
                unfocusedBorderColor = cc.borderDefault,
            ),
        )
        Spacer(modifier = Modifier.padding(2.dp))
        TextButton(onClick = onCopy) {
            Text("Copy", color = cc.primary)
        }
    }
}
