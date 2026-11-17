package co.electriccoin.zcash.ui.screen.chat.model

import cash.z.ecc.android.sdk.model.Zatoshi
import cash.z.ecc.android.sdk.model.toZecString
import java.util.Locale

/** The fraction digits the SDK's toZecString always writes. */
private const val ZEC_FRACTION_DIGITS = 8

/**
 * Render a [Zatoshi] as a plain decimal ZEC string in [locale] — never scientific notation.
 *
 * The dialogs used to build this as `zatoshi.value / 100_000_000.0`, and `Double.toString` switches to
 * exponents below 1e-3. That put "about 1.7E-4 ZEC" in front of a user being asked to approve spending
 * — the one place in the app where the number has to be legible, since approving it moves real money.
 *
 * Exact by construction: the figure is the SDK's own balance formatter ([toZecString], which always writes
 * all eight decimal places), so the quoted amount is the amount that leaves the wallet, with the separators
 * and grouping the balances on the same screen use. Trailing zeros, and then a trailing decimal separator,
 * are trimmed so ordinary values keep their familiar short form ("0.0053", "1"; "0,0053" in Spanish).
 *
 * Round 8 P9 (PHONE_TRIAGE ES-2): the old figure came from `BigDecimal.toPlainString()`, which knows no
 * language, so a Spanish phone read "al menos 0.00017 ZEC" next to balances written "0,01320586 ZEC".
 * Callers pass rememberDesiredFormatLocale().
 */
fun zecDisplay(
    zatoshi: Zatoshi,
    locale: Locale
): String {
    val full = zatoshi.toZecString(locale)
    val separatorIndex = full.length - ZEC_FRACTION_DIGITS - 1
    // Only trim a figure of the expected shape (a non-digit separator before exactly eight digits): anything
    // else is shown as the formatter wrote it rather than risk dropping a significant zero.
    if (separatorIndex < 1 || full[separatorIndex].isDigit() || !full.takeLast(ZEC_FRACTION_DIGITS).all { it.isDigit() }) {
        return full
    }
    val trimmed = full.trimEnd('0')
    return if (trimmed.length == separatorIndex + 1) trimmed.dropLast(1) else trimmed
}
