package co.electriccoin.zcash.ui.design.util

import co.electriccoin.zcash.ui.design.component.AmountUnit
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * QA2 m3 (money-path rule 5, the amount parsing path), the parts of the design that main Send and the chat fields do not
 * share with every other field:
 *
 * - ZC-1752: the payment request's ZEC/USD switch re-reads the amount ([AmountFieldState.reread]). After the switch the
 *   state is plain typed text: a refused dollar paste "12.345678" that fits ZEC is the amount 12.345678, and a mark
 *   typed inside it is not taken (it used to keep the paste origin, so the key read the text as a whole paste and
 *   "12.345.678" settled 12345678 ZEC, 1,000,000 times the intent).
 * - ZC-1739: a field that cannot ask (restore height, slippage, a field built with askOnAmbiguity = false) refuses an
 *   ambiguous shape itself, however many readings fit the precision. "12.345" in a dollar field used to settle 12345.
 *   Typed alone, such a field keeps the typing rule (a third dollar decimal is not taken).
 */
internal class AmountFieldRereadAndNoAskM3Test {
    private val en: Locale = Locale.US
    private val es: Locale = Locale.forLanguageTag("es-ES")
    private val locales = listOf(en, es)

    private fun each(block: (Locale) -> Unit) {
        for (locale in locales) {
            try {
                block(locale)
            } catch (e: AssertionError) {
                throw AssertionError("[$locale] ${e.message}", e)
            }
        }
    }

    private fun settled(
        expected: String,
        view: AmountView,
        what: String
    ) {
        assertEquals(
            expected,
            view.settled,
            "$what: settled \"${view.settled}\" (shown \"${view.shown}\", options ${view.options}, error ${view.error})"
        )
        assertNull(view.options, "$what: a question is open")
        assertNull(view.error, "$what: a refusal is open (${view.error})")
    }

    // ---- ZC-1752 ----

    @Test
    fun zc1752_a_dollar_paste_of_12_345678_switched_to_zec_is_that_amount_and_a_dot_typed_inside_is_not_taken() {
        each { locale ->
            val usd = ChatAmountEditor(locale, AmountUnit.USD)
            val zec = ChatAmountEditor(locale, AmountUnit.ZEC)
            val refused = usd.paste("12.345678")
            assertEquals(AmountInvalidReason.TooManyDecimals(2), refused.error, "precondition: dollars refuse six decimals")
            val switched = zec.reread(refused)
            settled("12.345678", switched, "USD paste 12.345678, switch to ZEC")
            with(zec) {
                val inside = switched.insertedAt(6, ".")
                settled("12.345678", inside, "then a dot typed after 12.345")
                assertEquals(switched.shown, inside.shown, "the dot must not change the text")
            }
        }
    }

    @Test
    fun zc1752_a_dollar_paste_with_the_arabic_mark_switched_to_zec_is_typed_text_afterwards() {
        each { locale ->
            val usd = ChatAmountEditor(locale, AmountUnit.USD)
            val zec = ChatAmountEditor(locale, AmountUnit.ZEC)
            val switched = zec.reread(usd.paste("1٫234"))
            settled("1.234", switched, "USD paste 1<arabic mark>234, switch to ZEC")
            with(zec) {
                settled("1.234", switched.typed(","), "then a comma typed (a second mark is not taken, it is not 1234)")
                settled("1.234", switched.typed("."), "then a dot typed")
                settled("1.2345", switched.typed("5"), "then a 5 typed")
            }
        }
    }

    @Test
    fun zc1752_a_zec_paste_with_a_dollar_sign_switched_to_dollars_is_read_in_dollars() {
        each { locale ->
            val zec = ChatAmountEditor(locale, AmountUnit.ZEC)
            val usd = ChatAmountEditor(locale, AmountUnit.USD)
            val refused = zec.paste("$5")
            assertEquals(AmountInvalidReason.OtherUnit(AmountUnit.USD), refused.error, "precondition: a dollar sign in a ZEC field")
            settled("5", usd.reread(refused), "ZEC paste \$5, switch to dollars")
        }
    }

    @Test
    fun zc1752_a_typed_amount_that_does_not_fit_dollars_is_refused_keeps_its_text_and_comes_back() {
        each { locale ->
            val zec = ChatAmountEditor(locale, AmountUnit.ZEC)
            val usd = ChatAmountEditor(locale, AmountUnit.USD)
            val typed = with(zec) { empty().typed("0.123") }
            val toUsd = usd.reread(typed)
            assertEquals(AmountInvalidReason.TooManyDecimals(2), toUsd.error)
            assertEquals("", toUsd.settled)
            assertEquals("0.123", toUsd.shown, "the refused text stays")
            settled("0.123", zec.reread(toUsd), "and back to ZEC")
        }
    }

    @Test
    fun zc1752_an_open_question_stays_a_question_after_the_switch_and_lists_what_fits() {
        each { locale ->
            val zec = ChatAmountEditor(locale, AmountUnit.ZEC)
            val usd = ChatAmountEditor(locale, AmountUnit.USD)
            val question = zec.paste("1,000")
            val sameInDollars = usd.reread(question)
            assertEquals(listOf("1", "1000"), sameInDollars.options, "1,000 fits dollars both ways")
            assertEquals("", sameInDollars.settled)
            val bigger = usd.reread(zec.paste("12,345"))
            assertEquals(listOf("12345"), bigger.options, "12,345 as 12.345 has three dollar decimals")
            assertEquals("", bigger.settled)
            assertEquals("12,345", bigger.shown, "the question is about the pasted text")
        }
    }

    // ---- ZC-1739 ----

    @Test
    fun zc1739_a_field_that_cannot_ask_keeps_the_typing_rule_for_a_pasted_12_then_dot_345() {
        each { locale ->
            with(FieldAmountEditor(locale, AmountUnit.USD, canAsk = false)) {
                val view = paste("12").typed(".345")
                settled("12.34", view, "no-ask USD, paste 12, type .345")
            }
        }
    }

    @Test
    fun zc1739_a_field_that_cannot_ask_refuses_a_pasted_12_345_in_dollars() {
        each { locale ->
            with(FieldAmountEditor(locale, AmountUnit.USD, canAsk = false)) {
                val view = paste("12.345")
                assertEquals(
                    "",
                    view.settled,
                    "a no-ask dollar field settled \"${view.settled}\" for a pasted 12.345 (12345 is 1000 times a cent figure)"
                )
                assertNull(view.options, "a field that cannot ask asked")
                assertEquals(AmountInvalidReason.Unreadable, view.error)
                assertEquals("12.345", view.shown, "the refused text stays on screen")
            }
        }
    }

    @Test
    fun zc1739_a_field_that_cannot_ask_refuses_a_shape_that_only_one_reading_fits() {
        each { locale ->
            with(FieldAmountEditor(locale, AmountUnit.USD, canAsk = false)) {
                for (text in listOf("1.234", "1,234", "999.999")) {
                    val view = paste(text)
                    assertEquals("", view.settled, "no-ask USD paste $text settled \"${view.settled}\"")
                    assertEquals(AmountInvalidReason.Unreadable, view.error, "no-ask USD paste $text")
                }
            }
        }
    }

    @Test
    fun guard_a_field_that_cannot_ask_refuses_two_readings_and_reads_a_grouped_amount() {
        each { locale ->
            with(FieldAmountEditor(locale, AmountUnit.ZEC, canAsk = false)) {
                assertEquals(AmountInvalidReason.Unreadable, paste("1,000").error)
                assertEquals(AmountInvalidReason.Unreadable, paste("1.500").error)
                settled("2500000", paste("2,500,000"), "no-ask ZEC paste 2,500,000")
                settled("1000.50", paste("1,000.50"), "no-ask ZEC paste 1,000.50")
            }
        }
    }

    @Test
    fun guard_a_field_that_cannot_ask_never_makes_a_question_from_a_key() {
        each { locale ->
            with(FieldAmountEditor(locale, AmountUnit.USD, canAsk = false)) {
                val view = paste("12").typed(".345").typed("6")
                assertNull(view.options)
                assertTrue(view.error == null, "error ${view.error}")
            }
        }
    }
}
