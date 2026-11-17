package co.electriccoin.zcash.ui.design.util

import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * QA2 m2 ZC-1727 (money-path rule 5): ZC-1714 made a whole paste that ends in a decimal mark keep the mark ("12." stays "12.",
 * so the next typed digit is a decimal). It changed four branches of the reader that had no test: the grouped read, the
 * Arabic decimal mark, the mixed-marks read and the guard that keeps a field with no decimals from holding a mark. The
 * Fable review of ZC-1714 traced all four by hand; these tests pin them (they pass at the ZC-1714 fix and must stay green).
 * Each paste is followed by one typed digit: with the mark kept it is a decimal (1234.5), dropped it would be 12345.
 */
class AmountReaderKeptTrailingMarkTest {
    private val en = Locale.US
    private val es = Locale.forLanguageTag("es-ES")
    private val zec = 8

    private fun paste(
        text: String,
        locale: Locale = en,
        maxDecimals: Int = zec
    ): AmountFieldState = AmountFieldState().edit(text, locale, maxDecimals, null, true)

    private fun AmountFieldState.typeDigit(
        digit: Char,
        locale: Locale = en,
        maxDecimals: Int = zec
    ): AmountFieldState = edit(shown + digit, locale, maxDecimals, null, true)

    private fun assertKeepsMark(
        pasted: AmountFieldState,
        expectedWithMark: String,
        afterDigit: String,
        locale: Locale = en
    ) {
        assertNull(pasted.ask)
        assertNull(pasted.error, "error ${pasted.error}")
        assertEquals(expectedWithMark, pasted.settled)
        assertEquals(expectedWithMark, pasted.shown)
        assertEquals(afterDigit, pasted.typeDigit('5', locale).settled)
    }

    // The grouped read (space-like grouping fixes the integer part; a mark after it is the decimal point).

    @Test
    fun a_space_grouped_paste_with_a_trailing_dot_keeps_the_mark() {
        assertKeepsMark(paste("1 234."), "1234.", "1234.5")
    }

    @Test
    fun a_space_grouped_paste_with_a_trailing_comma_keeps_the_mark_on_a_spanish_phone() {
        assertKeepsMark(paste("1 234,", es), "1234.", "1234.5", es)
    }

    @Test
    fun an_apostrophe_grouped_paste_with_a_trailing_dot_keeps_the_mark() {
        assertKeepsMark(paste("1'234."), "1234.", "1234.5")
    }

    @Test
    fun a_grouped_paste_with_no_mark_stays_a_whole_number() {
        val pasted = paste("1 234")
        assertEquals("1234", pasted.settled)
        assertEquals("12345", pasted.typeDigit('5').settled)
    }

    // The Arabic decimal mark.

    @Test
    fun an_arabic_mark_paste_with_nothing_after_it_keeps_the_mark() {
        assertKeepsMark(paste("12٫"), "12.", "12.5")
    }

    @Test
    fun an_arabic_mark_typed_after_a_pasted_integer_is_the_decimal_point() {
        val typed = paste("12").edit("12٫", en, zec, null, true)
        assertEquals("12.", typed.settled)
        assertEquals("12.5", typed.typeDigit('5').settled)
    }

    @Test
    fun an_arabic_mark_paste_with_arabic_indic_digits_keeps_the_mark() {
        assertKeepsMark(paste("١٢٫"), "12.", "12.5")
    }

    // Mixed marks (the last one is the decimal point).

    @Test
    fun a_mixed_marks_paste_with_a_trailing_dot_keeps_the_mark() {
        assertKeepsMark(paste("1,234."), "1234.", "1234.5")
    }

    @Test
    fun a_mixed_marks_paste_with_a_trailing_comma_keeps_the_mark_on_a_spanish_phone() {
        assertKeepsMark(paste("1.234,", es), "1234.", "1234.5", es)
    }

    @Test
    fun an_indian_grouped_paste_with_a_trailing_dot_keeps_the_mark() {
        assertKeepsMark(paste("12,34,567."), "1234567.", "1234567.5")
    }

    @Test
    fun a_mixed_marks_paste_with_digits_after_the_last_mark_is_unchanged() {
        assertEquals("1234.5", paste("1,234.5").settled)
        assertEquals("1234.5", paste("1.234,5", es).settled)
    }

    // The guard: a field with no decimals never holds a mark.

    @Test
    fun a_field_with_no_decimals_drops_the_trailing_mark_of_a_plain_paste() {
        val pasted = paste("12.", maxDecimals = 0)
        assertEquals("12", pasted.settled)
        assertEquals("12", pasted.shown)
        assertNull(pasted.error)
    }

    @Test
    fun a_field_with_no_decimals_drops_the_trailing_mark_of_a_grouped_paste() {
        assertEquals("1234", paste("1 234.", maxDecimals = 0).settled)
        assertEquals("1234", paste("1 234.", maxDecimals = 0).shown)
    }

    @Test
    fun a_field_with_no_decimals_drops_the_trailing_mark_of_an_arabic_mark_paste() {
        assertEquals("12", paste("12٫", maxDecimals = 0).settled)
        assertEquals("12", paste("12٫", maxDecimals = 0).shown)
    }

    @Test
    fun a_field_with_no_decimals_drops_the_trailing_mark_of_a_mixed_marks_paste() {
        assertEquals("1234", paste("1,234.", maxDecimals = 0).settled)
        assertEquals("1234", paste("1,234.", maxDecimals = 0).shown)
    }

    @Test
    fun a_field_with_no_decimals_is_not_made_ambiguous_by_the_dropped_mark() {
        val pasted = paste("12.", maxDecimals = 0)
        assertEquals("125", pasted.typeDigit('5', maxDecimals = 0).settled)
    }
}
