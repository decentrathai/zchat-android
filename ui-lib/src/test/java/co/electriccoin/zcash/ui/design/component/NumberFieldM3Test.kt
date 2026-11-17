package co.electriccoin.zcash.ui.design.component

import co.electriccoin.zcash.ui.design.component.NumberFieldProbe.ask
import co.electriccoin.zcash.ui.design.component.NumberFieldProbe.paste
import co.electriccoin.zcash.ui.design.component.NumberFieldProbe.plain
import co.electriccoin.zcash.ui.design.component.NumberFieldProbe.readError
import co.electriccoin.zcash.ui.design.component.NumberFieldProbe.text
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * QA2 m3 (money-path rule 5), the number fields (Swap, Pay, slippage, restore height) on the one amount machine:
 *
 * - ZC-1739: a number field that cannot ask refuses an ambiguous shape ("2,500", "428,143" in the restore height, "1.234"
 *   in the two-decimal slippage field) however many readings fit its precision. A grouped number that reads one way
 *   ("2,500,000") still reads.
 * - ZC-1746: a key on an open question empties the field and closes it.
 * - ZC-1752: after the field's precision grows ([NumberTextFieldInnerState.limitedTo], a token with more decimals was
 *   chosen), the text refused for its decimals is plain typed text: a mark typed inside it is not taken.
 */
class NumberFieldM3Test {
    private val en = Locale.US
    private val es = Locale.forLanguageTag("es-ES")

    private fun field(
        maxDecimals: Int,
        unit: String?,
        ask: Boolean = true
    ): NumberTextFieldState = NumberFieldProbe.field(NumberFieldProbe.newReading(maxDecimals, unit, ask))

    @Test
    fun zc1739_the_restore_height_refuses_2_500_and_428_143_and_reads_2_500_000() {
        val height = NumberFieldProbe.field(NumberFieldProbe.presetHandle("BLOCK_HEIGHT"))
        for (locale in listOf(en, es)) {
            for (text in listOf("2,500", "428,143", "3,428", "2.500")) {
                val pasted = paste(height, text, locale)
                assertNull(pasted.innerState.amount, "$locale: the restore height read $text as ${plain(pasted.innerState.amount)}")
                assertNull(ask(pasted.innerState), "$locale: the height field asked")
                assertEquals("Unreadable", readError(pasted.innerState), "$locale: $text")
            }
            assertEquals("2500000", plain(paste(height, "2,500,000", locale).innerState.amount), "$locale")
            assertEquals("3428143", plain(paste(height, "3,428,143", locale).innerState.amount), "$locale")
        }
    }

    @Test
    fun zc1739_the_slippage_field_refuses_1_234_instead_of_reading_1234_percent() {
        val slippage = NumberFieldProbe.field(NumberFieldProbe.presetHandle("SLIPPAGE_PERCENT"))
        for (locale in listOf(en, es)) {
            for (text in listOf("1.234", "1,234", "2,500")) {
                val pasted = paste(slippage, text, locale)
                assertNull(pasted.innerState.amount, "$locale: slippage read $text as ${plain(pasted.innerState.amount)}")
                assertEquals("Unreadable", readError(pasted.innerState), "$locale: $text")
            }
        }
    }

    @Test
    fun zc1746_a_backspace_on_an_open_question_empties_the_number_field() {
        val asked = paste(field(8, "ZEC"), "1,000", en)
        assertEquals(listOf("1", "1000"), ask(asked.innerState), "precondition")
        val backspaced = asked.copy(innerState = NumberFieldProbe.edit(asked, "1,00", en))
        assertNull(ask(backspaced.innerState), "the question is still open")
        assertNull(readError(backspaced.innerState))
        assertNull(backspaced.innerState.amount)
        assertEquals("", text(backspaced.innerState), "the field is empty")
    }

    @Test
    fun zc1752_after_the_precision_grows_a_mark_typed_inside_the_refused_text_is_not_taken() {
        val narrow = paste(field(2, null), "12.345678", en)
        assertEquals("TooManyDecimals(2)", readError(narrow.innerState), "precondition")
        val wide = field(8, null).copy(innerState = narrow.innerState.limitedTo(8))
        assertEquals("12.345678", plain(wide.innerState.amount), "the refused text is the amount once the precision allows it")
        assertNull(readError(wide.innerState))
        val inside = NumberFieldProbe.edit(wide, "12.345.678", en)
        assertEquals("12.345678", plain(inside.amount), "a dot typed inside the text was read as grouping")
        assertEquals("12.345678", text(inside))
    }
}
