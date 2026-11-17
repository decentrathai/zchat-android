package co.electriccoin.zcash.ui.screen.ai

import co.electriccoin.zcash.ui.design.util.AmountReadingProbe
import co.electriccoin.zcash.ui.design.util.AmountReadingProbe.Field
import org.junit.Test
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * C8 (round 7 adversarial review of B8, finding 25) — the AI top-up custom USD field must read a comma as the
 * decimal mark; F1 (fix-all 2.13.0, train T1) — it reads amounts with the shared amount reader and bills whole
 * dollars rounded down.
 *
 * The field kept only digits and '.', so "12,50" typed on a Spanish or Serbian keyboard became "1250": the sheet
 * then showed "$1250 USD ≈ … ZEC" and "Pay with this wallet" opened a ZIP-321 request for 100 times the amount the
 * user meant. C8 read ',' and U+066B as the decimal point but also cut a pasted "1,234.5" to $1.23.
 *
 * Wanted (F1): a typed mark is the decimal point, a second mark or a third decimal is ignored, so the whole-dollar
 * part never grows from a keystroke; a paste is read with the shared reader, so "1,234.5" and "1.234,5" are $1234.50
 * (both have one reading); the tier is whole dollars rounded DOWN, so the sheet never bills more than was typed.
 */
class AiTopupCustomAmountTest {
    /** What the field does: each keystroke proposes `shown + key`, and the field holds what the rule returns. */
    private fun type(vararg keys: String): String =
        keys.fold(accept(AmountReadingProbe.emptyField(), "")) { current, key -> accept(current, current.shown + key) }.shown

    private fun paste(text: String): Field = accept(AmountReadingProbe.emptyField(), text)

    @Test
    fun typing_12_comma_50_is_twelve_dollars_fifty_not_1250() {
        val shown = type("1", "2", ",", "5", "0")

        assertEquals("12.50", shown, "1 2 , 5 0 on a Spanish keyboard must read as 12.50")
        assertEquals(12, customUsdTier(shown), "12.50 bills 12 whole dollars, rounded down")
        assertNotEquals(1250, customUsdTier(shown))
    }

    @Test
    fun the_arabic_decimal_separator_is_the_decimal_point_too() {
        val shown = type("1", "2", "٫", "5", "0")

        assertEquals("12.50", shown)
        assertEquals(12, customUsdTier(shown))
    }

    @Test
    fun a_pasted_comma_amount_is_read_the_same_way() {
        assertEquals("12.50", paste("12,50").settled)
        assertEquals("0.5", paste("0,5").settled)
        assertNull(customUsdTier(paste("0,5").settled), "under \$1 is still no top-up")
    }

    @Test
    fun a_number_with_two_marks_reads_its_one_reading() {
        // "1.234,5" (Spanish thousands) and "1,234.5" (English thousands) each read one way only: $1234.50.
        for (pasted in listOf("1.234,5", "1,234.5")) {
            assertEquals("1234.5", paste(pasted).settled, "'$pasted'")
            assertEquals(1234, customUsdTier(paste(pasted).settled), "'$pasted'")
        }
        // Typed keystroke by keystroke, the third decimal and the second mark are ignored: the amount never grows.
        assertEquals("1.23", type("1", ".", "2", "3", "4", ",", "5"))
        assertEquals(1, customUsdTier(type("1", ".", "2", "3", "4", ",", "5")))
    }

    @Test
    fun at_most_two_decimals_are_kept() {
        assertEquals("12.50", type("1", "2", ".", "5", "0", "9"))
        assertEquals("12.5", type("1", "2", ",", "5", ","))
    }

    @Test
    fun a_decimal_mark_never_makes_the_whole_dollars_bigger() {
        // The bug in one line: a dropped mark lets every later digit join the whole-dollar part.
        for (whole in listOf("", "1", "12", "300")) {
            for (mark in listOf(".", ",", "٫")) {
                val shown = type(*(whole.map { it.toString() } + listOf(mark, "5", "0")).toTypedArray())
                assertEquals("$whole.50", shown, "'$whole' + '$mark' + 5 0")
            }
        }
    }

    @Test
    fun plain_amounts_are_unchanged() {
        assertEquals("12.50", type("1", "2", ".", "5", "0"))
        assertEquals(12, customUsdTier("12.50"))
        assertEquals("5", paste("5").settled)
        assertEquals(5, customUsdTier("5"))
        assertEquals(12, customUsdTier("12.49"))
        assertEquals(12, customUsdTier("12.99"), "rounded down, never up")
        assertNull(customUsdTier("0.99"))
        assertNull(customUsdTier(""))
        assertNull(customUsdTier("."))
        assertEquals("", paste("").settled)
        assertEquals("12", paste("\$12").settled, "a pasted dollar sign is dropped, as before")
        assertEquals(1_000_000, customUsdTier("99999999999"), "the upper clamp is unchanged")
    }

    @Test
    fun the_sheet_uses_the_tested_rule() {
        val source = AmountReadingProbe.source(SHEET)
        assertTrue(
            "acceptUsdAmountInputResult(state, raw, formatLocale, selectionBefore)" in source,
            "the custom USD field must call acceptUsdAmountInput"
        )
        assertTrue("customUsdTier(" in source, "the custom USD field must take its tier from customUsdTier")
        assertFalse("it.isDigit() || it == '.'" in source, "the old inline filter that drops the comma is back")
    }

    internal companion object {
        const val SHEET = "ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/ai/AiTopupSheet.kt"

        /** UsdAmountInput.kt's acceptUsdAmountInput(field, raw, locale), called by name (it did not exist before F1). */
        fun accept(
            field: Field,
            raw: String,
            locale: Locale = Locale.US
        ): Field {
            val owner = AmountReadingProbe.type("co.electriccoin.zcash.ui.screen.ai.UsdAmountInputKt")
            return AmountReadingProbe.field(
                AmountReadingProbe.call(AmountReadingProbe.method(owner, "acceptUsdAmountInput", 3), null, field.handle, raw, locale)!!
            )
        }
    }
}
