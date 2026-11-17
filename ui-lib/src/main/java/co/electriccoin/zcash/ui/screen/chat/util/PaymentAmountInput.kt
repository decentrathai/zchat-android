package co.electriccoin.zcash.ui.screen.chat.util

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import co.electriccoin.zcash.ui.design.component.AmountUnit
import co.electriccoin.zcash.ui.design.util.AmountEditResult
import co.electriccoin.zcash.ui.design.util.AmountFieldState
import co.electriccoin.zcash.ui.design.util.AmountInvalidReason
import java.text.DecimalFormatSymbols
import java.util.Locale

/** At most one zatoshi of precision: a ZEC amount has eight decimals. */
internal const val MAX_AMOUNT_DECIMALS = 8

private val AMOUNT_INPUT = Regex("^\\d*\\.?\\d{0,$MAX_AMOUNT_DECIMALS}$")

/**
 * The one keystroke rule for the chat amount fields (Send Payment, the time-lock required amount and
 * the payment-request amount).
 *
 * [newValue] is the text Compose proposes after a keystroke or a paste. The result is the text the
 * field should hold, or null to ignore the keystroke. A comma — the decimal mark on a Spanish or
 * Serbian keyboard — is read as the decimal point, so "0,5" is half a ZEC; so are the Arabic decimal separator and
 * the decimal mark of the phone's language ([isDecimalMark]). Only ASCII digits, at most
 * one decimal mark and at most [MAX_AMOUNT_DECIMALS] decimals are accepted. A keystroke is taken as
 * typed or ignored whole: it never turns into a different value. The old rule dropped the comma and
 * appended the next digit to the whole part, so 0 , 5 became "05", ten times the amount (SEND-PAYMENT-COMMA).
 */
internal fun acceptAmountKeystroke(newValue: String): String? {
    val normalized = normalizeDecimalMarks(newValue)
    return if (AMOUNT_INPUT.matches(normalized)) normalized else null
}

/** The Arabic decimal separator, sent by Arabic and Persian keyboards and number pads. */
private const val ARABIC_DECIMAL_SEPARATOR = '\u066B'

/**
 * True for a character a keyboard may send as the decimal mark: the point, the comma, the Arabic decimal separator
 * (U+066B) and the decimal separator of the phone's language (read on every call, so a language change applies at
 * once). A digit, a letter or a space is never a decimal mark, whatever the language says (C8, round 7 review
 * finding 24: only the comma was mapped before).
 */
internal fun isDecimalMark(c: Char): Boolean {
    if (c == '.' || c == ',' || c == ARABIC_DECIMAL_SEPARATOR) return true
    if (c.isLetterOrDigit() || c.isWhitespace()) return false
    return c == DecimalFormatSymbols.getInstance().decimalSeparator
}

/** [text] with every decimal mark (see [isDecimalMark]) written as '.'; nothing else changes. */
internal fun normalizeDecimalMarks(text: String): String =
    text.map { if (isDecimalMark(it)) '.' else it }.joinToString("")

/**
 * Round 8 P9 (PHONE_TRIAGE ES-1): shows an amount field's stored '.' as [separator], the decimal separator of the
 * phone's language, so a Spanish phone reads "0,5" where the field holds "0.5". Display only: the field keeps its '.'
 * (the text [acceptAmountKeystroke] returns and the send parses), and one character stays one character, so the
 * cursor offsets map one to one.
 */
internal class DecimalSeparatorTransformation(
    private val separator: Char
) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText =
        TransformedText(
            if (separator == '.') text else AnnotatedString(text.text.replace('.', separator)),
            OffsetMapping.Identity
        )

    override fun equals(other: Any?): Boolean = other is DecimalSeparatorTransformation && other.separator == separator

    override fun hashCode(): Int = separator.hashCode()
}

/** The decimal separator [locale] writes amounts with ('.' in English, ',' in Spanish). */
internal fun decimalSeparatorOf(locale: Locale): Char = DecimalFormatSymbols(locale).decimalSeparator

/**
 * F1 (fix-all 2.13.0): the input rule of every chat amount field (Send Payment, the time-lock amount, the payment
 * request and the new-chat custom amount). A typed mark is the decimal point, as [acceptAmountKeystroke] reads it; a
 * paste is read with the shared amount reader, so a pasted "1,000" or "1.500" asks which amount was meant (smaller
 * first) instead of paying 1 ZEC, and a pasted "1,000.50" or "0.123456789" is read or refused with a reason instead
 * of being dropped. The amount that counts is the result's [AmountFieldState.settled] ('.' decimal point, never
 * grouped), "" while a question or an error is open. [unit] is the field's unit (it gives the precision): a pasted
 * currency sign of the other unit ("$5" in a ZEC field) is refused, never read as this unit (T1 fixup, X-F1-1).
 */
@JvmOverloads
internal fun acceptAmountInput(
    field: AmountFieldState,
    newValue: String,
    locale: Locale,
    unit: AmountUnit,
    selectionBefore: IntRange? = null
): AmountFieldState = acceptAmountInputResult(field, newValue, locale, unit, selectionBefore).state

/**
 * QA2 m8 (ZC-1780, ZC-1774): [acceptAmountInput] and whether the machine took the edit (false: [field] stays as it is).
 */
internal fun acceptAmountInputResult(
    field: AmountFieldState,
    newValue: String,
    locale: Locale,
    unit: AmountUnit,
    selectionBefore: IntRange?
): AmountEditResult<AmountFieldState> {
    val result = field.editResult(newValue, locale, unit.maxDecimals, unit, true, selectionBefore)
    val next = result.state
    // QA2 Q2-46 (money-path rule 5): a money field keeps no letters. A text whose letters are one trailing word the
    // reader could not take ("0.000013request", one multi-character insertion read as a paste) is read again without
    // that word, so the amount is the digits that were typed. A sign of another unit ("0.5 ZEC" in a dollar field)
    // stays refused as it is: those letters name a currency, and dropping them would read the figure as this field's
    // unit. U7 review follow-up (Normal 1): letters inside the number ("1e5", "1abc.5") are never dropped.
    // U7 second follow-up (Fable review #13, finding 2): the dropped word is an allow-list, not a deny-list. Only a word
    // in [HARMLESS_TRAILING_WORDS] is dropped; any other word ("500 mZEC", "5 BTC", "100 EUR", "10k", "1000 зат",
    // "1000 zat") may name a unit or a multiplier, so the paste stays refused out loud, the letters visible with the
    // error. Editing a refused paste ("1000 za" after "1000 zat") never drops the word either: the edit only answers
    // the refusal when the letters are gone. A paste of letters alone keeps its error instead of clearing the field.
    // [selectionBefore] is the field's selection before the edit, when the field knows it (QA2 m3, design rule 7).
    if (next.shown.none(Char::isLetter) || next.error is AmountInvalidReason.OtherUnit) return result
    if (field.error != null) return result
    val trimmed = newValue.trimEnd()
    val trailingWord = trimmed.takeLastWhile(Char::isLetter)
    val number = trimmed.dropLast(trailingWord.length).trimEnd()
    if (trailingWord.isEmpty() || number.isEmpty() || number.any(Char::isLetter)) return result
    if (trailingWord.lowercase(Locale.ROOT) !in HARMLESS_TRAILING_WORDS) return result
    return field.editResult(number, locale, unit.maxDecimals, unit, true, selectionBefore)
}

/**
 * The only trailing words an amount field drops (Q2-46): words that name no unit and no multiplier, so the digits
 * before them are the amount the user meant in this field's unit. Every other word is refused (money-path rule 5).
 */
private val HARMLESS_TRAILING_WORDS = setOf("request")

/**
 * The amount field's display: the stored '.' shown as [separator] (Round 8 P9), except while a question or a refusal is
 * open, when the field shows the text exactly as the question or the error quotes it (H9).
 */
internal fun amountFieldTransformation(
    field: AmountFieldState,
    separator: Char
): VisualTransformation =
    if (field.isQuestionOpen) VisualTransformation.None else DecimalSeparatorTransformation(separator)
