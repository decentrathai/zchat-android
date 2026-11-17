package co.electriccoin.zcash.ui.screen.send.model

import co.electriccoin.zcash.ui.common.wallet.ExchangeRateState
import java.util.Locale

/*
 * QA2 m4 (ZC-1765): newFromZec and newFromFiat read only text the field already holds or the app wrote ("STORED"); their
 * unused `entry` and `previous` parameters are gone. The older probes that named an entry reach main Send the way the
 * screen does: a "PASTE" is AmountState.edit with the whole [previous] text selected, a "KEYSTROKE" is AmountState.edit
 * with the caret after [previous].
 */

@Suppress("LongParameterList")
internal fun zecEntry(
    value: String,
    locale: Locale,
    entry: String,
    previous: String,
    fiatValue: String,
    exchangeRateState: ExchangeRateState
): AmountState {
    if (entry == "STORED") return AmountState.newFromZec(locale, value, fiatValue, false, exchangeRateState, AmountField.ZEC)
    val before =
        if (previous.isEmpty()) {
            AmountState.Invalid("", fiatValue, AmountField.ZEC)
        } else {
            AmountState.newFromZec(locale, previous, fiatValue, false, exchangeRateState, AmountField.ZEC)
        }
    return AmountState.edit(before, AmountField.ZEC, value, selectionOf(entry, previous), locale, false, exchangeRateState)
}

@Suppress("LongParameterList")
internal fun fiatEntry(
    fiatValue: String,
    locale: Locale,
    entry: String,
    value: String,
    previous: String,
    exchangeRateState: ExchangeRateState
): AmountState {
    if (entry == "STORED") return AmountState.newFromFiat(locale, value, fiatValue, false, exchangeRateState)
    val before =
        if (previous.isEmpty()) {
            AmountState.Invalid(value, "", AmountField.FIAT)
        } else {
            AmountState.newFromFiat(locale, value, previous, false, exchangeRateState)
        }
    return AmountState.edit(before, AmountField.FIAT, fiatValue, selectionOf(entry, previous), locale, false, exchangeRateState)
}

private fun selectionOf(
    entry: String,
    previous: String
): IntRange =
    when (entry) {
        "PASTE" -> 0..previous.length
        "KEYSTROKE" -> previous.length..previous.length
        else -> error("unknown entry $entry")
    }
