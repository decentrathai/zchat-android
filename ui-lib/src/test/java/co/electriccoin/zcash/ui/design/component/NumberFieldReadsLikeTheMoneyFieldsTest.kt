package co.electriccoin.zcash.ui.design.component

import co.electriccoin.zcash.ui.design.component.NumberFieldProbe.ask
import co.electriccoin.zcash.ui.design.component.NumberFieldProbe.choose
import co.electriccoin.zcash.ui.design.component.NumberFieldProbe.paste
import co.electriccoin.zcash.ui.design.component.NumberFieldProbe.plain
import co.electriccoin.zcash.ui.design.component.NumberFieldProbe.readError
import co.electriccoin.zcash.ui.design.component.NumberFieldProbe.text
import co.electriccoin.zcash.ui.design.component.NumberFieldProbe.type
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * F12 (fix-all 2.13.0, train T3): Swap, Pay, the slippage field and the restore height use ZashiNumberTextField, which
 * at 657570d65 still reads text with its own rule (ZashiNumberTextFieldParser.normalizeInput, R2-1 a): a single mark
 * that is not the phone's decimal mark with three digits after it is grouping. So on a Spanish phone a pasted "1.500"
 * (an English 1.5 from an exchange) is 1500 in Pay and Swap with no question, while the money fields of F1 ask. The
 * field keeps no precision either, and the app writes formatted, grouped numbers into it ("1,234.56") that the F1
 * keystroke rule can't edit.
 *
 * After F12 the field reads through F1's AmountTextReader with a NumberFieldReading (precision, unit, ask or refuse):
 * a typed mark is the decimal point, a paste that reads two ways asks (smaller first) and holds no amount until a
 * reading is tapped, a paste that reads one way is read, anything else is refused with a reason, and every text the
 * app writes is plain canonical text that holds exactly the amount it shows.
 */
class NumberFieldReadsLikeTheMoneyFieldsTest {
    private val en = Locale.US
    private val es = Locale.forLanguageTag("es-ES")

    /** A field of [maxDecimals] and [unit] as a screen builds it. */
    private fun field(
        maxDecimals: Int,
        unit: String?,
        ask: Boolean = true
    ): NumberTextFieldState = NumberFieldProbe.field(NumberFieldProbe.newReading(maxDecimals, unit, ask))

    @Test
    fun a_spanish_paste_of_1_500_asks_and_holds_no_amount_until_a_reading_is_tapped() {
        val asked = paste(field(8, "ZEC"), "1.500", es)
        assertEquals(listOf("1.5", "1500"), ask(asked.innerState), "smaller reading first")
        assertNull(asked.innerState.amount, "a raw paste never reaches the amount that counts")
        assertTrue(asked.innerState.isError, "the field is not usable while the question is open")
        val chosen = choose(asked, "1.5")
        assertEquals("1.5", plain(chosen.amount))
        assertNull(ask(chosen))
        assertEquals("1.5", text(chosen))
    }

    @Test
    fun an_english_paste_of_1_000_asks_too() {
        assertEquals(listOf("1", "1000"), ask(paste(field(6, null), "1,000", en).innerState))
    }

    @Test
    fun editing_the_pasted_text_discards_the_paste_and_never_answers_the_question() {
        // H5 as F1 pinned it, then m2: the question stayed open for every key. m3 (owner decision W-137, ZC-1746): a key on
        // an open question discards the paste; the field is empty and nothing counts.
        val asked = paste(field(8, "ZEC"), "1,000", en)
        val backspaced = asked.copy(innerState = NumberFieldProbe.edit(asked, "1,00", en))
        assertNull(ask(backspaced.innerState), "the question must close")
        assertNull(backspaced.innerState.amount)
        val retyped = type(backspaced, en, "0")
        assertNull(ask(retyped.innerState), "the question must not come back")
        assertEquals("0", plain(retyped.innerState.amount), "the next key types afresh")
    }

    @Test
    fun a_paste_that_reads_one_way_is_read() {
        assertEquals("1000.5", plain(paste(field(8, "ZEC"), "1,000.50", en).innerState.amount))
        assertEquals("1000.5", plain(paste(field(8, "ZEC"), "1.000,50", es).innerState.amount))
        assertEquals("1234567", plain(paste(field(6, null), "1 234 567", es).innerState.amount))
    }

    @Test
    fun a_typed_mark_is_the_decimal_point_on_any_phone() {
        assertEquals("1.5", plain(type(field(8, "ZEC"), es, "1", ".", "5").innerState.amount))
        assertEquals("1.5", plain(type(field(8, "ZEC"), en, "1", ",", "5").innerState.amount))
        // Typed "1,000" on an English phone is 1.000 = 1 (H14), never 1000.
        assertEquals("1", plain(type(field(8, "ZEC"), en, "1", ",", "0", "0", "0").innerState.amount))
    }

    @Test
    fun the_field_refuses_a_keystroke_past_its_precision_and_a_paste_past_it_says_why() {
        val two = type(field(2, "USD"), en, "1", ".", "2", "5", "9")
        assertEquals("1.25", plain(two.innerState.amount), "the third decimal keystroke is refused")
        val pasted = paste(field(2, "USD"), "1.2591", en)
        assertNull(pasted.innerState.amount)
        assertEquals("TooManyDecimals(2)", readError(pasted.innerState))
    }

    @Test
    fun another_unit_s_sign_is_refused_in_a_field_with_no_currency() {
        val pasted = paste(field(6, null), "$5", en)
        assertNull(pasted.innerState.amount)
        assertEquals("OtherUnit(USD)", readError(pasted.innerState))
    }

    @Test
    fun a_field_that_can_not_ask_refuses_two_readings_instead_of_guessing() {
        // H7: slippage "1,500" is 1.5 or 1500; a field without a question refuses it.
        val pasted = paste(field(2, null, ask = false), "1,500", en)
        assertNull(pasted.innerState.amount)
        assertNull(ask(pasted.innerState))
        assertEquals("Unreadable", readError(pasted.innerState))
    }

    @Test
    fun an_amount_from_outside_is_written_as_plain_text_and_never_rounded() {
        val qr = NumberFieldProbe.fromAmount("1234.5", 8)
        assertEquals("1234.5", text(qr), "never grouped, never padded")
        assertEquals("1234.5", plain(qr.amount))
        val tooPrecise = NumberFieldProbe.fromAmount("0.123456789", 8)
        assertNull(tooPrecise.amount, "a QR amount past the field's precision is refused, not rounded up or down")
        assertEquals("TooManyDecimals(8)", readError(tooPrecise))
    }

    @Test
    fun a_computed_figure_is_written_at_the_field_s_precision_and_holds_what_it_shows() {
        val fiat = NumberFieldProbe.fromComputed("1234.5678", 2)
        assertEquals("1234.57", text(fiat))
        assertEquals("1234.57", plain(fiat.amount), "the field holds exactly the amount it shows")
        assertFalse(fiat.isError)
    }
}
