package co.electriccoin.zcash.ui.screen.chat.view

import android.graphics.BitmapFactory
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
import androidx.compose.foundation.text.KeyboardActions
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cash.z.ecc.android.sdk.ext.convertZecToZatoshi
import cash.z.ecc.android.sdk.model.Zatoshi
import co.electriccoin.zcash.ui.common.sendattempt.ChainTiming
import co.electriccoin.zcash.ui.design.theme.colors.NightwireColors
import co.electriccoin.zcash.ui.design.theme.typography.RajdhaniFontFamily
import co.electriccoin.zcash.ui.screen.chat.model.MemoTemplate
import co.electriccoin.zcash.ui.screen.chat.model.ChatSendCopy
import co.electriccoin.zcash.ui.screen.chat.model.PaidActionCopy
import co.electriccoin.zcash.ui.screen.chat.model.PaymentQuote
import co.electriccoin.zcash.ui.screen.chat.usecase.ChatSendQuote
import kotlinx.coroutines.delay
import co.electriccoin.zcash.ui.screen.chat.model.PaymentRequestInfo
import co.electriccoin.zcash.ui.screen.chat.model.SplitShare
import co.electriccoin.zcash.ui.screen.chat.model.canAffordPayment
import co.electriccoin.zcash.ui.screen.chat.model.paymentMemoChunks
import co.electriccoin.zcash.ui.screen.chat.util.acceptAmountInputResult
import co.electriccoin.zcash.ui.screen.chat.util.amountFieldTransformation
import co.electriccoin.zcash.ui.screen.chat.util.decimalSeparatorOf
import co.electriccoin.zcash.ui.screen.chat.model.zecDisplay
import java.math.BigDecimal
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.design.util.rememberDesiredFormatLocale
import co.electriccoin.zcash.ui.design.component.AmountReadingChooser
import co.electriccoin.zcash.ui.design.component.AmountUnit
import co.electriccoin.zcash.ui.design.component.rememberAmountTextFieldSelection
import co.electriccoin.zcash.ui.design.util.AmountFieldState
import co.electriccoin.zcash.ui.screen.chat.util.MAX_AMOUNT_DECIMALS
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController

/** Zatoshi per ZEC, for the Payment dialog's display-only Double figures. */
private const val ZATOSHI_PER_ZEC = 100_000_000.0

/** D-6: the Payment dialog prices the amount and memo this long after they stop changing. */
private const val PAYMENT_QUOTE_DEBOUNCE_MS = 400L

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
    // C-5: what can actually reach the peer (Ironwood available). [balance] stays the "Available" figure
    // the dialog always showed; Send is gated on this one.
    peerSendable: Zatoshi = balance,
    // D-6 (fix-all 2.13.0, owner W-10): the exact price of an amount (zatoshi) and memo (ChatViewModel.quotePayment).
    quotePayment: suspend (amountZatoshi: Long, memo: String) -> PaymentQuote,
    onDismiss: () -> Unit,
    // D-6: Send hands on the exact quote of the amount and memo it pays; the send pays that transaction or nothing.
    onSendPayment: (amountZec: Double, memo: String, quote: ChatSendQuote) -> Unit
) {
    // Pre-fill from template if provided
    val initialAmount = prefilledTemplate?.let {
        val zecAmount = it.getZecAmount(zecPriceUsd)
        if (zecAmount > 0) String.format(java.util.Locale.ROOT, "%.8f", zecAmount).trimEnd('0').trimEnd('.') else ""
    } ?: ""

    // F1: the field's reading state. The amount that counts is its settled text, "" while a pasted amount waits for a
    // reading or shows an error, so a raw paste never reaches amountText.
    // QA2 m8: the holder edits the state it is handed and stores what the machine took through amountFieldState.
    val amountFieldState = remember(prefilledTemplate) { mutableStateOf(AmountFieldState.settled(initialAmount)) }
    var amountField by amountFieldState
    val amountSelection = rememberAmountTextFieldSelection(amountField) { it.shown }
    val amountText = amountField.settled
    var memo by remember(prefilledTemplate) { mutableStateOf(prefilledTemplate?.memo ?: "") }

    val amountZec = amountText.toDoubleOrNull() ?: 0.0
    val amountUsd = zecPriceUsd?.let { amountZec * it }

    // Balance in ZEC
    val balanceZec = balance.value.toDouble() / 100_000_000.0
    // C-5: affordability in zatoshi, with the fees. `amountZec <= balanceZec` compared Doubles and left out
    // the platform-fee output and the network fee, so typing the whole balance enabled Send and the SDK
    // refused afterwards. The amount is converted exactly as sendPayment converts it; the fee is the ZIP
    // 317 floor for this payment's outputs (the proposal still decides).
    val amountZatoshi = runCatching { BigDecimal.valueOf(amountZec).convertZecToZatoshi() }.getOrDefault(Zatoshi(0))
    val memoChunks = paymentMemoChunks(memo)
    val hasEnoughBalance = canAffordPayment(amountZatoshi, memoChunks, peerSendable)

    // D-6 (fix-all 2.13.0, owner W-10): ONE exact figure, never "about". The amount and memo are priced
    // PAYMENT_QUOTE_DEBOUNCE_MS after they stop changing (only when the floor above can be paid at all); a quote counts
    // only for the amount and memo it priced, and Send needs an exact one.
    val quoteKey = amountZatoshi.value to memo
    var quoted by remember(prefilledTemplate) { mutableStateOf<Pair<Pair<Long, String>, PaymentQuote>?>(null) }
    LaunchedEffect(quoteKey, hasEnoughBalance) {
        if (amountZatoshi.value <= 0L || !hasEnoughBalance) return@LaunchedEffect
        delay(PAYMENT_QUOTE_DEBOUNCE_MS)
        quoted = quoteKey to quotePayment(amountZatoshi.value, memo)
    }
    val currentQuote = quoted?.takeIf { it.first == quoteKey }?.second
    val exactQuote = currentQuote as? PaymentQuote.Exact

    // R5-4: keyed on the Compose-observed locale. The activity keeps `locale` in configChanges, so a
    // language change does not recreate it; a keyless remember held a DecimalFormat built from the
    // locale at first composition, so these amounts kept the old language's separators until the app
    // was restarted. Display only — the amounts, fees and the affordability check are unchanged.
    val formatLocale = rememberDesiredFormatLocale()
    val decimalFormat = remember(formatLocale) { DecimalFormat("#,##0.00", DecimalFormatSymbols(formatLocale)) }
    val zecFormat = remember(formatLocale) { DecimalFormat("#,##0.########", DecimalFormatSymbols(formatLocale)) }
    // Round 8 P9 (ES-1): the amount field shows the phone's decimal separator; the stored text keeps its '.'.
    val decimalSeparator = decimalSeparatorOf(formatLocale)

    // ZC-1273: Done closes the keyboard, which covered the dialog's buttons.
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val closeKeyboard = {
        focusManager.clearFocus()
        keyboardController?.hide()
        Unit
    }

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
                        text = prefilledTemplate?.name ?: stringResource(R.string.send_payment_dialog_title),
                        fontSize = 20.sp,
                        color = chatColors().textPrimary
                    )
                }
                IconButton(onClick = onDismiss) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = stringResource(R.string.send_payment_dialog_close),
                        tint = chatColors().textSecondary
                    )
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
                                    text = stringResource(R.string.send_payment_dialog_quick_pay, prefilledTemplate.name),
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
                    text = stringResource(R.string.send_payment_dialog_to, recipientName),
                    fontSize = 15.sp,
                    color = chatColors().textSecondary
                )

                // Bridge 3: the payment line (a contact still on ZChat 2.12.4 or older doesn't see this ZEC yet).
                Text(
                    text =
                        stringResource(R.string.upgrade_payment_line, recipientName) + " " +
                            stringResource(R.string.upgrade_payment_not_lost),
                    fontSize = 12.sp,
                    color = chatColors().textSecondary
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
                            text = stringResource(R.string.send_payment_dialog_available),
                            fontSize = 15.sp,
                            color = chatColors().textSecondary
                        )
                        Text(
                            text = "${zecFormat.format(balanceZec)} ZEC",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    // Only Ironwood can pay another person; say so when it is less than "Available".
                    if (peerSendable != balance) {
                        Row(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = stringResource(R.string.send_payment_dialog_can_send_to_others),
                                fontSize = 13.sp,
                                color = chatColors().textSecondary
                            )
                            Text(
                                text = "${zecFormat.format(peerSendable.value.toDouble() / ZATOSHI_PER_ZEC)} ZEC",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }

                // Amount input
                OutlinedTextField(
                    // A typed comma is the decimal point (0,5 is half a ZEC, not 5); a paste that reads two ways asks.
                    // QA2 m4 (ZC-1759): the field reports the selection before each edit.
                    value = amountSelection.valueFor(amountField.shown),
                    onValueChange = { fieldValue ->
                        amountSelection.onValueChange(
                            fieldValue,
                            edit = { state, newValue, selectionBefore ->
                                acceptAmountInputResult(state, newValue, formatLocale, AmountUnit.ZEC, selectionBefore)
                            },
                            onState = { amountFieldState.value = it }
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.send_payment_dialog_amount_label)) },
                    placeholder = { Text("0${decimalSeparator}00") },
                    visualTransformation = amountFieldTransformation(amountField, decimalSeparator),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { closeKeyboard() }),
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
                    isError = amountField.isQuestionOpen || (amountZec > 0 && !hasEnoughBalance)
                )
                AmountReadingChooser(
                    raw = amountField.shown,
                    options = amountField.ask,
                    notUsable = amountField.notUsable,
                    error = amountField.error,
                    unit = AmountUnit.ZEC,
                    locale = formatLocale,
                    onChoose = { amountField = amountField.choose(it, MAX_AMOUNT_DECIMALS) },
                )

                // The fees come on top of the amount. D-6: the exact fees of this amount and memo, in the phone's format, or
                // why there is no exact price yet.
                if (amountZatoshi.value > 0 && hasEnoughBalance) {
                    if (exactQuote != null) {
                        Text(
                            text =
                                if (exactQuote.memoOutputs.value == 0L) {
                                    stringResource(
                                        R.string.send_payment_dialog_fees_exact,
                                        PaidActionCopy.zec(exactQuote.fees, formatLocale),
                                        PaidActionCopy.zec(exactQuote.platformFee, formatLocale),
                                        PaidActionCopy.zec(exactQuote.networkFee, formatLocale),
                                    )
                                } else {
                                    stringResource(
                                        R.string.send_payment_dialog_fees_exact_memos,
                                        PaidActionCopy.zec(exactQuote.fees, formatLocale),
                                        PaidActionCopy.zec(exactQuote.platformFee, formatLocale),
                                        PaidActionCopy.zec(exactQuote.networkFee, formatLocale),
                                        PaidActionCopy.zec(exactQuote.memoOutputs, formatLocale),
                                    )
                                },
                            fontSize = 13.sp,
                            color = chatColors().textSecondary
                        )
                    } else {
                        PaymentQuoteStatus(currentQuote, formatLocale)
                    }
                }

                // KI-3: no split here. Send pays the amount typed above to this contact; splitting a bill is
                // Request Payment's "Split a bill", which asks the contact for their share.

                // Memo (optional)
                OutlinedTextField(
                    value = memo,
                    onValueChange = { memo = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.send_payment_dialog_memo_label)) },
                    placeholder = { Text(stringResource(R.string.send_payment_dialog_memo_placeholder)) },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { closeKeyboard() }),
                    singleLine = true,
                    maxLines = 1
                )

                // Insufficient funds warning
                if (amountZec > 0 && !hasEnoughBalance) {
                    Text(
                        text = stringResource(R.string.send_payment_dialog_not_enough),
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
                onClick = { if (exactQuote != null) onSendPayment(amountZec, memo, exactQuote.quote) },
                enabled = amountZec > 0 && hasEnoughBalance && exactQuote != null
            ) {
                Icon(
                    Icons.Default.AttachMoney,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    if (amountZec > 0) {
                        stringResource(R.string.send_payment_dialog_send_amount, zecFormat.format(amountZec))
                    } else {
                        stringResource(R.string.send_payment_dialog_send)
                    }
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.send_payment_dialog_cancel))
            }
        }
    )
}

/**
 * D-6 (fix-all 2.13.0, owner W-10) — what a payment confirm says when it has no exact price, so Pay stays off: still
 * working it out, the wallet cannot pay it (both figures), Ironwood still confirming, or no price (with the refusal's own
 * text when there is one). Nothing for an exact quote: the confirm shows its figures.
 */
@Composable
internal fun PaymentQuoteStatus(
    quote: PaymentQuote?,
    locale: java.util.Locale,
    modifier: Modifier = Modifier,
) {
    val text: String? =
        when (quote) {
            null -> stringResource(R.string.payment_quote_working)
            is PaymentQuote.Exact -> null
            // ZC-1416: when nothing can go out for this payment, say so instead of "0 ZEC can be sent now".
            is PaymentQuote.NotEnough ->
                if (quote.spendable.value <= 0L) {
                    stringResource(R.string.payment_quote_nothing_sendable, PaidActionCopy.zec(quote.floor, locale))
                } else {
                    stringResource(
                        R.string.payment_quote_not_enough,
                        PaidActionCopy.zec(quote.floor, locale),
                        PaidActionCopy.zec(quote.spendable, locale),
                    )
                }
            PaymentQuote.StillConfirming -> stringResource(R.string.chat_send_pending_balance_wait)
            is PaymentQuote.Unavailable -> {
                val reasonRes = quote.reason?.let { ChatSendCopy.localizedResource(it) }
                when {
                    reasonRes != null -> stringResource(reasonRes)
                    quote.reason != null -> quote.reason
                    else -> stringResource(R.string.payment_quote_unavailable)
                }
            }
        }
    if (text != null) {
        Text(
            text = text,
            fontSize = 13.sp,
            color = if (quote == null) chatColors().textSecondary else chatColors().error,
            modifier = modifier,
        )
    }
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

    // Payment lock settings (F1: the field's reading state; the amount that counts is its settled text)
    var paymentAmount by remember { mutableStateOf(AmountFieldState.settled("0.01")) }
    val paymentAmountZec = paymentAmount.settled

    // Conditional lock settings
    var conditionalAnswer by remember { mutableStateOf("") }
    var conditionalHint by remember { mutableStateOf("") }

    val tabs = listOf(
        stringResource(R.string.timelock_tab_schedule) to Icons.Default.Schedule,
        stringResource(R.string.timelock_tab_block) to Icons.Default.Lock,
        stringResource(R.string.timelock_tab_payment) to Icons.Default.AttachMoney,
        stringResource(R.string.timelock_tab_secret) to Icons.Default.Lock
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
                        text = stringResource(R.string.timelock_title),
                        fontSize = 20.sp,
                        color = chatColors().textPrimary
                    )
                }
                IconButton(onClick = onDismiss) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = stringResource(R.string.timelock_close),
                        tint = chatColors().textSecondary
                    )
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
                        amount = paymentAmount,
                        onAmountChange = { paymentAmount = it }
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
                    label = { Text(stringResource(R.string.timelock_message_label)) },
                    placeholder = { Text(stringResource(R.string.timelock_message_placeholder)) },
                    minLines = 3,
                    maxLines = 5
                )
            }
        },
        confirmButton = {
            val isValid = messageText.isNotBlank() && when (selectedTab) {
                0 -> (scheduledMinutes.toIntOrNull() ?: 0) > 0
                // Block-height lock needs a known current height; without it the target height defaults to
                // (0 + offset), a block in the distant past, so the "locked" message unlocks immediately.
                1 -> currentBlockHeight != null && (blockOffset.toIntOrNull() ?: 0) > 0
                2 -> (paymentAmountZec.toDoubleOrNull() ?: 0.0) > 0
                3 -> conditionalAnswer.isNotBlank()
                else -> false
            }

            FilledTonalButton(
                onClick = {
                    when (selectedTab) {
                        0 -> {
                            val minutes = scheduledMinutes.toIntOrNull() ?: 30
                            // minutes * 60L in Long arithmetic — plain Int `minutes * 60` overflowed for
                            // large values (e.g. 36_000_000 min) and produced an unlock time in the PAST.
                            val unlockTimestamp = (System.currentTimeMillis() / 1000) + (minutes * 60L)
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
                Text(stringResource(R.string.timelock_send))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.timelock_cancel))
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
                text = stringResource(R.string.timelock_schedule_title),
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.timelock_schedule_note),
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
                    label = { Text(stringResource(R.string.timelock_minutes_label)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true
                )
                Column {
                    val mins = minutes.toIntOrNull() ?: 0
                    val resources = LocalContext.current.resources
                    Text(
                        text = when {
                            mins >= 1440 ->
                                resources.getQuantityString(R.plurals.timelock_duration_days, mins / 1440, mins / 1440) + " " +
                                    resources.getQuantityString(
                                        R.plurals.timelock_duration_hours,
                                        (mins % 1440) / 60,
                                        (mins % 1440) / 60
                                    )
                            mins >= 60 ->
                                resources.getQuantityString(R.plurals.timelock_duration_hours, mins / 60, mins / 60) + " " +
                                    stringResource(R.string.timelock_duration_short_minutes, mins % 60)
                            else -> resources.getQuantityString(R.plurals.timelock_duration_minutes, mins, mins)
                        },
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
            // Quick presets. They scroll sideways, so a longer label in another language never squeezes the row.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf(
                    "30" to stringResource(R.string.timelock_preset_30m),
                    "60" to stringResource(R.string.timelock_preset_1h),
                    "1440" to stringResource(R.string.timelock_preset_1d),
                    "10080" to stringResource(R.string.timelock_preset_1w)
                ).forEach { (value, label) ->
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
                text = stringResource(R.string.timelock_block_title),
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.timelock_block_note),
                fontSize = 13.sp,
                color = chatColors().textSecondary
            )
            Spacer(modifier = Modifier.height(8.dp))
            if (currentBlockHeight != null) {
                Text(
                    text = stringResource(R.string.timelock_current_block, currentBlockHeight),
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
                    label = { Text(stringResource(R.string.timelock_blocks_label)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true
                )
                Column {
                    val offset = blockOffset.toIntOrNull() ?: 0
                    val targetBlock = (currentBlockHeight ?: 0) + offset
                    Text(
                        text = stringResource(R.string.timelock_target_block, targetBlock),
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    // ZC-114: each block at its own spacing (75 s before NU7, 25 s from NU7 on), whole minutes as before.
                    val estimatedMinutes = (ChainTiming.secondsBetween(targetBlock - offset, targetBlock) / 60).toInt()
                    Text(
                        text =
                            if (estimatedMinutes >= 60) {
                                stringResource(R.string.timelock_eta_hours_minutes, estimatedMinutes / 60, estimatedMinutes % 60)
                            } else {
                                stringResource(R.string.timelock_eta_minutes, estimatedMinutes)
                            },
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
    amount: AmountFieldState,
    onAmountChange: (AmountFieldState) -> Unit
) {
    // Round 8 P9 (ES-6): the field and the presets follow the phone's language.
    val formatLocale = rememberDesiredFormatLocale()
    val amountZec = amount.settled
    val amountSelection = rememberAmountTextFieldSelection(amount) { it.shown }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = chatColors().bgInput
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = stringResource(R.string.timelock_payment_title),
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.timelock_payment_note),
                fontSize = 13.sp,
                color = chatColors().textSecondary
            )
            Spacer(modifier = Modifier.height(12.dp))
            OutlinedTextField(
                // QA2 m4 (ZC-1759): the field reports the selection before each edit.
                value = amountSelection.valueFor(amount.shown),
                onValueChange = { fieldValue ->
                    amountSelection.onValueChange(
                        fieldValue,
                        edit = { state, newValue, selectionBefore ->
                            acceptAmountInputResult(state, newValue, formatLocale, AmountUnit.ZEC, selectionBefore)
                        },
                        onState = onAmountChange
                    )
                },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.timelock_payment_amount_label)) },
                // Round 8 P9: shown with the phone's decimal separator; the stored amount keeps its '.'.
                visualTransformation = amountFieldTransformation(amount, decimalSeparatorOf(formatLocale)),
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
            AmountReadingChooser(
                raw = amount.shown,
                options = amount.ask,
                notUsable = amount.notUsable,
                error = amount.error,
                unit = AmountUnit.ZEC,
                locale = formatLocale,
                onChoose = { onAmountChange(amount.choose(it, MAX_AMOUNT_DECIMALS)) },
            )
            // Quick presets: written in the phone's language (zecDisplay), scrolling sideways so none is cut off.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf(
                    "0.001" to Zatoshi(100_000),
                    "0.01" to Zatoshi(1_000_000),
                    "0.1" to Zatoshi(10_000_000),
                    "1.0" to Zatoshi(100_000_000)
                ).forEach { (value, zatoshi) ->
                    Card(
                        modifier = Modifier
                            .clickable { onAmountChange(AmountFieldState.settled(value)) }
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
                            text = "${zecDisplay(zatoshi, formatLocale)} ZEC",
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
                text = stringResource(R.string.timelock_secret_title),
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.timelock_secret_note),
                fontSize = 13.sp,
                color = chatColors().textSecondary
            )
            Spacer(modifier = Modifier.height(12.dp))
            OutlinedTextField(
                value = answer,
                onValueChange = onAnswerChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.timelock_secret_answer_label)) },
                placeholder = { Text(stringResource(R.string.timelock_secret_answer_placeholder)) },
                singleLine = true
            )
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = hint,
                onValueChange = onHintChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.timelock_hint_label)) },
                placeholder = { Text(stringResource(R.string.timelock_hint_placeholder)) },
                singleLine = true,
                supportingText = {
                    Text(stringResource(R.string.timelock_hint_visible))
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
    // KI-3: the contact the request goes to, named in the "Split a bill" share line.
    recipientName: String,
    zecPriceUsd: Double?,
    // Round 9 R9-1: what sending the request costs in this chat (paymentRequestSendCost): the least its one on-chain
    // transaction costs in a Shielded chat, null in a Tunnel or Open chat, where it goes over the relays, free.
    requestSendCost: Zatoshi?,
    onDismiss: () -> Unit,
    onSendRequest: (amountZatoshi: Long, reason: String) -> Unit
) {
    // F1: the field's reading state; the amount that counts is its settled text ("" while a question is open).
    // QA2 m8: the holder edits the state it is handed and stores what the machine took through amountFieldState.
    val amountFieldState = remember { mutableStateOf(AmountFieldState()) }
    var amountField by amountFieldState
    val amountSelection = rememberAmountTextFieldSelection(amountField) { it.shown }
    val amountText = amountField.settled
    var reason by remember { mutableStateOf("") }
    // KI-3: true once the user edits the reason; until then, with the split on, the field holds the prefilled reason.
    var reasonTyped by remember { mutableStateOf(false) }
    var useUsd by remember { mutableStateOf(false) }
    // KI-3: "Split a bill" asks this contact for their share of the total only; nothing leaves the user's wallet.
    var splitOn by remember { mutableStateOf(false) }
    var splitPeople by remember { mutableIntStateOf(SplitShare.MIN_PEOPLE) }

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
    // KI-3: with the split on, the typed amount is the bill's total and the request asks for one share of it,
    // floor(total / people) zatoshi, never the total; 0 (Request disabled) when the share would be below 1 zatoshi.
    val split = SplitShare.of(amountZatoshi, splitPeople)
    val requestedZatoshi = if (splitOn) split?.share ?: 0L else amountZatoshi

    // R5-4: keyed on the Compose-observed locale. The activity keeps `locale` in configChanges, so a
    // language change does not recreate it; a keyless remember held a DecimalFormat built from the
    // locale at first composition, so these amounts kept the old language's separators until the app
    // was restarted. Display only — the amounts, fees and the affordability check are unchanged.
    // Round 9 R9-1: ZEC figures go through PaidActionCopy.zec, exact and in the phone's language.
    val formatLocale = rememberDesiredFormatLocale()
    val decimalFormat = remember(formatLocale) { DecimalFormat("#,##0.00", DecimalFormatSymbols(formatLocale)) }
    val amountZecText = PaidActionCopy.zec(Zatoshi(amountZatoshi), formatLocale)
    val requestedZecText = PaidActionCopy.zec(Zatoshi(requestedZatoshi), formatLocale)
    // KI-3: an empty reason of a split request is "Share of <total> ZEC, split <N> ways", in the user's language.
    val splitReason = stringResource(R.string.request_payment_split_reason, amountZecText, splitPeople)
    val reasonToSend = if (splitOn && reason.isBlank()) splitReason else reason

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
                        text = stringResource(R.string.request_payment_title),
                        fontSize = 20.sp,
                        color = chatColors().textPrimary
                    )
                }
                IconButton(onClick = onDismiss) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = stringResource(R.string.request_payment_close),
                        tint = chatColors().textSecondary
                    )
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
                            text = stringResource(R.string.request_payment_amount_usd_hint),
                            fontSize = 15.sp
                        )
                        Switch(
                            checked = useUsd,
                            onCheckedChange = {
                                useUsd = it
                                // H13: dollars have 2 decimals, so the amount is read again for the new unit.
                                amountField = amountField.reread(formatLocale, if (it) AmountUnit.USD else AmountUnit.ZEC)
                            },
                            enabled = zecPriceUsd != null
                        )
                    }
                }

                // KI-3: Split a bill. The amount below becomes the total; the request asks this contact for one share.
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors =
                        CardDefaults.cardColors(
                            containerColor = if (splitOn) chatColors().primary.copy(alpha = 0.1f) else chatColors().bgInput
                        )
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = stringResource(R.string.request_payment_split_title),
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = stringResource(R.string.request_payment_split_desc),
                                    fontSize = 13.sp,
                                    color = chatColors().textSecondary
                                )
                            }
                            Switch(
                                checked = splitOn,
                                onCheckedChange = { splitOn = it }
                            )
                        }
                        if (splitOn) {
                            Spacer(modifier = Modifier.height(12.dp))
                            // People counter, the user included.
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                IconButton(
                                    onClick = { if (splitPeople > SplitShare.MIN_PEOPLE) splitPeople-- },
                                    enabled = splitPeople > SplitShare.MIN_PEOPLE,
                                    modifier =
                                        Modifier
                                            .size(40.dp)
                                            .clip(CircleShape)
                                            .background(chatColors().bgElevated)
                                ) {
                                    Icon(
                                        Icons.Default.Remove,
                                        contentDescription = stringResource(R.string.send_payment_dialog_decrease)
                                    )
                                }
                                Spacer(modifier = Modifier.width(16.dp))
                                Column(
                                    modifier = Modifier.weight(1f, fill = false),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Text(
                                        text = splitPeople.toString(),
                                        fontSize = 24.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = chatColors().primary
                                    )
                                    Text(
                                        text = stringResource(R.string.request_payment_split_people),
                                        fontSize = 13.sp,
                                        color = chatColors().textSecondary,
                                        textAlign = TextAlign.Center
                                    )
                                }
                                Spacer(modifier = Modifier.width(16.dp))
                                IconButton(
                                    onClick = { if (splitPeople < SplitShare.MAX_PEOPLE) splitPeople++ },
                                    enabled = splitPeople < SplitShare.MAX_PEOPLE,
                                    modifier =
                                        Modifier
                                            .size(40.dp)
                                            .clip(CircleShape)
                                            .background(chatColors().bgElevated)
                                ) {
                                    Icon(
                                        Icons.Default.Add,
                                        contentDescription = stringResource(R.string.send_payment_dialog_increase)
                                    )
                                }
                            }
                        }
                    }
                }

                // Amount input
                OutlinedTextField(
                    // QA2 m4 (ZC-1759): the field reports the selection before each edit.
                    value = amountSelection.valueFor(amountField.shown),
                    onValueChange = { fieldValue ->
                        amountSelection.onValueChange(
                            fieldValue,
                            edit = { state, newValue, selectionBefore ->
                                val unit = if (useUsd) AmountUnit.USD else AmountUnit.ZEC
                                acceptAmountInputResult(state, newValue, formatLocale, unit, selectionBefore)
                            },
                            onState = { amountFieldState.value = it }
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = {
                        Text(
                            stringResource(
                                when {
                                    splitOn && useUsd -> R.string.request_payment_split_total_label_usd
                                    splitOn -> R.string.request_payment_split_total_label_zec
                                    useUsd -> R.string.request_payment_amount_label_usd
                                    else -> R.string.request_payment_amount_label_zec
                                }
                            )
                        )
                    },
                    placeholder = { Text(stringResource(R.string.request_payment_amount_placeholder)) },
                    // Round 8 P9 left this field out: the stored '.' is shown as the phone's decimal mark.
                    visualTransformation = amountFieldTransformation(amountField, decimalSeparatorOf(formatLocale)),
                    isError = amountField.isQuestionOpen,
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
                                Text(stringResource(R.string.request_payment_zec_equivalent, amountZecText))
                            } else if (!useUsd && zecPriceUsd != null) {
                                val usdValue = amountValue * zecPriceUsd
                                Text(stringResource(R.string.request_payment_usd_equivalent, decimalFormat.format(usdValue)))
                            }
                        }
                    }
                )

                AmountReadingChooser(
                    raw = amountField.shown,
                    options = amountField.ask,
                    notUsable = amountField.notUsable,
                    error = amountField.error,
                    unit = if (useUsd) AmountUnit.USD else AmountUnit.ZEC,
                    locale = formatLocale,
                    onChoose = {
                        amountField = amountField.choose(it, if (useUsd) AmountUnit.USD.maxDecimals else MAX_AMOUNT_DECIMALS)
                    },
                )

                // KI-3: what the split request asks this contact for, and what the rounding down left out.
                if (splitOn && amountZatoshi > 0) {
                    if (split != null) {
                        Text(
                            text = stringResource(R.string.request_payment_split_share, recipientName, requestedZecText),
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = chatColors().primary
                        )
                        if (split.remainder > 0) {
                            Text(
                                text = stringResource(R.string.request_payment_split_rounded, split.remainder),
                                fontSize = 13.sp,
                                color = chatColors().textSecondary
                            )
                        }
                    } else {
                        Text(
                            text = stringResource(R.string.request_payment_split_too_small),
                            fontSize = 13.sp,
                            color = chatColors().error
                        )
                    }
                }

                // Quick amount presets
                Text(
                    text = stringResource(R.string.request_payment_quick_amounts),
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
                                .clickable { amountField = AmountFieldState.settled(preset) }
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
                                text =
                                    if (useUsd) {
                                        stringResource(R.string.request_payment_preset_usd, preset)
                                    } else {
                                        stringResource(R.string.request_payment_preset_zec, presetZecText(preset, formatLocale))
                                    },
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
                // KI-3: with the split on, the field holds the prefilled split reason until the user edits it; cleared,
                // it shows that reason as its placeholder, as that is what goes.
                OutlinedTextField(
                    value = if (splitOn && !reasonTyped && split != null) splitReason else reason,
                    onValueChange = {
                        reason = it
                        reasonTyped = true
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.request_payment_reason_label)) },
                    placeholder = {
                        Text(
                            if (splitOn && split != null) splitReason else stringResource(R.string.request_payment_reason_placeholder)
                        )
                    },
                    singleLine = true,
                    maxLines = 1,
                    // W-11 (T4 phones, KI-3 (d)): the examples help while the reason is the user's own to write.
                    // With the split on, the field already holds the reason that goes, and at 400 dp the examples
                    // line was cut in half at the scroll area's edge; it is not shown then.
                    supportingText =
                        if (splitOn) null else ({ Text(stringResource(R.string.request_payment_reason_examples)) })
                )

                // Round 9 R9-1: what sending this request costs. In a Shielded chat it is one on-chain transaction, and
                // tapping Request asks again with this figure before anything is paid; elsewhere it goes over the relays.
                Text(
                    text =
                        if (requestSendCost != null) {
                            stringResource(
                                R.string.request_payment_cost_onchain,
                                PaidActionCopy.zec(requestSendCost, formatLocale)
                            )
                        } else {
                            stringResource(R.string.request_payment_cost_free)
                        },
                    fontSize = 13.sp,
                    color = chatColors().textSecondary
                )
            }
        },
        confirmButton = {
            FilledTonalButton(
                onClick = { onSendRequest(requestedZatoshi, reasonToSend) },
                enabled = requestedZatoshi > 0
            ) {
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text =
                        if (requestedZatoshi > 0) {
                            stringResource(R.string.request_payment_button, requestedZecText)
                        } else {
                            stringResource(R.string.request_payment_title)
                        }
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.paid_action_cancel))
            }
        }
    )
}

/** Round 9 R9-1 — a ZEC quick-amount preset ("0.01") written for the phone's language ("0,01" in Spanish). */
private fun presetZecText(
    preset: String,
    locale: java.util.Locale
): String =
    runCatching { PaidActionCopy.zec(BigDecimal(preset).convertZecToZatoshi(), locale) }.getOrDefault(preset)

// ==========================================
// PAYMENT REQUEST CONTENT
// ==========================================

/** Header of a payment request bubble; [failed] is true when an outgoing request could not be sent. */
internal fun paymentRequestHeader(
    isOutgoing: Boolean,
    failed: Boolean
): String =
    when {
        !isOutgoing -> "Payment Requested"
        failed -> "Payment Request Not Sent"
        else -> "Payment Request Sent"
    }

/**
 * Payment request content displayed in chat bubbles.
 * Shows amount, reason, and a Pay button for incoming requests.
 */
@Composable
internal fun PaymentRequestContent(
    paymentRequest: PaymentRequestInfo,
    zecPriceUsd: Double?,
    isOutgoing: Boolean,
    // Round 8 P3 (PHONE_TRIAGE F1): the chat's key is a real address (ChatDetailView.isValidAddress). A chat keyed
    // "unknown", by a sender hash or by a conversation id has nobody to pay, so it shows why instead of Pay.
    canPay: Boolean,
    onPayClick: () -> Unit,
    modifier: Modifier = Modifier,
    // P2h: the outgoing request could not be sent (its bubble is FAILED).
    failed: Boolean = false,
    // F5: Pay is hidden because we paid, but the payment is not mined yet: "Payment pending", never "Paid".
    paymentPending: Boolean = false,
    // QA2 Q2-54: when the payment was mined, as the phone shows a time ("15:45"); null: the bubble says "Paid" only.
    paidAtText: String? = null,
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
                text = paymentRequestHeader(isOutgoing, failed),
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

        // Pay button (only for incoming requests that aren't paid yet, in a chat with a real address)
        if (!isOutgoing && !paymentRequest.isPaid && canPay) {
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
                // QA2 Q2-54: the network fee comes on top of the requested amount; the button says so.
                Text(
                    text = stringResource(R.string.chat_request_pay_button, paymentRequest.getFormattedAmount()),
                    fontWeight = FontWeight.SemiBold
                )
            }
        }

        // Round 8 P3: no address to pay (the sender is known only by a hash) — say so instead of offering Pay.
        if (!isOutgoing && !paymentRequest.isPaid && !canPay) {
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = stringResource(R.string.chat_request_cannot_pay),
                fontSize = 13.sp,
                color = textColor,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }

        // Paid indicator. F5: "Payment pending" until the chain shows our payment mined, then "Paid".
        if (paymentRequest.isPaid) {
            val statusText =
                when {
                    paymentPending -> stringResource(R.string.chat_request_payment_pending)
                    // QA2 Q2-54: the receipt names the amount paid and when.
                    paidAtText != null ->
                        stringResource(R.string.chat_request_paid_receipt, paymentRequest.getFormattedAmount(), paidAtText)
                    else -> stringResource(R.string.chat_request_paid)
                }
            // ZC-1706: on our own (teal) bubble the success green is unreadable; the line takes the bubble's foreground.
            val statusColor = if (paymentPending || isOutgoing) textColor else chatColors().success
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = if (paymentPending) Icons.Default.Schedule else Icons.Default.DoneAll,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = statusColor
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = statusText,
                    fontSize = 13.sp,
                    color = statusColor,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

/**
 * QA2 B-5 (Q2-67, ZC-1653): the picture picked in the gallery, taken with the camera or shared into ZChat, shown before
 * it is sent. Cancel sends nothing. Send is not a payment confirmation and names no price (none is quoted yet): the
 * upload slot, the key-exchange gate and a Shielded chat's "Pay for this?" question all come after it, unchanged. No
 * caption: that would need a new field in the ZFILE memo, which a 2.12.4 phone would misread (compat-2124).
 */
@Composable
internal fun ImageSendPreviewDialog(
    pending: co.electriccoin.zcash.ui.screen.chat.model.PendingImageSend,
    onSend: () -> Unit,
    onCancel: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val preview by androidx.compose.runtime.produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, pending.uri) {
        value =
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching {
                    val resolver = context.contentResolver
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    resolver.openInputStream(pending.uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
                    var sample = 1
                    while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= PREVIEW_EDGE_PX) sample *= 2
                    val options = BitmapFactory.Options().apply { inSampleSize = sample }
                    resolver.openInputStream(pending.uri)?.use { BitmapFactory.decodeStream(it, null, options) }
                }.getOrNull()?.asImageBitmap()
            }
    }
    AlertDialog(
        onDismissRequest = onCancel,
        containerColor = chatColors().bgElevated,
        title = { Text(stringResource(R.string.chat_image_preview_title), color = chatColors().textPrimary) },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                val image = preview
                if (image != null) {
                    androidx.compose.foundation.Image(
                        bitmap = image,
                        contentDescription = null,
                        modifier = Modifier.fillMaxWidth().height(PREVIEW_HEIGHT).clip(RoundedCornerShape(12.dp)),
                        contentScale = androidx.compose.ui.layout.ContentScale.Fit,
                    )
                }
                if (pending.viewOnce) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.chat_image_preview_view_once),
                        fontSize = 13.sp,
                        color = chatColors().textSecondary,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onSend) {
                Text(stringResource(R.string.chat_image_preview_send), color = chatColors().primary)
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel) {
                Text(stringResource(R.string.chat_image_preview_cancel), color = chatColors().textSecondary)
            }
        },
    )
}

/** B-5: the preview is decoded down to about this many pixels on its longer edge (a sampled decode, as for thumbnails). */
private const val PREVIEW_EDGE_PX = 1024

private val PREVIEW_HEIGHT = 240.dp
