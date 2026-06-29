package co.electriccoin.zcash.ui.screen.chat.view

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AttachMoney
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cash.z.ecc.android.sdk.ext.convertZecToZatoshi
import cash.z.ecc.android.sdk.model.Zatoshi
import co.electriccoin.zcash.ui.design.theme.colors.NightwireColors
import co.electriccoin.zcash.ui.design.theme.typography.RajdhaniFontFamily
import co.electriccoin.zcash.ui.screen.chat.model.MemoTemplate
import co.electriccoin.zcash.ui.screen.chat.model.PaymentRequestInfo
import java.text.DecimalFormat

/**
 * Dialog composables for ZCHAT.
 * Extracted from ChatDetailView for better organization.
 */

// ==========================================
// PAYMENT DIALOG
// ==========================================

@Composable
internal fun PaymentDialog(
    balance: Zatoshi,
    zecPriceUsd: Double?,
    recipientName: String,
    prefilledTemplate: MemoTemplate? = null,
    onDismiss: () -> Unit,
    onSendPayment: (amountZec: Double, memo: String) -> Unit
) {
    // Pre-fill from template if provided
    val initialAmount = prefilledTemplate?.let {
        val zecAmount = it.getZecAmount(zecPriceUsd)
        if (zecAmount > 0) String.format("%.8f", zecAmount).trimEnd('0').trimEnd('.') else ""
    } ?: ""

    var amountText by remember(prefilledTemplate) { mutableStateOf(initialAmount) }
    var memo by remember(prefilledTemplate) { mutableStateOf(prefilledTemplate?.memo ?: "") }
    var splitEnabled by remember { mutableStateOf(false) }
    var splitCount by remember { mutableIntStateOf(2) }

    val amountZec = amountText.toDoubleOrNull() ?: 0.0
    val perPersonAmount = if (splitEnabled && splitCount > 0) amountZec / splitCount else amountZec
    val amountUsd = zecPriceUsd?.let { amountZec * it }
    val perPersonUsd = zecPriceUsd?.let { perPersonAmount * it }

    // Balance in ZEC
    val balanceZec = balance.value.toDouble() / 100_000_000.0
    val hasEnoughBalance = amountZec <= balanceZec && amountZec > 0

    val decimalFormat = remember { DecimalFormat("#,##0.00") }
    val zecFormat = remember { DecimalFormat("#,##0.########") }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = chatColors().bgElevated,
        shape = RoundedCornerShape(NightwireColors.RadiusModal),
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (prefilledTemplate != null) {
                        Text(
                            text = prefilledTemplate.emoji,
                            fontSize = 24.sp
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    Text(
                        text = prefilledTemplate?.name ?: "Send Payment",
                        fontSize = 20.sp,
                        color = chatColors().textPrimary
                    )
                }
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = "Close", tint = chatColors().textSecondary)
                }
            }
        },
        text = {
            Column(
                // Scroll + IME-pad the dialog body so the soft keyboard can't hide the lower buttons
                // or push the type-selector off-screen. A Material3 AlertDialog doesn't react to IME
                // insets, so without this the Number/Decimal keyboard overlapped the confirm/cancel
                // buttons (no way to scroll to them) and shoved the Block/Payment/Secret tabs out of
                // reach once a field was focused. (#bug-composer-keyboard)
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .imePadding(),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Template info banner
                if (prefilledTemplate != null) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = chatColors().primary.copy(alpha = 0.15f)
                        ),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = prefilledTemplate.emoji,
                                fontSize = 20.sp
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Text(
                                    text = "Quick Pay: ${prefilledTemplate.name}",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = prefilledTemplate.getDisplayAmount(),
                                    fontSize = 13.sp,
                                    color = chatColors().textSecondary
                                )
                            }
                        }
                    }
                }

                // Recipient
                Text(
                    text = "To: $recipientName",
                    fontSize = 15.sp,
                    color = chatColors().textSecondary
                )

                // Amount input
                OutlinedTextField(
                    value = amountText,
                    onValueChange = { newValue ->
                        // Only allow valid decimal input
                        if (newValue.isEmpty() || newValue.matches(Regex("^\\d*\\.?\\d*$"))) {
                            amountText = newValue
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Amount (ZEC)") },
                    placeholder = { Text("0.00") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    leadingIcon = {
                        Text(
                            text = "Ⓩ",
                            fontSize = 20.sp,
                            color = chatColors().primary
                        )
                    },
                    supportingText = {
                        if (amountUsd != null && amountZec > 0) {
                            Text("≈ $${decimalFormat.format(amountUsd)} USD")
                        }
                    },
                    isError = amountZec > 0 && !hasEnoughBalance
                )

                // Balance display
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = chatColors().bgInput
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Available:",
                            fontSize = 15.sp,
                            color = chatColors().textSecondary
                        )
                        Text(
                            text = "${zecFormat.format(balanceZec)} ZEC",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }

                // Split payment section
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = if (splitEnabled)
                            chatColors().primary.copy(alpha = 0.1f)
                        else
                            chatColors().bgElevated
                    )
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(
                                    text = "Split Payment",
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = "Divide total among people",
                                    fontSize = 13.sp,
                                    color = chatColors().textSecondary
                                )
                            }
                            Switch(
                                checked = splitEnabled,
                                onCheckedChange = { splitEnabled = it }
                            )
                        }

                        if (splitEnabled) {
                            Spacer(modifier = Modifier.height(12.dp))

                            // People counter
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                IconButton(
                                    onClick = { if (splitCount > 2) splitCount-- },
                                    enabled = splitCount > 2,
                                    modifier = Modifier
                                        .size(40.dp)
                                        .clip(CircleShape)
                                        .background(chatColors().bgElevated)
                                ) {
                                    Icon(
                                        Icons.Default.Remove,
                                        contentDescription = "Decrease"
                                    )
                                }

                                Spacer(modifier = Modifier.width(16.dp))

                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(
                                        text = "$splitCount",
                                        fontSize = 24.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = chatColors().primary
                                    )
                                    Text(
                                        text = "people",
                                        fontSize = 13.sp,
                                        color = chatColors().textSecondary
                                    )
                                }

                                Spacer(modifier = Modifier.width(16.dp))

                                IconButton(
                                    onClick = { if (splitCount < 20) splitCount++ },
                                    enabled = splitCount < 20,
                                    modifier = Modifier
                                        .size(40.dp)
                                        .clip(CircleShape)
                                        .background(chatColors().bgElevated)
                                ) {
                                    Icon(
                                        Icons.Default.Add,
                                        contentDescription = "Increase"
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(8.dp))

                            // Per-person amount
                            if (amountZec > 0) {
                                Card(
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = CardDefaults.cardColors(
                                        containerColor = chatColors().primary.copy(alpha = 0.1f)
                                    )
                                ) {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(12.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally
                                    ) {
                                        Text(
                                            text = "Each person pays:",
                                            fontSize = 13.sp,
                                            color = chatColors().textSecondary
                                        )
                                        Text(
                                            text = "${zecFormat.format(perPersonAmount)} ZEC",
                                            fontSize = 17.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = chatColors().primary
                                        )
                                        if (perPersonUsd != null) {
                                            Text(
                                                text = "≈ $${decimalFormat.format(perPersonUsd)} USD",
                                                fontSize = 13.sp,
                                                color = chatColors().textSecondary
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // Memo (optional)
                OutlinedTextField(
                    value = memo,
                    onValueChange = { memo = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Memo (optional)") },
                    placeholder = { Text("Add a note...") },
                    singleLine = true,
                    maxLines = 1
                )

                // Insufficient funds warning
                if (amountZec > 0 && !hasEnoughBalance) {
                    Text(
                        text = "Insufficient balance",
                        fontSize = 13.sp,
                        color = chatColors().error,
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Center
                    )
                }
            }
        },
        confirmButton = {
            FilledTonalButton(
                onClick = { onSendPayment(amountZec, memo) },
                enabled = amountZec > 0 && hasEnoughBalance
            ) {
                Icon(
                    Icons.Default.AttachMoney,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text("Send ${if (amountZec > 0) "${zecFormat.format(amountZec)} ZEC" else ""}")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

// ==========================================
// TIME-LOCK COMPOSER DIALOG
// ==========================================

/**
 * Time-Lock Composer Dialog for creating time-locked messages.
 * Supports 4 lock types: Scheduled, Block Height, Payment, Conditional
 */
@Composable
internal fun TimeLockComposerDialog(
    currentBlockHeight: Long?,
    onDismiss: () -> Unit,
    onSendScheduledMessage: (message: String, unlockTimestamp: Long) -> Unit,
    onSendBlockLockedMessage: (message: String, unlockHeight: Long) -> Unit,
    onSendPaymentLockedMessage: (message: String, requiredZatoshi: Long) -> Unit,
    onSendConditionalMessage: (message: String, answer: String, hint: String) -> Unit
) {
    var selectedTab by remember { mutableIntStateOf(0) }
    var messageText by remember { mutableStateOf("") }

    // Scheduled lock settings
    var scheduledMinutes by remember { mutableStateOf("30") }

    // Block height lock settings
    var blockOffset by remember { mutableStateOf("100") }

    // Payment lock settings
    var paymentAmountZec by remember { mutableStateOf("0.01") }

    // Conditional lock settings
    var conditionalAnswer by remember { mutableStateOf("") }
    var conditionalHint by remember { mutableStateOf("") }

    val tabs = listOf(
        "Schedule" to Icons.Default.Schedule,
        "Block" to Icons.Default.Lock,
        "Payment" to Icons.Default.AttachMoney,
        "Secret" to Icons.Default.Lock
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = chatColors().bgElevated,
        shape = RoundedCornerShape(NightwireColors.RadiusModal),
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "🔐",
                        fontSize = 24.sp
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Time-Locked Message",
                        fontSize = 20.sp,
                        color = chatColors().textPrimary
                    )
                }
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = "Close", tint = chatColors().textSecondary)
                }
            }
        },
        text = {
            Column(
                // Scroll + IME-pad the dialog body so the soft keyboard can't hide the lower buttons
                // or push the type-selector off-screen. A Material3 AlertDialog doesn't react to IME
                // insets, so without this the Number/Decimal keyboard overlapped the confirm/cancel
                // buttons (no way to scroll to them) and shoved the Block/Payment/Secret tabs out of
                // reach once a field was focused. (#bug-composer-keyboard)
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .imePadding(),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Tab selector
                ScrollableTabRow(
                    selectedTabIndex = selectedTab,
                    modifier = Modifier.fillMaxWidth(),
                    edgePadding = 0.dp
                ) {
                    tabs.forEachIndexed { index, (title, icon) ->
                        Tab(
                            selected = selectedTab == index,
                            onClick = { selectedTab = index },
                            text = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        icon,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(title)
                                }
                            }
                        )
                    }
                }

                // Settings based on selected tab
                when (selectedTab) {
                    0 -> ScheduledLockSettings(
                        minutes = scheduledMinutes,
                        onMinutesChange = { scheduledMinutes = it }
                    )
                    1 -> BlockHeightLockSettings(
                        currentBlockHeight = currentBlockHeight,
                        blockOffset = blockOffset,
                        onOffsetChange = { blockOffset = it }
                    )
                    2 -> PaymentLockSettings(
                        amountZec = paymentAmountZec,
                        onAmountChange = { paymentAmountZec = it }
                    )
                    3 -> ConditionalLockSettings(
                        answer = conditionalAnswer,
                        onAnswerChange = { conditionalAnswer = it },
                        hint = conditionalHint,
                        onHintChange = { conditionalHint = it }
                    )
                }

                // Message input
                OutlinedTextField(
                    value = messageText,
                    onValueChange = { messageText = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Secret Message") },
                    placeholder = { Text("Write your time-locked message...") },
                    minLines = 3,
                    maxLines = 5
                )
            }
        },
        confirmButton = {
            val isValid = messageText.isNotBlank() && when (selectedTab) {
                0 -> (scheduledMinutes.toIntOrNull() ?: 0) > 0
                1 -> (blockOffset.toIntOrNull() ?: 0) > 0
                2 -> (paymentAmountZec.toDoubleOrNull() ?: 0.0) > 0
                3 -> conditionalAnswer.isNotBlank()
                else -> false
            }

            FilledTonalButton(
                onClick = {
                    when (selectedTab) {
                        0 -> {
                            val minutes = scheduledMinutes.toIntOrNull() ?: 30
                            val unlockTimestamp = (System.currentTimeMillis() / 1000) + (minutes * 60)
                            onSendScheduledMessage(messageText, unlockTimestamp)
                        }
                        1 -> {
                            val offset = blockOffset.toIntOrNull() ?: 100
                            val targetHeight = (currentBlockHeight ?: 0) + offset
                            onSendBlockLockedMessage(messageText, targetHeight)
                        }
                        2 -> {
                            // Parse the typed amount as BigDecimal (not Double) and convert via the
                            // SDK's DECIMAL128 converter, so the locked zatoshi exactly matches what the
                            // user entered — Double * 1e8 loses precision on common amounts. Falls back
                            // to 0.01 ZEC (1,000,000 zatoshi) on an unparseable/invalid entry.
                            val zatoshi = runCatching {
                                paymentAmountZec.toBigDecimal().convertZecToZatoshi().value
                            }.getOrDefault(1_000_000L)
                            onSendPaymentLockedMessage(messageText, zatoshi)
                        }
                        3 -> {
                            onSendConditionalMessage(messageText, conditionalAnswer, conditionalHint)
                        }
                    }
                },
                enabled = isValid
            ) {
                Icon(
                    Icons.Default.Lock,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text("Send Locked Message")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

// ==========================================
// LOCK SETTINGS COMPOSABLES
// ==========================================

@Composable
private fun ScheduledLockSettings(
    minutes: String,
    onMinutesChange: (String) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = chatColors().bgInput
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = "Schedule Unlock",
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Message will automatically unlock after the specified time",
                fontSize = 13.sp,
                color = chatColors().textSecondary
            )
            Spacer(modifier = Modifier.height(12.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = minutes,
                    onValueChange = { if (it.all { c -> c.isDigit() }) onMinutesChange(it) },
                    modifier = Modifier.width(100.dp),
                    label = { Text("Minutes") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true
                )
                Column {
                    val mins = minutes.toIntOrNull() ?: 0
                    Text(
                        text = when {
                            mins >= 1440 -> "${mins / 1440} days ${(mins % 1440) / 60} hours"
                            mins >= 60 -> "${mins / 60} hours ${mins % 60} min"
                            else -> "$mins minutes"
                        },
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
            // Quick presets
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf("30" to "30m", "60" to "1h", "1440" to "1d", "10080" to "1w").forEach { (value, label) ->
                    Card(
                        modifier = Modifier
                            .clickable { onMinutesChange(value) }
                            .border(
                                width = 1.dp,
                                color = if (minutes == value) chatColors().primary
                                else chatColors().borderDefault,
                                shape = RoundedCornerShape(8.dp)
                            ),
                        colors = CardDefaults.cardColors(
                            containerColor = if (minutes == value)
                                chatColors().primary
                            else
                                Color.Transparent
                        ),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(
                            text = label,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            fontSize = 13.sp
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun BlockHeightLockSettings(
    currentBlockHeight: Long?,
    blockOffset: String,
    onOffsetChange: (String) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = chatColors().bgInput
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = "Block Height Lock",
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Message unlocks at a specific Zcash block height (trustless)",
                fontSize = 13.sp,
                color = chatColors().textSecondary
            )
            Spacer(modifier = Modifier.height(8.dp))
            if (currentBlockHeight != null) {
                Text(
                    text = "Current block: #${currentBlockHeight}",
                    fontSize = 13.sp,
                    color = chatColors().primary
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = blockOffset,
                    onValueChange = { if (it.all { c -> c.isDigit() }) onOffsetChange(it) },
                    modifier = Modifier.width(100.dp),
                    label = { Text("Blocks") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true
                )
                Column {
                    val offset = blockOffset.toIntOrNull() ?: 0
                    val targetBlock = (currentBlockHeight ?: 0) + offset
                    Text(
                        text = "-> Block #$targetBlock",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    // ~75 seconds per block on Zcash
                    val estimatedMinutes = offset * 75 / 60
                    Text(
                        text = "~${if (estimatedMinutes >= 60) "${estimatedMinutes / 60}h ${estimatedMinutes % 60}m" else "${estimatedMinutes}m"}",
                        fontSize = 13.sp,
                        color = chatColors().textSecondary
                    )
                }
            }
        }
    }
}

@Composable
private fun PaymentLockSettings(
    amountZec: String,
    onAmountChange: (String) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = chatColors().bgInput
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = "Payment to Reveal",
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Recipient must pay you to unlock this message",
                fontSize = 13.sp,
                color = chatColors().textSecondary
            )
            Spacer(modifier = Modifier.height(12.dp))
            OutlinedTextField(
                value = amountZec,
                onValueChange = { newValue ->
                    if (newValue.isEmpty() || newValue.matches(Regex("^\\d*\\.?\\d*$"))) {
                        onAmountChange(newValue)
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Required Payment (ZEC)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                singleLine = true,
                leadingIcon = {
                    Text(
                        text = "Z",
                        fontSize = 20.sp,
                        color = chatColors().primary
                    )
                }
            )
            // Quick presets
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf("0.001", "0.01", "0.1", "1.0").forEach { value ->
                    Card(
                        modifier = Modifier
                            .clickable { onAmountChange(value) }
                            .border(
                                width = 1.dp,
                                color = if (amountZec == value) chatColors().primary
                                else chatColors().borderDefault,
                                shape = RoundedCornerShape(8.dp)
                            ),
                        colors = CardDefaults.cardColors(
                            containerColor = if (amountZec == value)
                                chatColors().primary
                            else
                                Color.Transparent
                        ),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(
                            text = "$value ZEC",
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                            fontSize = 13.sp
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ConditionalLockSettings(
    answer: String,
    onAnswerChange: (String) -> Unit,
    hint: String,
    onHintChange: (String) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = chatColors().bgInput
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = "Secret Answer",
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Recipient must answer correctly to unlock the message",
                fontSize = 13.sp,
                color = chatColors().textSecondary
            )
            Spacer(modifier = Modifier.height(12.dp))
            OutlinedTextField(
                value = answer,
                onValueChange = onAnswerChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Secret Answer") },
                placeholder = { Text("The answer only they would know...") },
                singleLine = true
            )
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = hint,
                onValueChange = onHintChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Hint (optional)") },
                placeholder = { Text("Give them a clue...") },
                singleLine = true,
                supportingText = {
                    Text("The hint will be visible to the recipient")
                }
            )
        }
    }
}

// ==========================================
// TEMPLATE PICKER
// ==========================================

/**
 * Horizontal scrollable row of memo templates for quick payments.
 */
@Composable
internal fun TemplatePickerRow(
    templates: List<MemoTemplate>,
    zecPriceUsd: Double?,
    onTemplateSelected: (MemoTemplate) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(
            containerColor = chatColors().bgElevated
        ),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Quick Pay",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold
                )
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.size(24.dp)
                ) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = "Close",
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                templates.forEach { template ->
                    TemplateChip(
                        template = template,
                        zecPriceUsd = zecPriceUsd,
                        onClick = { onTemplateSelected(template) }
                    )
                }
            }
        }
    }
}

@Composable
private fun TemplateChip(
    template: MemoTemplate,
    zecPriceUsd: Double?,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .clickable(onClick = onClick)
            .widthIn(min = 80.dp),
        colors = CardDefaults.cardColors(
            containerColor = chatColors().primary.copy(alpha = 0.3f)
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = template.emoji,
                fontSize = 24.sp
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = template.name,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = template.getDisplayAmount(),
                fontSize = 13.sp,
                color = chatColors().textSecondary
            )
            // Show ZEC equivalent if template is in USD
            if (template.amountUsd != null && zecPriceUsd != null && zecPriceUsd > 0) {
                val zecAmount = template.amountUsd / zecPriceUsd
                Text(
                    text = "~${String.format("%.4f", zecAmount)} ZEC",
                    fontSize = 11.sp,
                    color = chatColors().primary
                )
            }
        }
    }
}

// ==========================================
// PAYMENT REQUEST COMPOSER DIALOG
// ==========================================

@Composable
internal fun PaymentRequestComposerDialog(
    zecPriceUsd: Double?,
    onDismiss: () -> Unit,
    onSendRequest: (amountZatoshi: Long, reason: String) -> Unit
) {
    var amountText by remember { mutableStateOf("") }
    var reason by remember { mutableStateOf("") }
    var useUsd by remember { mutableStateOf(false) }

    val amountValue = amountText.toDoubleOrNull() ?: 0.0
    val amountZec = if (useUsd && zecPriceUsd != null && zecPriceUsd > 0) {
        amountValue / zecPriceUsd
    } else {
        amountValue
    }
    // Convert via BigDecimal (DECIMAL128), not Double * 1e8 — the latter loses precision so the
    // requested zatoshi can differ from the displayed ZEC. valueOf() takes the Double's clean decimal
    // string; an invalid/negative amount yields 0.
    val amountZatoshi = runCatching {
        java.math.BigDecimal.valueOf(amountZec).convertZecToZatoshi().value
    }.getOrDefault(0L)

    val decimalFormat = remember { DecimalFormat("#,##0.00") }
    val zecFormat = remember { DecimalFormat("#,##0.########") }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = chatColors().bgElevated,
        shape = RoundedCornerShape(NightwireColors.RadiusModal),
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "Request Payment",
                        fontSize = 20.sp,
                        color = chatColors().textPrimary
                    )
                }
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = "Close", tint = chatColors().textSecondary)
                }
            }
        },
        text = {
            Column(
                // Scroll + IME-pad the dialog body so the soft keyboard can't hide the lower buttons
                // or push the type-selector off-screen. A Material3 AlertDialog doesn't react to IME
                // insets, so without this the Number/Decimal keyboard overlapped the confirm/cancel
                // buttons (no way to scroll to them) and shoved the Block/Payment/Secret tabs out of
                // reach once a field was focused. (#bug-composer-keyboard)
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .imePadding(),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Currency toggle
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = chatColors().bgInput
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Enter amount in USD",
                            fontSize = 15.sp
                        )
                        Switch(
                            checked = useUsd,
                            onCheckedChange = { useUsd = it },
                            enabled = zecPriceUsd != null
                        )
                    }
                }

                // Amount input
                OutlinedTextField(
                    value = amountText,
                    onValueChange = { newValue ->
                        if (newValue.isEmpty() || newValue.matches(Regex("^\\d*\\.?\\d*$"))) {
                            amountText = newValue
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(if (useUsd) "Amount (USD)" else "Amount (ZEC)") },
                    placeholder = { Text("0.00") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    leadingIcon = {
                        Text(
                            text = if (useUsd) "$" else "Z",
                            fontSize = 20.sp,
                            color = chatColors().primary
                        )
                    },
                    supportingText = {
                        if (amountValue > 0) {
                            if (useUsd && zecPriceUsd != null) {
                                Text("~${zecFormat.format(amountZec)} ZEC")
                            } else if (!useUsd && zecPriceUsd != null) {
                                val usdValue = amountValue * zecPriceUsd
                                Text("~$${decimalFormat.format(usdValue)} USD")
                            }
                        }
                    }
                )

                // Quick amount presets
                Text(
                    text = "Quick amounts:",
                    fontSize = 13.sp,
                    color = chatColors().textSecondary
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val presets = if (useUsd) {
                        listOf("5", "10", "25", "50")
                    } else {
                        listOf("0.01", "0.1", "1", "5")
                    }
                    presets.forEach { preset ->
                        Card(
                            modifier = Modifier
                                .weight(1f)
                                .clickable { amountText = preset }
                                .border(
                                    width = 1.dp,
                                    color = if (amountText == preset) chatColors().primary
                                    else chatColors().borderDefault,
                                    shape = RoundedCornerShape(8.dp)
                                ),
                            colors = CardDefaults.cardColors(
                                containerColor = if (amountText == preset)
                                    chatColors().primary
                                else
                                    Color.Transparent
                            ),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text(
                                text = if (useUsd) "$$preset" else "$preset ZEC",
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 8.dp),
                                fontSize = 13.sp,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }

                // Reason input
                OutlinedTextField(
                    value = reason,
                    onValueChange = { reason = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Reason (optional)") },
                    placeholder = { Text("What's this for?") },
                    singleLine = true,
                    maxLines = 1,
                    supportingText = {
                        Text("e.g., \"Dinner split\", \"Rent\", \"Movie tickets\"")
                    }
                )
            }
        },
        confirmButton = {
            FilledTonalButton(
                onClick = { onSendRequest(amountZatoshi, reason) },
                enabled = amountZatoshi > 0
            ) {
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = if (amountZec > 0) "Request ${zecFormat.format(amountZec)} ZEC" else "Request Payment"
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

// ==========================================
// PAYMENT REQUEST CONTENT
// ==========================================

/**
 * Payment request content displayed in chat bubbles.
 * Shows amount, reason, and a Pay button for incoming requests.
 */
@Composable
internal fun PaymentRequestContent(
    paymentRequest: PaymentRequestInfo,
    zecPriceUsd: Double?,
    isOutgoing: Boolean,
    onPayClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val textColor = if (isOutgoing) Color.White else chatColors().textSecondary
    val accentColor = if (isOutgoing) Color.White.copy(alpha = 0.9f) else chatColors().primary
    val bgColor = if (isOutgoing) Color.White.copy(alpha = 0.15f) else chatColors().primary.copy(alpha = 0.1f)

    Column(modifier = modifier) {
        // Request header
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = "Request",
                fontSize = 24.sp
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = if (isOutgoing) "Payment Request Sent" else "Payment Requested",
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = textColor
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Amount card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = bgColor),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "${paymentRequest.getFormattedAmount()} ZEC",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = accentColor
                )
                if (zecPriceUsd != null) {
                    val usdAmount = paymentRequest.getAmountUsd(zecPriceUsd)
                    if (usdAmount != null) {
                        Text(
                            text = "~$${String.format("%.2f", usdAmount)} USD",
                            fontSize = 13.sp,
                            color = textColor.copy(alpha = 0.7f)
                        )
                    }
                }
            }
        }

        // Reason (if provided)
        if (paymentRequest.reason.isNotBlank()) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "\"${paymentRequest.reason}\"",
                fontSize = 15.sp,
                color = textColor,
                fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }

        // Pay button (only for incoming requests that aren't paid yet)
        if (!isOutgoing && !paymentRequest.isPaid) {
            Spacer(modifier = Modifier.height(12.dp))
            FilledTonalButton(
                onClick = onPayClick,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.AttachMoney,
                    contentDescription = "Pay",
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Pay ${paymentRequest.getFormattedAmount()} ZEC",
                    fontWeight = FontWeight.SemiBold
                )
            }
        }

        // Paid indicator
        if (paymentRequest.isPaid) {
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.DoneAll,
                    contentDescription = "Paid",
                    modifier = Modifier.size(16.dp),
                    tint = chatColors().success
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "Paid",
                    fontSize = 13.sp,
                    color = chatColors().success,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}
