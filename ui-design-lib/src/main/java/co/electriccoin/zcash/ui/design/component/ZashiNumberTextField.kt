package co.electriccoin.zcash.ui.design.component

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import co.electriccoin.zcash.ui.design.theme.ZcashTheme
import co.electriccoin.zcash.ui.design.theme.typography.ZashiTypography
import co.electriccoin.zcash.ui.design.util.AmountEditResult
import co.electriccoin.zcash.ui.design.util.AmountFieldState
import co.electriccoin.zcash.ui.design.util.AmountInvalidReason
import co.electriccoin.zcash.ui.design.util.AmountRead
import co.electriccoin.zcash.ui.design.util.AmountReading
import co.electriccoin.zcash.ui.design.util.AmountTextReader
import co.electriccoin.zcash.ui.design.util.StringResource
import co.electriccoin.zcash.ui.design.util.getString
import co.electriccoin.zcash.ui.design.util.getValue
import co.electriccoin.zcash.ui.design.util.rememberDesiredFormatLocale
import co.electriccoin.zcash.ui.design.util.stringRes
import co.electriccoin.zcash.ui.design.util.stringResByDynamicNumber
import java.math.BigDecimal
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.text.ParseException
import java.util.Locale

@Suppress("LongParameterList")
@Composable
fun ZashiNumberTextField(
    state: NumberTextFieldState,
    modifier: Modifier = Modifier,
    innerModifier: Modifier = ZashiTextFieldDefaults.innerModifier,
    textStyle: TextStyle = ZashiNumberTextFieldDefaults.textStyle,
    placeholder: @Composable (() -> Unit)? = { ZashiNumberTextFieldDefaults.Placeholder() },
    prefix: @Composable (() -> Unit)? = null,
    suffix: @Composable (() -> Unit)? = null,
    leadingIcon: @Composable (() -> Unit)? = null,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    keyboardOptions: KeyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
    shape: Shape = ZashiTextFieldDefaults.shape,
    contentPadding: PaddingValues =
        ZashiTextFieldDefaults.contentPadding(
            leadingIcon = leadingIcon,
            suffix = suffix,
            trailingIcon = null,
            prefix = prefix
        ),
    colors: ZashiTextFieldColors = ZashiTextFieldDefaults.defaultColors()
) {
    val textFieldState = createTextFieldState(state)
    ZashiTextField(
        state = textFieldState,
        modifier = modifier,
        innerModifier = innerModifier,
        textStyle = textStyle,
        placeholder = placeholder,
        leadingIcon = leadingIcon,
        trailingIcon = null,
        prefix = prefix,
        suffix = suffix,
        visualTransformation = visualTransformation,
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        singleLine = true,
        maxLines = 1,
        minLines = 1,
        interactionSource = interactionSource,
        shape = shape,
        contentPadding = contentPadding,
        colors = colors,
    )
}

@Composable
private fun createTextFieldState(state: NumberTextFieldState): EnhancedTextFieldState {
    val context = LocalContext.current
    val locale = rememberDesiredFormatLocale()
    // QA2 m7, ZC-1775 ZC-1776: one editor per field, kept across compositions, decides what each proposal does.
    val editor = remember { NumberFieldEditor() }
    return EnhancedTextFieldState(
        innerState = editor.shown(state.innerState, locale),
        isEnabled = state.isEnabled,
        error = state.explicitError ?: state.defaultNumberError.takeIf { state.innerState.isError },
        onValueChange = { innerState ->
            val proposal = innerState.copy(value = stringRes(innerState.value.getString(context, locale)))
            state.onValueChange(editor.proposed(proposal, state.reading, locale))
        }
    )
}

/**
 * What a number field does with each composition and each proposal of its ZashiTextField (QA2 m7).
 *
 * F12: the field reads through the one amount reader of F1 (AmountTextReader), with this field's precision, unit and
 * question rule. The event decides the rule (QA2 m3): a typed mark is the decimal point, a paste that reads two ways asks,
 * and a key on an open question empties the field; the selection before the edit tells a select-all paste from a key.
 *
 * ZC-1775: when the machine keeps the text the field showed (a key that is not taken), the field keeps the selection it
 * had before the edit, so the next key lands where the user's caret was. A proposal that only moves the selection is stored.
 *
 * ZC-1776: each proposal is edited against the latest state: the one the last composition showed, or the one the last
 * proposal since then made, so two proposals before a recomposition are both edited, the second against the first.
 *
 * QA2 m8 (ZC-1779, ZC-1781, ZC-1783): each proposal is made on BasicTextField's own buffer (the last proposal), not on what
 * the machine holds. It goes through [AmountFieldRebase.step] (the same rules as [AmountTextFieldSelection]): the state
 * handed to the owner keeps the machine's text and the selection the field shows (ACCEPTED); the editor keeps the buffer
 * and where its places stand in ACCEPTED until the next composition. A proposal that leaves the buffer different from
 * ACCEPTED bumps a tick [shown] reads, so the field recomposes in the frame in which BasicTextField resets its buffer.
 */
class NumberFieldEditor {
    private var latest: NumberTextFieldInnerState = NumberTextFieldInnerState()
    private var composed: NumberTextFieldInnerState? = null
    private var composedTick = -1
    private var tick by mutableIntStateOf(0)
    private var buffer: AmountFieldRebase.Value? = null
    private var map: IntArray? = null

    /** Each composition: what ZashiTextField is given for the owner's [state] (the shown text, the selection coerced to it). */
    fun shown(
        state: NumberTextFieldInnerState,
        locale: Locale
    ): InnerTextFieldState {
        val now = tick
        if (composed == null || composed != state || composedTick != now) {
            buffer = null
            map = null
            latest = state
            composed = state
            composedTick = now
        }
        return shownOf(state, locale)
    }

    /** Each proposal the filter reports (plain text): the state to hand to the owner. */
    fun proposed(
        proposal: InnerTextFieldState,
        reading: NumberFieldReading,
        locale: Locale
    ): NumberTextFieldInnerState {
        val base = latest
        val textOf: (NumberTextFieldInnerState) -> String = { it.shownText(it.storedText(), locale) }
        val accepted = valueOf(base, textOf(base))
        val newText = proposal.value.plainText()
        val range = proposal.getTextRange(newText)
        val callback = AmountFieldRebase.Value(newText, range.start, range.end)
        val held = buffer
        val out =
            AmountFieldRebase.step(
                accepted = accepted,
                buffer = held ?: accepted,
                map = if (held != null) map else null,
                callback = callback,
                state = base,
                textOf = textOf
            ) { state, text, selectionBefore ->
                state.editResult(
                    new = text,
                    shown = textOf(state),
                    locale = locale,
                    reading = reading,
                    selectionBefore = selectionBefore
                )
            }
        if (out.ignored) return base
        val next =
            out.state.copy(
                innerTextFieldState =
                    out.state.innerTextFieldState.copy(
                        selection = TextSelection.ByTextRange(TextRange(out.accepted.a, out.accepted.b))
                    )
            )
        latest = next
        if (out.settles(callback)) {
            buffer = null
            map = null
        } else {
            buffer = callback
            map = out.map
            tick++
        }
        return next
    }

    /** [state] as a field value showing [text], its selection coerced to it. */
    private fun valueOf(
        state: NumberTextFieldInnerState,
        text: String
    ): AmountFieldRebase.Value =
        when (val selection = state.innerTextFieldState.selection) {
            is TextSelection.ByTextRange -> AmountFieldRebase.Value.coerced(text, selection.range.start, selection.range.end)
            TextSelection.End -> AmountFieldRebase.Value(text, text.length, text.length)
            TextSelection.Start -> AmountFieldRebase.Value(text, 0, 0)
        }

    private fun shownOf(
        state: NumberTextFieldInnerState,
        locale: Locale
    ): InnerTextFieldState {
        val text = state.shownText(state.storedText(), locale)
        val selection =
            when (val selection = state.innerTextFieldState.selection) {
                is TextSelection.ByTextRange ->
                    TextSelection.ByTextRange(
                        TextRange(
                            selection.range.start.coerceAtMost(text.length),
                            selection.range.end.coerceAtMost(text.length)
                        )
                    )

                TextSelection.End -> selection
                TextSelection.Start -> selection
            }
        return state.innerTextFieldState.copy(value = stringRes(text), selection = selection)
    }

    private fun StringResource.plainText(): String = (this as? StringResource.ByString)?.value.orEmpty()
}

/**
 * How a number field reads its text (F12, fix-all 2.13.0): through F1's [AmountTextReader] with [maxDecimals] (the
 * precision of what the field holds: ZEC 8, dollars 2, a swap token its own decimals, a block height 0), [unit] (the
 * currency a pasted sign may name; null is a field with no currency, where any sign is refused), and
 * [askOnAmbiguity] (a paste that reads two ways asks, or, in a field that can't ask, is refused and never guessed).
 * [ticker] labels the readings of a field with no unit ("1.5 USDC").
 */
@Immutable
data class NumberFieldReading(
    val maxDecimals: Int,
    val unit: AmountUnit? = null,
    val askOnAmbiguity: Boolean = true,
    val ticker: String? = null,
) {
    companion object {
        /** A ZEC amount. */
        val ZEC = NumberFieldReading(maxDecimals = AmountUnit.ZEC.maxDecimals, unit = AmountUnit.ZEC)

        /** A dollar amount. */
        val USD = NumberFieldReading(maxDecimals = AmountUnit.USD.maxDecimals, unit = AmountUnit.USD)

        /** A block height: whole numbers only; the field can't ask. */
        val BLOCK_HEIGHT = NumberFieldReading(maxDecimals = 0, unit = null, askOnAmbiguity = false)

        /** A swap slippage percent: 2 decimals (the quote sends basis points); the field can't ask. */
        val SLIPPAGE_PERCENT = NumberFieldReading(maxDecimals = 2, unit = null, askOnAmbiguity = false)

        /** A token amount with [decimals] and [ticker]. */
        fun token(
            decimals: Int,
            ticker: String?
        ) = NumberFieldReading(maxDecimals = decimals, unit = null, askOnAmbiguity = true, ticker = ticker)
    }
}

@Immutable
data class NumberTextFieldState(
    val innerState: NumberTextFieldInnerState = NumberTextFieldInnerState(),
    val isEnabled: Boolean = true,
    val explicitError: StringResource? = null,
    val defaultNumberError: StringResource = stringRes(""),
    val reading: NumberFieldReading = NumberFieldReading.ZEC,
    val onValueChange: (NumberTextFieldInnerState) -> Unit,
) {
    val isError = explicitError != null || innerState.isError

    /** The inner state after the text [new] arrives, read as this field reads. */
    fun edit(
        new: String,
        locale: Locale
    ): NumberTextFieldInnerState =
        innerState.edit(new = new, shown = innerState.shownText(innerState.storedText(), locale), locale = locale, reading = reading)

    /** The reading the user tapped under the field. */
    fun choose(value: BigDecimal): NumberTextFieldInnerState = innerState.choose(value, reading)
}

/**
 * A number field's text and amount.
 *
 * - [innerTextFieldState]: while the amount is settled, the canonical text ('.' as the decimal point, never grouped;
 *   the field shows it with the phone's mark); while a question or an error is open, the paste as it was pasted.
 * - [amount]: the amount that counts, null while a question or an error is open. A raw paste never reaches it.
 * - [readings], [readError], [exact]: F1's AmountFieldState for this field (F12; QA2 m3, no paste origin). [ask] is the
 *   usable readings of an open question. [exact] is, for a text refused only for its decimals, the value it reads as
 *   with no precision limit, so a field whose precision grows (a swap or pay token with more decimals was chosen)
 *   takes it as typed text ([limitedTo]).
 */
@Immutable
data class NumberTextFieldInnerState(
    val innerTextFieldState: InnerTextFieldState =
        InnerTextFieldState(
            value = stringRes(value = ""),
            selection = TextSelection.Start
        ),
    val amount: BigDecimal? = null,
    val lastValidAmount: BigDecimal? = null,
    val readings: List<AmountReading>? = null,
    val readError: AmountInvalidReason? = null,
    val exact: String? = null,
) {
    val isError = amount == null && !innerTextFieldState.value.isEmpty()

    /** The usable readings of an open question, ascending; null when no question is open. */
    val ask: List<BigDecimal>? get() = readings?.filter { it.usable }?.map { it.value }

    /** The readings of an open question that do not fit the field's precision (shown disabled). */
    val notUsable: List<BigDecimal> get() = readings?.filterNot { it.usable }?.map { it.value }.orEmpty()

    /** A question or a reading error is open: the amount is not usable. */
    val isQuestionOpen: Boolean get() = readings != null || readError != null

    /** This state as F1's field state, the field showing [shown]. */
    private fun fieldState(shown: String): AmountFieldState =
        AmountFieldState(
            shown = shown,
            settled = if (isQuestionOpen) "" else storedText(),
            readings = readings,
            error = readError,
            exact = exact
        )

    /** The stored text when it is plain text (every writer of F12 stores plain text). */
    internal fun storedText(): String = (innerTextFieldState.value as? StringResource.ByString)?.value.orEmpty()

    /** What the field shows for its [stored] text: a settled amount with the phone's decimal mark, a paste as pasted. */
    fun shownText(
        stored: String,
        locale: Locale
    ): String = if (isQuestionOpen) stored else AmountTextReader.toFieldText(stored, locale)

    /**
     * The state after [new] replaces [shown] (what the field showed), read with [reading]. [selectionBefore] is the
     * field's selection before the edit (start..end), null when it is not known (QA2 m3, design rule 7).
     */
    fun edit(
        new: String,
        shown: String,
        locale: Locale,
        reading: NumberFieldReading,
        selectionBefore: IntRange? = null
    ): NumberTextFieldInnerState = editResult(new, shown, locale, reading, selectionBefore).state

    /** QA2 m8 (ZC-1780, ZC-1774): [edit] and whether the amount machine took the edit (false: this state stays). */
    fun editResult(
        new: String,
        shown: String,
        locale: Locale,
        reading: NumberFieldReading,
        selectionBefore: IntRange?
    ): AmountEditResult<NumberTextFieldInnerState> {
        val before = fieldState(shown)
        val result = before.editResult(new, locale, reading.maxDecimals, reading.unit, reading.askOnAmbiguity, selectionBefore)
        if (!result.taken) return AmountEditResult(this, taken = false)
        return AmountEditResult(fromFieldState(result.state, innerTextFieldState.selection, lastValidAmount), taken = true)
    }

    /**
     * The reading the user tapped: the question closes and the amount is the one tapped. Only a usable reading of the
     * open question is taken (QA2 m4, ZC-1765); any other value leaves the field as it is.
     */
    fun choose(
        value: BigDecimal,
        reading: NumberFieldReading
    ): NumberTextFieldInnerState {
        val before = fieldState(storedText())
        val after = before.choose(value, reading.maxDecimals)
        if (after === before) return this
        return fromFieldState(after, TextSelection.End, lastValidAmount)
    }

    /**
     * This state for a field whose precision is now [maxDecimals] (a swap or pay token was chosen; the precision event of
     * QA2 m3): an amount with more decimals is not used and says why; a question's readings are flagged again; a text
     * refused only for its decimals is typed text once its [exact] value fits, and otherwise names [maxDecimals].
     */
    fun limitedTo(maxDecimals: Int): NumberTextFieldInnerState {
        val before = fieldState(storedText())
        val after = before.withPrecision(maxDecimals)
        if (after == before) return this
        return fromFieldState(after, innerTextFieldState.selection, lastValidAmount)
    }

    companion object {
        /**
         * An amount from outside the app (a scanned request), in a field of [maxDecimals]: written as plain canonical
         * text, never rounded. More decimals than the field holds is an error the field shows, not a rounded amount.
         */
        fun fromAmount(
            amount: BigDecimal,
            maxDecimals: Int
        ): NumberTextFieldInnerState {
            val text = amount.stripTrailingZeros().let { if (it.scale() < 0) it.setScale(0) else it }.toPlainString()
            return if (amount.stripTrailingZeros().scale() > maxDecimals) {
                NumberTextFieldInnerState(
                    innerTextFieldState = InnerTextFieldState(value = stringRes(text), selection = TextSelection.End),
                    readError = AmountInvalidReason.TooManyDecimals(maxDecimals),
                    exact = text
                )
            } else {
                settled(text, TextSelection.Start)
            }
        }

        /**
         * A figure the app worked out (the other field of Pay, the Swap dollar switch), at the field's [maxDecimals]
         * (rounded half-even): the field holds exactly the amount it shows.
         */
        @JvmOverloads
        fun fromComputed(
            amount: BigDecimal,
            maxDecimals: Int,
            selection: TextSelection = TextSelection.End
        ): NumberTextFieldInnerState = settled(AmountTextReader.canonicalOf(amount, maxDecimals), selection)

        private fun settled(
            canonical: String,
            selection: TextSelection
        ): NumberTextFieldInnerState {
            val value = AmountRead.Exact(canonical).value
            return NumberTextFieldInnerState(
                innerTextFieldState = InnerTextFieldState(value = stringRes(canonical), selection = selection),
                amount = value,
                lastValidAmount = value
            )
        }

        private fun fromFieldState(
            field: AmountFieldState,
            selection: TextSelection,
            lastValidAmount: BigDecimal?
        ): NumberTextFieldInnerState {
            val value = if (field.isQuestionOpen) null else AmountRead.Exact(field.settled).value
            return NumberTextFieldInnerState(
                innerTextFieldState =
                    InnerTextFieldState(
                        value = stringRes(if (field.isQuestionOpen) field.shown else field.settled),
                        selection = selection
                    ),
                amount = value,
                lastValidAmount = value ?: lastValidAmount,
                readings = field.readings,
                readError = field.error,
                exact = field.exact
            )
        }
    }
}

/**
 * F12: the question under a number field when a paste reads two ways, or why a paste can't be used (F1's chooser).
 * A tap only fills the field. Nothing is drawn while the field has no open question.
 */
@Composable
fun ZashiNumberTextFieldQuestion(
    state: NumberTextFieldState,
    modifier: Modifier = Modifier,
) {
    val inner = state.innerState
    if (!inner.isQuestionOpen) return
    val locale = rememberDesiredFormatLocale()
    val raw = inner.innerTextFieldState.value.getValue()
    val onChoose: (BigDecimal) -> Unit = { state.onValueChange(state.choose(it)) }
    val unit = state.reading.unit
    if (unit != null) {
        AmountReadingChooser(
            raw = raw,
            options = inner.ask,
            notUsable = inner.notUsable,
            error = inner.readError,
            unit = unit,
            locale = locale,
            onChoose = onChoose,
            modifier = modifier
        )
    } else {
        NumberReadingChooser(
            raw = raw,
            options = inner.ask,
            notUsable = inner.notUsable,
            error = inner.readError,
            maxDecimals = state.reading.maxDecimals,
            ticker = state.reading.ticker,
            locale = locale,
            onChoose = onChoose,
            modifier = modifier
        )
    }
}

object ZashiNumberTextFieldDefaults {
    val textStyle
        @Composable get() = ZashiTypography.textMd.copy(fontWeight = FontWeight.Medium)

    @Composable
    fun Placeholder(
        modifier: Modifier = Modifier,
        style: TextStyle = ZashiTypography.textMd,
        fontWeight: FontWeight = FontWeight.Normal,
        textAlign: TextAlign = TextAlign.Start,
        contentAlignment: Alignment = Alignment.CenterStart,
        text: String = stringResByDynamicNumber(0).getValue()
    ) {
        ZashiAutoSizeText(
            text = text,
            modifier = modifier,
            style = style,
            fontWeight = fontWeight,
            textAlign = textAlign,
            maxLines = 1,
            contentAlignment = contentAlignment
        )
    }
}

object ZashiNumberTextFieldParser {
    fun normalizeInput(input: String, locale: Locale): String {
        val symbols = DecimalFormatSymbols(locale)
        val decimalSeparator = symbols.decimalSeparator

        val cleaned =
            input
                .replace("\u00A0", "") // NO-BREAK SPACE
                .replace("\u202F", "") // NARROW NO-BREAK SPACE
                .replace("\u0020", "") // REGULAR SPACE

        // Which '.' or ',' (if any) is the decimal point. -1: none, every mark groups thousands.
        // null: the marks cannot be read one way only, so the text is returned as typed and fails
        // validation (the field shows its invalid-amount error) instead of being guessed.
        val decimalIndex = decimalMarkIndex(cleaned, decimalSeparator) ?: return cleaned

        return buildString {
            cleaned.forEachIndexed { index, c ->
                when {
                    c == '.' || c == ',' -> if (index == decimalIndex) append(decimalSeparator)
                    else -> append(c)
                }
            }
        }
    }

    /**
     * Where the decimal point is in [text]: its index, -1 when every mark is a thousands separator, or
     * null when the marks are ambiguous. The rule (R2-1 a) is that a mark is read as grouping ONLY when
     * the digits around it look like thousands groups; anything else that can be a decimal point is one,
     * because stripping a mark multiplies the amount (0.5 typed on a German phone used to pay 5 ZEC):
     * - both '.' and ',' present: the right-most is the decimal point. It must appear once, and the
     *   part before it must read as thousands groups; otherwise null.
     * - one mark, once: the locale decimal separator is the decimal point. The other mark groups only
     *   when 1-3 digits not starting with 0 come before it and exactly 3 digits after it ("1,234" on a
     *   US phone is 1234); otherwise ("0,5", "1,5", "12,50", "0,00001", a trailing "1,") it is the
     *   decimal point and is shown as the locale's separator.
     * - one mark, repeated: thousands groups ("1.234.567"), or null ("1.2.3").
     */
    private fun decimalMarkIndex(
        text: String,
        localeDecimal: Char
    ): Int? {
        val marks = text.indices.filter { text[it] == '.' || text[it] == ',' }
        if (marks.isEmpty()) return -1
        val last = marks.last()
        val lastMark = text[last]
        val mixed = marks.any { text[it] != lastMark }
        return when {
            mixed ->
                if (marks.count { text[it] == lastMark } == 1 && readsAsThousands(text.substring(0, last))) {
                    last
                } else {
                    null
                }
            marks.size == 1 ->
                if (lastMark != localeDecimal && readsAsThousands(text)) -1 else last
            else -> if (readsAsThousands(text)) -1 else null
        }
    }

    /** "1,234", "12.345.678": a first group of 1-3 digits not starting with 0, then groups of exactly 3. */
    private fun readsAsThousands(text: String): Boolean = thousandsGroups.matches(text)

    private val thousandsGroups = Regex("^[1-9]\\d{0,2}(?:[.,]\\d{3})+$")

    /**
     * Convert user's [input] to a number of type [BigDecimal]. Decimal separator is derived from [locale].
     *
     * This function first validates the input format, then uses DecimalFormat with isParseBigDecimal=true
     * to parse the string. According to Java documentation, when isParseBigDecimal is true, parse() should
     * always return BigDecimal directly, making type conversion unnecessary in most cases.
     *
     * @param input The normalized string to parse (should be already processed by normalizeInput)
     * @param locale The locale to use for parsing (determines decimal separator)
     * @return [BigDecimal] if [input] is a valid number representation, null otherwise
     */
    @Suppress("ReturnCount")
    fun toBigDecimalOrNull(input: String, locale: Locale): BigDecimal? {
        val symbols = DecimalFormatSymbols(locale)

        if (!isValidNumericWithOptionalDecimalSeparator(input = input, symbols = symbols)) return null

        val pattern = (DecimalFormat.getInstance(locale) as? DecimalFormat)?.toPattern()

        val decimalFormat =
            if (pattern != null) {
                DecimalFormat(pattern, symbols).apply { this.isParseBigDecimal = true }
            } else {
                DecimalFormat().apply {
                    this.decimalFormatSymbols = symbols
                    this.isParseBigDecimal = true
                }
            }

        return try {
            when (val parsedNumber = decimalFormat.parse(input)) {
                null -> null
                is BigDecimal -> parsedNumber
                // The following branches should rarely/never execute when isParseBigDecimal=true,
                // but are kept as defensive fallbacks. Using string conversion to avoid precision loss.
                is Double -> BigDecimal.valueOf(parsedNumber)
                is Float -> BigDecimal(parsedNumber.toString())
                else -> BigDecimal(parsedNumber.toString())
            }
        } catch (_: ParseException) {
            return null
        } catch (_: NumberFormatException) {
            return null
        }
    }

    /**
     * Validates if the input string represents a valid numeric format with an optional decimal separator.
     *
     * Valid patterns:
     * - Integer: "123"
     * - Decimal: "123.456" or "123,456" (depending on locale)
     * - Trailing separator: "123." or "123,"
     * - Leading separator: ".123" or ",123"
     * - Zero: "0"
     *
     * Invalid patterns:
     * - Empty: ""
     * - Only separator: "." or ","
     * - Multiple separators: "12.34.56"
     * - Non-numeric: "abc", "12a34"
     *
     * @param input The string to validate (should be already normalized via normalizeInput)
     * @param symbols The decimal format symbols containing the decimal separator for the locale
     * @return true if the input is a valid numeric format, false otherwise
     */
    private fun isValidNumericWithOptionalDecimalSeparator(
        input: String,
        symbols: DecimalFormatSymbols
    ): Boolean {
        if (input.isEmpty()) return false

        // Escape the decimal separator for use in regex
        // We need to escape all regex metacharacters: . ^ $ * + ? { } [ ] \ | ( )
        val decimalSeparator = Regex.escape(symbols.decimalSeparator.toString())

        // Pattern breakdown:
        // ^(?:\d+SEPARATOR?\d*|SEPARATOR\d+)$
        // - First alternative: digits, optional separator, optional digits (e.g., "123", "123.", "123.45")
        // - Second alternative: separator followed by digits (e.g., ".45")
        val regex = Regex("^(?:\\d+$decimalSeparator?\\d*|$decimalSeparator\\d+)$")

        return regex.matches(input)
    }
}

@Composable
@Preview
private fun Preview() =
    ZcashTheme {
        BlankSurface {
            Column(modifier = Modifier.fillMaxSize()) {
                ZashiNumberTextField(
                    state = NumberTextFieldState {},
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
