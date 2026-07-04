@file:Suppress("DEPRECATION")

package co.electriccoin.zcash.ui.screen.ai

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import co.electriccoin.zcash.ui.screen.chat.view.ChatColors
import co.electriccoin.zcash.ui.screen.chat.view.chatColors
import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Top-up HISTORY sheet — lists the caller's AI-credit ZEC deposits (GET /api/v1/ai/topup/status).
 *
 * These payments are deliberately filtered OUT of the main chat list (they carry the
 * ZMSGConstants.AI_TOPUP_MEMO_PREFIX memo, not a message), so this sheet is the one place users
 * can verify a top-up landed: date, ZEC paid, USD credited, watcher status, and the on-chain txid
 * (tap to copy for block-explorer lookup).
 */
@Composable
fun AiTopupHistorySheet(
    state: TopupHistoryState,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
) {
    val cc = chatColors()
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(cc.surface)
                .padding(20.dp),
        ) {
            Text(
                text = "Top-up history",
                color = cc.primary,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "ZEC deposits credited to your AI balance.",
                color = cc.textSecondary,
                fontSize = 12.sp,
            )
            Spacer(modifier = Modifier.height(14.dp))

            when (state) {
                is TopupHistoryState.Loading -> {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 24.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(color = cc.primary, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(10.dp))
                        Text("Loading top-ups…", color = cc.textSecondary, fontSize = 13.sp)
                    }
                }
                is TopupHistoryState.Error -> {
                    Text(
                        text = "Couldn't load top-up history: ${state.message}",
                        color = cc.error,
                        fontSize = 12.sp,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    TextButton(onClick = onRetry) {
                        Text("Retry", color = cc.primary, fontWeight = FontWeight.SemiBold)
                    }
                }
                is TopupHistoryState.Data -> {
                    if (state.deposits.isEmpty()) {
                        Text(
                            text = "No top-ups yet",
                            color = cc.textPrimary,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Once you pay ZEC to the top-up address, deposits show up here " +
                                "with their credit status.",
                            color = cc.textTertiary,
                            fontSize = 12.sp,
                        )
                    } else {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 400.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            items(state.deposits, key = { it.id }) { entry ->
                                TopupHistoryRow(entry = entry, cc = cc)
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onDismiss) {
                    Text("Close", color = cc.primary, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@Composable
private fun TopupHistoryRow(
    entry: AiTopupEntry,
    cc: ChatColors,
) {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(cc.bgInput)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = formatTopupDate(entry.createdAtMillis),
                color = cc.textSecondary,
                fontSize = 11.sp,
            )
            TopupStatusPill(status = entry.status, cc = cc)
        }
        Spacer(modifier = Modifier.height(4.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = formatZatoshiAsZec(entry.zatoshi),
                color = cc.textPrimary,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                // -1.0 = unknown (e.g. still pending, nothing credited yet) — show a dash, not $0.
                text = if (entry.usdCredited >= 0.0) AiApiClient.usd(entry.usdCredited) else "—",
                color = cc.textSecondary,
                fontSize = 12.sp,
            )
        }
        if (entry.zecTxId.isNotEmpty()) {
            Spacer(modifier = Modifier.height(6.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(6.dp))
                    .clickable {
                        clipboard.setText(AnnotatedString(entry.zecTxId))
                        Toast.makeText(context, "Transaction ID copied", Toast.LENGTH_SHORT).show()
                    }
                    .padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Filled.ContentCopy,
                    contentDescription = "Copy transaction ID",
                    tint = cc.textTertiary,
                    modifier = Modifier.size(13.dp),
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = truncateTxId(entry.zecTxId),
                    color = cc.textTertiary,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * Status pill matching the backend watcher's lifecycle: pending → confirmed → credited (or
 * rejected). Unknown values fall through neutrally instead of crashing or lying.
 */
@Composable
private fun TopupStatusPill(
    status: String,
    cc: ChatColors,
) {
    val (color, label) = when (status.lowercase(Locale.ROOT)) {
        "credited" -> cc.success to "Credited"
        "pending" -> cc.warning to "Pending"
        "confirmed" -> cc.warning to "Confirming"
        "rejected" -> cc.error to "Failed"
        else -> cc.textSecondary to status.ifEmpty { "Unknown" }
    }
    Text(
        text = label,
        color = color,
        fontSize = 10.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(color.copy(alpha = 0.15f))
            .padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

private val TOPUP_DATE_FORMATTER: DateTimeFormatter =
    DateTimeFormatter.ofPattern("MMM d, yyyy · HH:mm").withZone(ZoneId.systemDefault())

private fun formatTopupDate(millis: Long?): String =
    millis?.let { TOPUP_DATE_FORMATTER.format(Instant.ofEpochMilli(it)) } ?: "Unknown date"

/** Exact zatoshi → ZEC via BigDecimal (never Double math on money). */
private fun formatZatoshiAsZec(zatoshi: Long): String =
    BigDecimal(zatoshi).movePointLeft(8).stripTrailingZeros().toPlainString() + " ZEC"

private fun truncateTxId(txId: String): String =
    if (txId.length <= 20) txId else "${txId.take(10)}…${txId.takeLast(6)}"
