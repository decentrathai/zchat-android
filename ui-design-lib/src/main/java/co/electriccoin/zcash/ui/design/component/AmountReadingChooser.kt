package co.electriccoin.zcash.ui.design.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import co.electriccoin.zcash.ui.design.R
import co.electriccoin.zcash.ui.design.util.AmountInvalidReason
import co.electriccoin.zcash.ui.design.util.AmountTextReader
import java.math.BigDecimal
import java.util.Locale

/** The unit an amount field holds: how an option is labelled and how many decimals it has. */
enum class AmountUnit(
    val maxDecimals: Int
) {
    ZEC(8),
    USD(2)
}

/**
 * F1 (fix-all 2.13.0): the question under an amount field when a paste reads two ways, or the reason a paste can't
 * be used.
 *
 * - [options] (ascending): one equal-weight button per reading, smaller first, none preselected (a reflex tap
 *   underpays, never overpays). A tap only fills the field ([onChoose]); nothing is sent. One option is a
 *   confirmation ("12.345" as dollars can only be $12345).
 * - [error]: why the text can't be used, and what to do.
 * - [notUsable] (QA2 m3): a reading of the paste that has more decimals than the field holds ("12.345" in dollars). It
 *   is shown as a disabled button, written in full (never rounded), with the too-many-decimals line under the buttons,
 *   so the user sees both readings and that only the other one can be used.
 * Nothing is drawn when there is neither a question nor an error. [raw] is quoted as it was pasted.
 */
@Composable
fun AmountReadingChooser(
    raw: String,
    options: List<BigDecimal>?,
    error: AmountInvalidReason?,
    unit: AmountUnit,
    locale: Locale,
    onChoose: (BigDecimal) -> Unit,
    modifier: Modifier = Modifier,
    notUsable: List<BigDecimal> = emptyList(),
) = ReadingChooser(
    raw = raw,
    options = options,
    error = error,
    label = { amountOptionLabel(it, unit, locale) },
    errorText = { amountErrorText(it, unit, locale) },
    onChoose = onChoose,
    modifier = modifier,
    notUsable = notUsable,
    notUsableLabel = { amountOptionLabel(it, unit, locale, unit.maxDecimals.coerceAtLeast(it.scale())) },
    tooManyDecimals = AmountInvalidReason.TooManyDecimals(unit.maxDecimals)
)

/**
 * F12 (fix-all 2.13.0): the same question for a number field with no ZEC or dollar unit (a swap token, with its own
 * [maxDecimals]): readings are labelled with [ticker] ("1.5 USDC"), never grouped.
 */
@Suppress("LongParameterList")
@Composable
fun NumberReadingChooser(
    raw: String,
    options: List<BigDecimal>?,
    error: AmountInvalidReason?,
    maxDecimals: Int,
    ticker: String?,
    locale: Locale,
    onChoose: (BigDecimal) -> Unit,
    modifier: Modifier = Modifier,
    notUsable: List<BigDecimal> = emptyList(),
) = ReadingChooser(
    raw = raw,
    options = options,
    error = error,
    label = { value -> numberOptionLabel(value, maxDecimals, ticker, locale) },
    notUsable = notUsable,
    notUsableLabel = { value -> numberOptionLabel(value, maxDecimals.coerceAtLeast(value.scale()), ticker, locale) },
    tooManyDecimals = AmountInvalidReason.TooManyDecimals(maxDecimals),
    errorText = { reason ->
        when (reason) {
            is AmountInvalidReason.TooManyDecimals -> stringResource(R.string.number_field_too_many_decimals, reason.max)
            is AmountInvalidReason.OtherUnit -> stringResource(R.string.number_field_other_unit)
            AmountInvalidReason.PasteIntoAmount -> stringResource(R.string.amount_paste_into_amount)
            AmountInvalidReason.Unreadable ->
                stringResource(R.string.amount_paste_unreadable, AmountTextReader.formatForField(BigDecimal("0.5"), locale, 1))
        }
    },
    onChoose = onChoose,
    modifier = modifier
)

@Composable
private fun numberOptionLabel(
    value: BigDecimal,
    maxDecimals: Int,
    ticker: String?,
    locale: Locale
): String {
    val number = AmountTextReader.formatForField(value, locale, maxDecimals)
    return if (ticker.isNullOrBlank()) number else stringResource(R.string.number_field_option_ticker, number, ticker)
}

@Suppress("LongParameterList")
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ReadingChooser(
    raw: String,
    options: List<BigDecimal>?,
    error: AmountInvalidReason?,
    label: @Composable (BigDecimal) -> String,
    errorText: @Composable (AmountInvalidReason) -> String,
    onChoose: (BigDecimal) -> Unit,
    modifier: Modifier = Modifier,
    notUsable: List<BigDecimal> = emptyList(),
    notUsableLabel: @Composable (BigDecimal) -> String = label,
    tooManyDecimals: AmountInvalidReason? = null,
) {
    if (options == null && error == null) return
    Column(modifier = modifier.fillMaxWidth().padding(top = 4.dp)) {
        if (options != null) {
            Text(
                text = stringResource(R.string.amount_paste_which_title),
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text =
                    if (options.size + notUsable.size > 1) {
                        stringResource(R.string.amount_paste_which_body, raw)
                    } else {
                        stringResource(R.string.amount_paste_confirm_body, raw, label(options.first()))
                    },
                fontSize = 13.sp,
            )
            FlowRow(
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // Smaller first, the reading that can't be used among them, disabled (QA2 m3).
                (options.map { it to true } + notUsable.map { it to false }).sortedBy { it.first }.forEach { (option, usable) ->
                    OutlinedButton(onClick = { onChoose(option) }, enabled = usable) {
                        Text(text = if (usable) label(option) else notUsableLabel(option), fontSize = 14.sp)
                    }
                }
            }
            if (notUsable.isNotEmpty() && tooManyDecimals != null) {
                Text(
                    text = errorText(tooManyDecimals),
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        } else if (error != null) {
            Text(
                text = errorText(error),
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

/** "1 ZEC" / "1000 ZEC"; "1,5 ZEC" in Spanish; "$1000" / "1000 US$". Never grouped. */
@Composable
fun amountOptionLabel(
    value: BigDecimal,
    unit: AmountUnit,
    locale: Locale,
    decimals: Int = unit.maxDecimals
): String {
    val number = AmountTextReader.formatForField(value, locale, decimals)
    return when (unit) {
        AmountUnit.ZEC -> stringResource(R.string.amount_paste_option_zec, number)
        AmountUnit.USD -> stringResource(R.string.amount_paste_option_usd, number)
    }
}

/** The text for an amount field [error]. */
@Composable
fun amountErrorText(
    error: AmountInvalidReason,
    unit: AmountUnit,
    locale: Locale
): String =
    when (error) {
        is AmountInvalidReason.TooManyDecimals ->
            when (unit) {
                AmountUnit.ZEC -> stringResource(R.string.amount_too_many_decimals_zec)
                AmountUnit.USD -> stringResource(R.string.amount_too_many_decimals_usd)
            }
        AmountInvalidReason.PasteIntoAmount -> stringResource(R.string.amount_paste_into_amount)
        is AmountInvalidReason.OtherUnit ->
            when (unit) {
                AmountUnit.ZEC -> stringResource(R.string.amount_other_unit_zec)
                AmountUnit.USD -> stringResource(R.string.amount_other_unit_usd)
            }
        AmountInvalidReason.Unreadable ->
            stringResource(R.string.amount_paste_unreadable, AmountTextReader.formatForField(BigDecimal("0.5"), locale, 1))
    }
