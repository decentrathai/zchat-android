package co.electriccoin.zcash.ui.design.component

import java.math.BigDecimal
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * R2-1 (a), plan step p2-6ccb00a01f88: what you pay equals what you typed.
 *
 * Before the fix, when a typed amount carried exactly one kind of mark and that mark was not the
 * locale's decimal separator, [ZashiNumberTextFieldParser.normalizeInput] stripped it as a grouping
 * separator. So "0.5" on a German phone became "05" = 5 ZEC (10x), "0.00001" became 1 ZEC
 * (100,000x), and "0,5" on a US phone became 5 ZEC.
 *
 * Rule after the fix: a lone mark is a grouping separator only when it reads as one (the part before
 * it is 1-3 digits not starting with 0, and each part after it is exactly 3 digits). Anything else
 * is the decimal point. A locale decimal separator that repeats, or a grouping that does not read as
 * grouping, is refused (the field shows its invalid-amount error) instead of being guessed.
 */
class ZashiNumberTextFieldParserTest {
    private val en = Locale.US
    private val de = Locale.GERMANY
    private val es = Locale.forLanguageTag("es-ES")

    private fun parse(
        input: String,
        locale: Locale
    ): BigDecimal? =
        ZashiNumberTextFieldParser.toBigDecimalOrNull(
            ZashiNumberTextFieldParser.normalizeInput(input, locale),
            locale
        )

    private fun assertAmount(
        expected: String,
        input: String,
        locale: Locale
    ) {
        val actual = parse(input, locale)
        assertNotNull(actual, "'$input' @ $locale must parse to $expected, got a refusal")
        assertEquals(
            0,
            BigDecimal(expected).compareTo(actual),
            "'$input' @ $locale must parse to $expected, got ${actual.toPlainString()}"
        )
    }

    // --- The misreads (RED on the parent) ---

    @Test
    fun `a dot decimal on a German phone is half a ZEC, not 5`() = assertAmount("0.5", "0.5", de)

    @Test
    fun `a dot dust amount on a German phone is 0_00001, not 1 ZEC`() = assertAmount("0.00001", "0.00001", de)

    @Test
    fun `a comma decimal on a US phone is half a ZEC, not 5`() = assertAmount("0.5", "0,5", en)

    @Test
    fun `a comma dust amount on a US phone is 0_00001, not 1 ZEC`() = assertAmount("0.00001", "0,00001", en)

    @Test
    fun `a dot decimal on a Spanish phone is half a ZEC, not 5`() = assertAmount("0.5", "0.5", es)

    @Test
    fun `1_5 typed with a dot on a German phone is 1_5, not 15`() = assertAmount("1.5", "1.5", de)

    @Test
    fun `a trailing dot typed on a German phone becomes the decimal comma`() {
        // The field feeds the normalized text back to the screen keystroke by keystroke; the mark
        // must stay visible as the decimal separator instead of vanishing.
        assertEquals("0,", ZashiNumberTextFieldParser.normalizeInput("0.", de))
    }

    @Test
    fun `a repeated US decimal point is refused, not read as 12_3`() = assertNull(parse("1.2.3", en))

    @Test
    fun `commas that are not thousands groups are refused on a US phone, not read as 123`() =
        assertNull(parse("1,2,3", en))

    // --- Controls that must stay ---

    @Test
    fun `a US thousands group stays 1234`() = assertAmount("1234", "1,234", en)

    @Test
    fun `a German thousands group stays 1234`() = assertAmount("1234", "1.234", de)

    @Test
    fun `a Spanish thousands group stays 1234`() = assertAmount("1234", "1.234", es)

    @Test
    fun `German grouping plus decimal stays 1234_5`() = assertAmount("1234.5", "1.234,5", de)

    @Test
    fun `US grouping plus decimal stays 1234_5`() = assertAmount("1234.5", "1,234.5", en)

    @Test
    fun `several German thousands groups stay 1234567`() = assertAmount("1234567", "1.234.567", de)

    @Test
    fun `the locale decimal separator stays the decimal point`() {
        assertAmount("0.5", "0,5", de)
        assertAmount("0.5", "0.5", en)
        assertAmount("1.234", "1,234", de)
        assertAmount("1.234", "1.234", en)
        assertAmount("0.00001", "0,00001", es)
    }

    @Test
    fun `plain integers and spaces stay`() {
        assertAmount("5", "5", en)
        assertAmount("1234", "1 234", de)
    }

    @Test
    fun `garbage and empty stay refused`() {
        assertNull(parse("", en))
        assertNull(parse("abc", de))
        assertNull(parse(".", en))
    }
}
