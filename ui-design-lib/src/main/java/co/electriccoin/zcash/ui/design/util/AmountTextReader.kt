package co.electriccoin.zcash.ui.design.util

import co.electriccoin.zcash.ui.design.component.AmountUnit
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.DecimalFormatSymbols
import java.util.Locale

/**
 * Which reading rule one [AmountTextReader.read] applies (F1, fix-all 2.13.0; QA2 m3). The rule is chosen by the event
 * alone, never by what the field held before (there is no paste origin):
 * - [KEYSTROKE]: one character typed (or a deletion). A single mark is the decimal point, whatever the phone's
 *   language, and nothing is ever read as grouping.
 * - [PASTE]: several characters at once. Pasted into an empty field (or over the whole text) its structure decides, and
 *   a shape that reads two ways asks; pasted into a typed amount only digits are taken.
 * - [STORED]: text the field already holds or the app wrote itself, read as typing reads it. It never asks.
 */
enum class AmountEntry { KEYSTROKE, PASTE, STORED }

/** One reading of an ambiguous paste: [value] and whether it fits the field's precision ([usable]). */
data class AmountReading(
    val value: BigDecimal,
    val usable: Boolean
)

/**
 * One edit of an amount field's text (QA2 m3, design rule 7): [inserted] is the text that arrived and [keptSome] whether
 * any of the old text stays around it. With the selection before the edit the split is exact; without it, it is the
 * smallest prefix/suffix difference of the two texts. [fromSelection] is true when the selection before the edit was
 * known and matches the new text, so the split is what the user did (QA2 m4, ZC-1759, ZC-1760).
 */
class AmountEdit private constructor(
    val inserted: String,
    val keptSome: Boolean,
    val fromSelection: Boolean = false
) {
    companion object {
        /**
         * The edit that turned [old] into [new]. [selectionBefore] is the selection the field had before the edit
         * (start..end, a caret is start == end); null when the field does not know it.
         */
        fun of(
            old: String,
            new: String,
            selectionBefore: IntRange? = null
        ): AmountEdit {
            if (selectionBefore != null) {
                val a = selectionBefore.first.coerceIn(0, old.length)
                val b = selectionBefore.last.coerceIn(a, old.length)
                val head = old.substring(0, a)
                val tail = old.substring(b)
                if (new.length >= head.length + tail.length && new.startsWith(head) && new.endsWith(tail)) {
                    return AmountEdit(new.substring(a, new.length - tail.length), head.length + tail.length > 0, true)
                }
            }
            val shorter = minOf(old.length, new.length)
            val prefix = old.commonPrefixWith(new).length
            val suffix = old.commonSuffixWith(new).length.coerceAtMost(shorter - prefix)
            return AmountEdit(new.substring(prefix, new.length - suffix), prefix + suffix > 0)
        }
    }
}

/** Why an amount text can't be used. */
sealed interface AmountInvalidReason {
    /** More decimals than the unit has (8 for ZEC, 2 for dollars). */
    data class TooManyDecimals(
        val max: Int
    ) : AmountInvalidReason

    /** Not a number, or marks that can't be read one way only ("1,2,3", "1.2.3", "1.000.50"). */
    data object Unreadable : AmountInvalidReason

    /** A pasted piece with marks landed inside a typed amount; the field must be cleared and pasted whole. */
    data object PasteIntoAmount : AmountInvalidReason

    /**
     * The paste carries a currency sign of [found] ("$", "US$" or "USD" for dollars; "ZEC" or "ⓩ" for ZEC), and the
     * field is in another unit (T1 fixup, X-F1-1). The amount is never read as the field's unit.
     */
    data class OtherUnit(
        val found: AmountUnit
    ) : AmountInvalidReason
}

/** The result of reading an amount text. */
sealed interface AmountRead {
    /**
     * One reading. [text] is canonical: ASCII digits, at most one '.' as the decimal point, never grouped. It keeps
     * what was typed ("1.", "0.50"); "" is an empty field.
     */
    data class Exact(
        val text: String
    ) : AmountRead {
        /** The amount, or null for "" and a lone ".". */
        val value: BigDecimal? get() = text.takeIf { it.any(Char::isDigit) }?.toBigDecimalOrNull()
    }

    /**
     * A paste that reads two ways: [options] are the usable readings, ascending, nothing preselected (one option when the
     * other reading has more decimals than the field holds). [readings] are both readings, each flagged usable or not.
     */
    data class Ask(
        val options: List<BigDecimal>,
        val raw: String,
        val readings: List<AmountReading> = options.map { AmountReading(it, true) }
    ) : AmountRead

    /**
     * Can't be used; the field shows why. [exact] is, for [AmountInvalidReason.TooManyDecimals] only, the one canonical
     * value the text reads as with no precision limit (a field whose precision grows uses it).
     */
    data class Invalid(
        val reason: AmountInvalidReason,
        val exact: String? = null
    ) : AmountRead
}

/**
 * The one amount reader for the money fields (F1): chat Send Payment, the time-lock amount, the payment request, the
 * new-chat custom amount, the AI top-up and the main Send ZEC and USD fields.
 *
 * Money is never guessed. A pasted "1,000" is 1 on an English phone and 1000 on another reading; a pasted "1.500" is
 * 1.5 in English and 1500 in Spanish. Those shapes (1-3 digits, one mark, exactly 3 digits) ask, smaller reading first,
 * so a reflex tap underpays, never overpays. Everything else reads one way only or is refused. The raw text is never
 * logged: a clipboard can hold anything.
 */
object AmountTextReader {
    private const val ARABIC_DECIMAL = '\u066B'
    private const val ARABIC_GROUPING = '\u066C'

    /** Grouping characters a paste may carry, besides '.' and ','. Each must sit between thousands groups. */
    private val SPACE_GROUPING = setOf(' ', '\u00A0', '\u202F', '\u2009', '\u2019', '\'')

    /** Currency signs and the unit each names; longer signs first, so "US$" is not read as "$". */
    private val CURRENCY_TOKENS =
        listOf(
            "US$" to AmountUnit.USD,
            "USD" to AmountUnit.USD,
            "ZEC" to AmountUnit.ZEC,
            "$" to AmountUnit.USD,
            "\u24E9" to AmountUnit.ZEC
        )

    /** "1,000", "1.500", "12,345": the shape that reads as a decimal and as thousands. */
    private val AMBIGUOUS = Regex("^[1-9]\\d{0,2}[.,]\\d{3}$")

    private val THOUSANDS = Regex("^[1-9]\\d{0,2}(?:[.,]\\d{3})+$")

    /** Indian grouping, "12,34,567". */
    private val INDIAN = Regex("^[1-9]\\d?(?:,\\d{2})*,\\d{3}$")

    /** A precision no token reaches (NEAR has 24): what a text reads as with no precision limit. */
    private const val WIDE_DECIMALS = 64

    /**
     * Reads [new] with the rule of [entry], stateless (QA2 m3). For [AmountEntry.PASTE], [old] is the text the paste
     * landed in ("" or a text with nothing kept reads [new] as one whole paste, [readPaste]; digits pasted into a typed
     * amount read as typing reads the result, any other piece is [AmountInvalidReason.PasteIntoAmount]). [maxDecimals] is
     * the unit's precision; [unit] and [askOnAmbiguity] as in [readPaste].
     */
    @Suppress("LongParameterList")
    fun read(
        new: String,
        old: String,
        entry: AmountEntry,
        locale: Locale,
        maxDecimals: Int,
        unit: AmountUnit?,
        askOnAmbiguity: Boolean = true
    ): AmountRead =
        when (entry) {
            AmountEntry.KEYSTROKE, AmountEntry.STORED -> readTyped(new, locale, maxDecimals)
            AmountEntry.PASTE -> {
                val change = AmountEdit.of(asciiDigits(old), asciiDigits(new))
                when {
                    !change.keptSome -> readPaste(new, locale, maxDecimals, unit, askOnAmbiguity)
                    change.inserted.all(Char::isDigit) -> readTyped(new, locale, maxDecimals)
                    else -> AmountRead.Invalid(AmountInvalidReason.PasteIntoAmount)
                }
            }
        }

    /**
     * [text] as typing reads it: digits and at most one mark (any mark is the decimal point), decimals within
     * [maxDecimals]. It never groups, never asks and never trims. A text refused only for its decimals carries what it
     * reads as with no limit ([AmountRead.Invalid.exact]).
     */
    fun readTyped(
        text: String,
        locale: Locale,
        maxDecimals: Int
    ): AmountRead {
        val ascii = asciiDigits(text)
        val read = readPlain(ascii, locale, maxDecimals)
        if (read is AmountRead.Invalid && read.reason is AmountInvalidReason.TooManyDecimals) {
            return read.copy(exact = (readPlain(ascii, locale, WIDE_DECIMALS) as? AmountRead.Exact)?.text)
        }
        return read
    }

    /**
     * [raw] as one whole paste: its structure decides. A shape that reads two ways ("1,000", "1.500", "12,345") is an
     * [AmountRead.Ask] with both readings, each flagged usable or not for [maxDecimals], so a field that can ask asks
     * even when only one reading fits; a field that can't ask ([askOnAmbiguity] false) refuses the shape itself
     * ([AmountInvalidReason.Unreadable]), however many readings fit. A single reading is canonical typed text. [unit]
     * is the field's unit: a sign of any other unit (or any sign when [unit] is null) is
     * [AmountInvalidReason.OtherUnit], never read as the field's unit (T1 fixup, X-F1-1).
     */
    fun readPaste(
        raw: String,
        locale: Locale,
        maxDecimals: Int,
        unit: AmountUnit?,
        askOnAmbiguity: Boolean = true
    ): AmountRead {
        val ascii = asciiDigits(raw)
        val read = readWhole(ascii, locale, maxDecimals, unit, askOnAmbiguity)
        if (read is AmountRead.Invalid && read.reason is AmountInvalidReason.TooManyDecimals) {
            return read.copy(exact = (readWhole(ascii, locale, WIDE_DECIMALS, unit, false) as? AmountRead.Exact)?.text)
        }
        return read
    }

    /**
     * True when [text], exactly as it stands, has the one shape that reads two ways ("1,000", "1.500", "12,345": 1-3 digits,
     * one mark, exactly 3 digits). QA2 m4, ZC-1762: a refused paste that an edit turns into this shape is read as a paste.
     */
    fun readsTwoWays(text: String): Boolean = AMBIGUOUS.matches(asciiDigits(text))

    /**
     * Canonical [text] for a precision of [maxDecimals] without rounding (QA2 m4, ZC-1763): [text] itself when it fits;
     * otherwise the same value with the redundant trailing zeros past [maxDecimals] removed ("0.123456780" is "0.12345678"
     * at 8); null when a decimal that is not zero does not fit.
     */
    fun fitted(
        text: String,
        maxDecimals: Int
    ): String? {
        if (fits(text, maxDecimals)) return text
        // A lone mark has no value to keep; it is never turned into 0.
        if (text.none(Char::isDigit)) return null
        val mark = text.indexOf('.')
        val fraction = text.substring(mark + 1)
        if (fraction.drop(maxDecimals).any { it != '0' }) return null
        val integer = text.substring(0, mark).ifEmpty { "0" }
        return if (maxDecimals == 0) integer else integer + "." + fraction.take(maxDecimals)
    }

    /** True when [text] is digits only (ASCII, Arabic-Indic or Persian), with no mark. */
    fun isDigits(text: String): Boolean = asciiDigits(text).all { it in '0'..'9' }

    /** True when canonical [text] has at most [maxDecimals] decimals (a trailing mark needs a precision above 0). */
    fun fits(
        text: String,
        maxDecimals: Int
    ): Boolean {
        val mark = text.indexOf('.')
        return mark < 0 || (maxDecimals > 0 && text.length - mark - 1 <= maxDecimals)
    }

    /**
     * [value] as a field writes it: plain digits, no grouping, at most [maxDecimals] decimals (a fiat figure is rounded
     * half-even for display; a ZEC figure from zatoshi is exact), trailing zeros dropped, and the decimal mark of
     * [locale].
     */
    fun formatForField(
        value: BigDecimal,
        locale: Locale,
        maxDecimals: Int
    ): String = toFieldText(canonicalOf(value, maxDecimals), locale)

    /** [value] as canonical text ('.' decimal point, no grouping, at most [maxDecimals] decimals). */
    fun canonicalOf(
        value: BigDecimal,
        maxDecimals: Int
    ): String {
        val scaled = if (value.scale() > maxDecimals) value.setScale(maxDecimals, RoundingMode.HALF_EVEN) else value
        return scaled.stripTrailingZeros().toPlainString()
    }

    /** Canonical [text] shown with the decimal mark of [locale] ("1.5" is "1,5" in Spanish). */
    fun toFieldText(
        text: String,
        locale: Locale
    ): String {
        val mark = decimalMarkOf(locale)
        return if (mark == '.') text else text.replace('.', mark)
    }

    /** The decimal mark [locale] writes; '.' when the locale's mark is a digit, a letter or a space. */
    fun decimalMarkOf(locale: Locale): Char {
        val mark = DecimalFormatSymbols(locale).decimalSeparator
        return if (mark.isLetterOrDigit() || mark.isWhitespace()) '.' else mark
    }

    private fun isDecimalMark(
        c: Char,
        locale: Locale
    ): Boolean =
        c == '.' || c == ',' || c == ARABIC_DECIMAL || c == decimalMarkOf(locale)

    /** Arabic-Indic and Persian digits as ASCII digits; nothing else changes. */
    private fun asciiDigits(text: String): String =
        buildString(text.length) {
            text.forEach { c ->
                append(
                    when (c) {
                        in '\u0660'..'\u0669' -> '0' + (c - '\u0660')
                        in '\u06F0'..'\u06F9' -> '0' + (c - '\u06F0')
                        else -> c
                    }
                )
            }
        }

    /** Digits and at most one decimal mark (any mark is the decimal point). Never groups, never asks. */
    @Suppress("ReturnCount")
    private fun readPlain(
        text: String,
        locale: Locale,
        maxDecimals: Int
    ): AmountRead {
        var markAt = -1
        text.forEachIndexed { index, c ->
            when {
                c in '0'..'9' -> Unit
                isDecimalMark(c, locale) -> {
                    if (markAt >= 0) return AmountRead.Invalid(AmountInvalidReason.Unreadable)
                    markAt = index
                }
                else -> return AmountRead.Invalid(AmountInvalidReason.Unreadable)
            }
        }
        if (markAt < 0) return AmountRead.Exact(text)
        val decimals = text.length - markAt - 1
        if (decimals > maxDecimals || maxDecimals == 0) {
            return AmountRead.Invalid(AmountInvalidReason.TooManyDecimals(maxDecimals))
        }
        return AmountRead.Exact(text.substring(0, markAt) + "." + text.substring(markAt + 1))
    }

    @Suppress("ReturnCount", "CyclomaticComplexMethod", "LongMethod")
    private fun readWhole(
        raw: String,
        locale: Locale,
        maxDecimals: Int,
        unit: AmountUnit?,
        askOnAmbiguity: Boolean
    ): AmountRead {
        val unreadable = AmountRead.Invalid(AmountInvalidReason.Unreadable)
        val (stripped, signs) = stripCurrency(raw.trim())
        // A sign names its unit: another unit's sign is never read as this field's unit.
        signs.firstOrNull { it != unit }?.let { return AmountRead.Invalid(AmountInvalidReason.OtherUnit(it)) }
        var text = stripped
        if (text.isEmpty()) return AmountRead.Exact("")

        // A locale decimal mark that is neither '.' nor ',' can only be a decimal point.
        val localeMark = decimalMarkOf(locale)
        if (localeMark != '.' && localeMark != ',' && localeMark != ARABIC_DECIMAL) {
            text = text.replace(localeMark, ARABIC_DECIMAL)
        }

        // Space-like grouping (and the Arabic grouping mark): valid only between thousands groups.
        val grouping = text.filter { it in SPACE_GROUPING || it == ARABIC_GROUPING }.toSet()
        if (grouping.size > 1) return unreadable
        grouping.firstOrNull()?.let { g ->
            val groups = Regex("^[1-9]\\d{0,2}(?:" + Regex.escape(g.toString()) + "\\d{3})+(?:[.,\u066B]\\d*)?$")
            if (!groups.matches(text)) return unreadable
            text = text.replace(g.toString(), "")
            // The groups fixed the integer part, so a '.' or ',' after them is the decimal point.
            val mark = text.indexOfFirst { it == '.' || it == ',' || it == ARABIC_DECIMAL }
            return if (mark < 0) {
                decimalReading(text, "", maxDecimals)
            } else {
                decimalReading(text.substring(0, mark), text.substring(mark + 1), maxDecimals, markWritten = true)
            }
        }

        // The Arabic decimal separator is a decimal point and nothing else.
        if (ARABIC_DECIMAL in text) {
            if (text.count { it == ARABIC_DECIMAL } > 1 || text.any { it == '.' || it == ',' }) return unreadable
            val at = text.indexOf(ARABIC_DECIMAL)
            return decimalReading(text.substring(0, at), text.substring(at + 1), maxDecimals, markWritten = true)
        }

        if (text.any { it !in '0'..'9' && it != '.' && it != ',' }) return unreadable
        val marks = text.indices.filter { text[it] == '.' || text[it] == ',' }
        if (marks.isEmpty()) return decimalReading(text, "", maxDecimals)

        val last = marks.last()
        val lastMark = text[last]
        val mixed = marks.any { text[it] != lastMark }
        return when {
            mixed -> {
                val integer = text.substring(0, last)
                val onlyLastOnce = marks.count { text[it] == lastMark } == 1
                if (onlyLastOnce && (THOUSANDS.matches(integer) || INDIAN.matches(integer))) {
                    decimalReading(integer.filter(Char::isDigit), text.substring(last + 1), maxDecimals, markWritten = true)
                } else {
                    unreadable
                }
            }
            marks.size > 1 ->
                if (THOUSANDS.matches(text) || INDIAN.matches(text)) {
                    decimalReading(text.filter(Char::isDigit), "", maxDecimals)
                } else {
                    unreadable
                }
            AMBIGUOUS.matches(text) -> {
                // A field that can't ask refuses the shape itself, however many readings fit (QA2 m3, ZC-1739).
                if (!askOnAmbiguity) return unreadable
                val asDecimal = BigDecimal(text.substring(0, last) + "." + text.substring(last + 1))
                val asThousands = BigDecimal(text.filter(Char::isDigit))
                // Both readings are kept, never filtered: one that does not fit the precision is shown as not usable.
                val readings =
                    listOf(asDecimal, asThousands)
                        .map { it.stripTrailingZeros().let { v -> if (v.scale() < 0) v.setScale(0) else v } }
                        .sortedWith(compareBy { it })
                        .map { AmountReading(it, it.scale() <= maxDecimals) }
                val usable = readings.filter { it.usable }.map { it.value }
                if (usable.isEmpty()) {
                    AmountRead.Invalid(AmountInvalidReason.TooManyDecimals(maxDecimals))
                } else {
                    AmountRead.Ask(usable, raw, readings)
                }
            }
            else -> decimalReading(text.substring(0, last), text.substring(last + 1), maxDecimals, markWritten = true)
        }
    }

    /**
     * [integer] "." [fraction] as canonical text, refused when its value has more than [maxDecimals] decimals. With
     * [markWritten] the text held a decimal mark, and a trailing one stays ("12." is "12.", as [readPlain] keeps it), so
     * a mark typed after a settled paste is not dropped and the next digit is a decimal (ZC-1714: "12" + "." + "5" is
     * 12.5, never 125).
     */
    private fun decimalReading(
        integer: String,
        fraction: String,
        maxDecimals: Int,
        markWritten: Boolean = false
    ): AmountRead {
        if (integer.isEmpty() && fraction.isEmpty()) return AmountRead.Invalid(AmountInvalidReason.Unreadable)
        if (!integer.all(Char::isDigit) || !fraction.all(Char::isDigit)) {
            return AmountRead.Invalid(AmountInvalidReason.Unreadable)
        }
        val value = BigDecimal((integer.ifEmpty { "0" }) + if (fraction.isEmpty()) "" else ".$fraction")
        if (value.stripTrailingZeros().scale() > maxDecimals) {
            return AmountRead.Invalid(AmountInvalidReason.TooManyDecimals(maxDecimals))
        }
        // Keep the zeros that were written when they fit the unit ("0.50" stays "0.50"); past it they are only zeros, and
        // only those are dropped: a dollar "0.000" is "0.00", never "0" with the mark trimmed away (QA2 m4, ZC-1761: a 5
        // typed after it must never become the amount 5).
        val keptFraction = if (fraction.length <= maxDecimals) fraction else fraction.take(maxDecimals)
        val tail =
            when {
                keptFraction.isNotEmpty() -> ".$keptFraction"
                markWritten && fraction.isEmpty() && maxDecimals > 0 -> "."
                else -> ""
            }
        return AmountRead.Exact(integer.ifEmpty { "0" } + tail)
    }

    /** [text] without the currency signs at either end, and the units those signs name. */
    private fun stripCurrency(text: String): Pair<String, Set<AmountUnit>> {
        var result = text
        val signs = mutableSetOf<AmountUnit>()
        var changed = true
        while (changed) {
            changed = false
            for ((token, unit) in CURRENCY_TOKENS) {
                if (result.startsWith(token, ignoreCase = true)) {
                    result = result.substring(token.length).trim()
                    signs += unit
                    changed = true
                }
                if (result.endsWith(token, ignoreCase = true)) {
                    result = result.substring(0, result.length - token.length).trim()
                    signs += unit
                    changed = true
                }
            }
        }
        return result to signs
    }
}

/**
 * QA2 m8: what an amount machine did with an edit. [state] is the machine's next state; [taken] is false when the machine
 * refused the edit and kept its state (a key it does not take is not shown, owner default 2). A taken edit may leave a state
 * equal to the old one (ZC-1780), so a refusal is never read from equality.
 */
data class AmountEditResult<S>(
    val state: S,
    val taken: Boolean
)

/**
 * One amount field's reading state (F1; QA2 m3: one state machine with no paste origin). It is one of three states:
 *
 * - typed ([readings] and [error] null): [shown] is the field's text and [settled] the amount that counts, the typing
 *   reading of the text. "" is the empty field. Nothing remembers how the text arrived: a settled paste is typed text.
 * - question ([readings] set): [shown] is the paste as pasted, [readings] both of its readings; nothing is settled.
 * - refused ([error] set): [shown] is the text as shown and [error] why; nothing is settled. [exact] is the one value a
 *   text refused only for its decimals reads as with no precision limit (a field whose precision grows uses it).
 *
 * A raw paste never reaches [settled], so nothing downstream (the button, the fee, the send) can read "1.500" as 1.5
 * while the question is open.
 *
 * The rule is chosen by the event ([edit]), never by history: a key is read as typing reads it, a paste as a whole paste
 * is read. The one exception is an open question, where a key discards the paste and empties the field.
 */
data class AmountFieldState(
    val shown: String = "",
    val settled: String = "",
    val readings: List<AmountReading>? = null,
    val error: AmountInvalidReason? = null,
    val exact: String? = null,
) {
    /** The usable readings of an open question, ascending; null when no question is open. */
    val ask: List<BigDecimal>? get() = readings?.filter { it.usable }?.map { it.value }

    /** The readings of an open question that do not fit the field's precision (shown as not usable). */
    val notUsable: List<BigDecimal> get() = readings?.filterNot { it.usable }?.map { it.value }.orEmpty()

    /** A question or an error is open: the amount is not usable and the send button stays disabled. */
    val isQuestionOpen: Boolean get() = readings != null || error != null

    /**
     * The field's next state after the text [new] arrives (a keystroke or a paste), in a field of [unit] (which also
     * gives the precision). A paste with another unit's currency sign is refused (T1 fixup, X-F1-1).
     */
    fun edit(
        new: String,
        locale: Locale,
        unit: AmountUnit,
        askOnAmbiguity: Boolean = true
    ): AmountFieldState = edit(new, locale, unit.maxDecimals, unit, askOnAmbiguity)

    /**
     * The same edit for a field whose precision is not its unit's (F12: a swap token with its own decimals, the
     * slippage percent, a block height). [unit] null is a field with no currency: any currency sign is refused.
     * [selectionBefore] is the selection the field had before the edit (start..end), null when the field does not know
     * it (QA2 m3, design section 2):
     *
     * - nothing of the old text kept (a whole replace): the field is emptied first; several characters are a whole
     *   paste, one character is typed;
     * - on an open question, a key (one character or a deletion) empties the field, and a paste into it asks again when
     *   it still reads two ways, else it is refused (ZC-1724, ZC-1725, ZC-1746);
     * - a piece with marks pasted into the text is refused ([AmountInvalidReason.PasteIntoAmount]); digits are typed;
     * - otherwise the new text is read as typing reads it. On a typed field a key that cannot be taken is not taken
     *   (the text stays as it was: a third dollar decimal, a second mark, ZC-1740); on a refused text the new text is
     *   either the typing reading or stays refused, so a refused paste can be trimmed by typing (ZC-1750) and is never
     *   grouped (ZC-1751).
     */
    @Suppress("LongParameterList")
    fun edit(
        new: String,
        locale: Locale,
        maxDecimals: Int,
        unit: AmountUnit?,
        askOnAmbiguity: Boolean,
        selectionBefore: IntRange? = null
    ): AmountFieldState = editResult(new, locale, maxDecimals, unit, askOnAmbiguity, selectionBefore).state

    /**
     * QA2 m8 (ZC-1780, ZC-1774): [edit] and whether the machine TOOK the edit. Not taken are exactly the exits that keep
     * this state: the new text equals the shown text, a key that cannot be taken, a read that asks. Every other exit is
     * taken, also when the new state equals this one ("$1.2" pasted over "1.2" reads as 1.2).
     */
    @Suppress("LongParameterList")
    fun editResult(
        new: String,
        locale: Locale,
        maxDecimals: Int,
        unit: AmountUnit?,
        askOnAmbiguity: Boolean,
        selectionBefore: IntRange?
    ): AmountEditResult<AmountFieldState> {
        val next = editedOrThis(new, locale, maxDecimals, unit, askOnAmbiguity, selectionBefore)
        return AmountEditResult(next, taken = next !== this)
    }

    /** The body of [edit]: `this` exactly when the edit is not taken. */
    @Suppress("ReturnCount", "LongParameterList")
    private fun editedOrThis(
        new: String,
        locale: Locale,
        maxDecimals: Int,
        unit: AmountUnit?,
        askOnAmbiguity: Boolean,
        selectionBefore: IntRange?
    ): AmountFieldState {
        if (new.isEmpty()) return AmountFieldState()
        if (new == shown) return this
        val change = AmountEdit.of(shown, new, selectionBefore)
        if (!change.keptSome) {
            if (change.inserted.length >= 2) return pasted(new, locale, maxDecimals, unit, askOnAmbiguity)
            return (AmountTextReader.readTyped(new, locale, maxDecimals) as? AmountRead.Exact)?.let { typed(it.text) }
                ?: AmountFieldState()
        }
        if (readings != null) {
            // An unanswered paste is never read as typing: a key discards it. A key the selection proves (one character
            // typed at the cursor) is then applied to the emptied field, so "1,000" then ".50" is 0.50 (QA2 m4, ZC-1760);
            // without that proof the field is only emptied.
            if (change.inserted.length <= 1) {
                if (change.fromSelection && change.inserted.length == 1) {
                    return (AmountTextReader.readTyped(change.inserted, locale, maxDecimals) as? AmountRead.Exact)
                        ?.let { typed(it.text) }
                        ?: AmountFieldState()
                }
                return AmountFieldState()
            }
            val again = pasted(new, locale, maxDecimals, unit, askOnAmbiguity)
            return if (again.readings != null) again else AmountFieldState(shown = new, error = AmountInvalidReason.PasteIntoAmount)
        }
        // A text refused for anything but its decimals (another unit's sign, letters, marks that read no way) that an edit
        // turns into the shape that reads two ways is read as the paste it was: "$1,000" minus the "$" asks 1 or 1000, and a
        // field that can't ask refuses it (QA2 m4, ZC-1762). A text refused for its decimals is trimmed by typing (ZC-1750).
        if (error != null && error !is AmountInvalidReason.TooManyDecimals && AmountTextReader.readsTwoWays(new)) {
            return pasted(new, locale, maxDecimals, unit, askOnAmbiguity)
        }
        if (change.inserted.length >= 2 && !AmountTextReader.isDigits(change.inserted)) {
            return AmountFieldState(shown = new, error = AmountInvalidReason.PasteIntoAmount)
        }
        return when (val read = AmountTextReader.readTyped(new, locale, maxDecimals)) {
            is AmountRead.Exact -> typed(read.text)
            is AmountRead.Invalid ->
                when {
                    error != null || change.inserted.length != 1 ->
                        AmountFieldState(shown = new, error = read.reason, exact = read.exact)
                    // A key that cannot be taken is not taken: the typed text stays, with no error. That is only known to be
                    // a key when the selection says so or the text grew by one character.
                    change.fromSelection || new.length == shown.length + 1 -> this
                    // QA2 m4, ZC-1759: the selection is unknown and one character replaced part of the text. It may be a
                    // select-all paste that shares its first and last characters with the old text ("15234.5", select all,
                    // "1,234.5"), so it is refused out loud and the old amount no longer counts.
                    else -> AmountFieldState(shown = new, error = AmountInvalidReason.PasteIntoAmount)
                }
            is AmountRead.Ask -> this
        }
    }

    /**
     * The reading the user tapped: written as canonical text; the question closes. Only a usable reading of the open
     * question is taken (QA2 m4, ZC-1765): any other value leaves the state as it is, so nothing is rounded or made up.
     */
    fun choose(
        value: BigDecimal,
        maxDecimals: Int
    ): AmountFieldState {
        val reading = readings?.firstOrNull { it.usable && it.value.compareTo(value) == 0 } ?: return this
        return typed(AmountTextReader.canonicalOf(reading.value, maxDecimals))
    }

    /**
     * Reads the field again for a new unit and its precision (the payment request's ZEC/USD switch). An open question
     * and a text refused for another unit's sign are read again as the paste they are; typed text stays typed text (a
     * text the new precision can't hold is refused, keeping its value in [exact]); a text refused only for its
     * decimals becomes typed text once [exact] fits (ZC-1752).
     */
    fun reread(
        locale: Locale,
        unit: AmountUnit
    ): AmountFieldState {
        if (shown.isEmpty()) return AmountFieldState()
        if (readings != null || error is AmountInvalidReason.OtherUnit) {
            return pasted(shown, locale, unit.maxDecimals, unit, true)
        }
        return withPrecision(unit.maxDecimals)
    }

    /**
     * This state for a field whose precision is now [maxDecimals] (a swap token was chosen, the unit switched). Typed
     * text that does not fit is refused; a question's readings are flagged again; a text refused only for its decimals
     * is typed text once its [exact] value fits, and otherwise names the new precision.
     */
    fun withPrecision(maxDecimals: Int): AmountFieldState =
        when {
            readings != null ->
                copy(
                    readings =
                        readings.map { it.copy(usable = it.value.stripTrailingZeros().scale() <= maxDecimals) }
                )
            error == null ->
                if (settled.isEmpty() || AmountTextReader.fits(settled, maxDecimals)) {
                    this
                } else {
                    AmountFieldState(
                        shown = shown,
                        error = AmountInvalidReason.TooManyDecimals(maxDecimals),
                        exact = settled
                    )
                }
            // QA2 m4, ZC-1763: redundant trailing zeros do not count against the precision ("0.123456780" fits ZEC).
            error is AmountInvalidReason.TooManyDecimals && exact != null ->
                AmountTextReader.fitted(exact, maxDecimals)?.let { typed(it) }
                    ?: copy(error = AmountInvalidReason.TooManyDecimals(maxDecimals))
            else -> this
        }

    companion object {
        /** A field holding the canonical amount [text] the app wrote (a prefill or a preset). */
        fun settled(text: String): AmountFieldState = typed(text)

        private fun typed(text: String): AmountFieldState = AmountFieldState(shown = text, settled = text)

        /** [new] read as one whole paste into an empty field. */
        @Suppress("LongParameterList")
        private fun pasted(
            new: String,
            locale: Locale,
            maxDecimals: Int,
            unit: AmountUnit?,
            askOnAmbiguity: Boolean
        ): AmountFieldState =
            when (val read = AmountTextReader.readPaste(new, locale, maxDecimals, unit, askOnAmbiguity)) {
                is AmountRead.Exact -> typed(read.text)
                is AmountRead.Ask -> AmountFieldState(shown = new, readings = read.readings)
                is AmountRead.Invalid -> AmountFieldState(shown = new, error = read.reason, exact = read.exact)
            }
    }
}
