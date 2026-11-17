package co.electriccoin.zcash.ui.screen.ai

import co.electriccoin.zcash.ui.design.component.AmountUnit
import co.electriccoin.zcash.ui.design.util.AmountEditResult
import co.electriccoin.zcash.ui.design.util.AmountFieldState
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Locale

/** Cents: a custom USD top-up keeps at most two decimals. */
internal const val MAX_USD_DECIMALS = 2

/**
 * The AI top-up custom USD field's next state after a keystroke or a paste ([raw] is what Compose proposes).
 *
 * F1 (fix-all 2.13.0): the shared amount reader. A typed comma, point or Arabic decimal separator (U+066B) is the
 * decimal point, so "12,50" typed on a Spanish or Serbian keyboard is $12.50 (C8, round 7 review finding 25: the comma
 * used to be dropped and "12,50" became "1250"); a second mark or a third decimal typed is ignored, so a mark never
 * makes the whole-dollar part bigger. A paste is read by its structure: "1,000.50" and "1.000,50" are $1000.50, a
 * pasted "$12" is $12, and a paste that reads two ways ("1,000") asks which one was meant. A pasted "5 ZEC" is refused,
 * never billed as $5 (T1 fixup, X-F1-1). The amount that counts is [AmountFieldState.settled] ('.' decimal point), ""
 * while a question is open, so no tier is offered until a tap. [selectionBefore] is the field's selection before the edit
 * (QA2 m4, ZC-1759), null when it is not known.
 */
@JvmOverloads
internal fun acceptUsdAmountInput(
    field: AmountFieldState,
    raw: String,
    locale: Locale,
    selectionBefore: IntRange? = null
): AmountFieldState = acceptUsdAmountInputResult(field, raw, locale, selectionBefore).state

/** QA2 m8 (ZC-1780): [acceptUsdAmountInput] and whether the machine took the edit (false: [field] stays as it is). */
internal fun acceptUsdAmountInputResult(
    field: AmountFieldState,
    raw: String,
    locale: Locale,
    selectionBefore: IntRange?
): AmountEditResult<AmountFieldState> =
    field.editResult(raw, locale, AmountUnit.USD.maxDecimals, AmountUnit.USD, true, selectionBefore)

/** Upper bound of a custom top-up in whole USD; mirrors AiApiClient.MAX_USD. */
internal const val MAX_CUSTOM_TOPUP_USD = 1_000_000L

/**
 * The whole-dollar top-up for the custom field [text] (its settled '.' text), or null when there is none.
 *
 * Downstream tiers are whole USD (Int). Require >= $1.00 and round DOWN to the whole dollar (F1: $1000.50 bills $1000,
 * never more than was typed; the sheet shows the billed "$1000 USD" before paying). Never silently coerce a sub-dollar
 * amount to $1: sub-$1 input shows a hint instead.
 */
internal fun customUsdTier(text: String): Int? {
    val parsed = text.takeIf { it.any(Char::isDigit) }?.toBigDecimalOrNull()
    return when {
        parsed == null -> null
        parsed < BigDecimal.ONE -> null
        // Clamp on the whole-dollar value BEFORE narrowing to Int, so a huge fat-fingered amount (> 2^31) never wraps
        // to a bogus or negative tier.
        else ->
            parsed
                .setScale(0, RoundingMode.DOWN)
                .min(BigDecimal.valueOf(MAX_CUSTOM_TOPUP_USD))
                .toInt()
    }
}
