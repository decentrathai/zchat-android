package co.electriccoin.zcash.ui.design.util

import co.electriccoin.zcash.ui.design.component.AmountUnit
import java.math.BigDecimal
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * QA2 m4 (money-path rule 5, the amount parsing path): the items the m3 fix left open, ZC-1759 (high), ZC-1760 (high),
 * ZC-1761 (high, GPT), ZC-1762, ZC-1763 (in AmountReaderM4Test), ZC-1765 (the `choose` nit).
 *
 * Every field is run twice where the call shape matters: [withSelection] true is a field that hands the machine the
 * selection it had before the edit (the number fields today; every shipped amount field after the fix, see
 * AmountFieldsPassTheSelectionTest), false is a field that does not know it (the API stays for such callers). The editors in
 * AmountEditors.kt are the only call shape.
 *
 * - ZC-1759: a select-all paste whose text shares its start and end with the old text must not be dropped while the old
 *   amount stays valid. With the selection it is a paste (guards below). Without it a change that is not a pure one-character
 *   insertion and that typing cannot read is REFUSED out loud, as the base of m3 did.
 * - ZC-1760: a real key on an open question empties the field and then applies the key to the empty field (paste 1,000, type
 *   .50 is 0.50; a backspace leaves it empty). Only when the selection proves the key is at the cursor; without the
 *   selection (or with one that does not match the new text) the field is just emptied.
 * - ZC-1761: a dollar paste of 0.000 keeps the decimals the field allows (0.00); the decimal mark is never trimmed away, so a
 *   typed 5 never becomes the amount 5.
 * - ZC-1762: a refusal of a raw paste (a currency sign, letters) that becomes numeric by an edit is read with paste rules:
 *   "$1,000" minus the "$" asks 1 or 1000. Typing-only recovery stays for refusals of decimals.
 * - ZC-1765: `choose` accepts only a usable reading of the open question.
 */
internal abstract class AmountM4ItemsContract {
    protected val en: Locale = Locale.US
    protected val es: Locale = Locale.forLanguageTag("es-ES")

    protected abstract fun editor(
        locale: Locale,
        unit: AmountUnit,
        withSelection: Boolean
    ): AmountEditor

    private fun eachLocale(block: (Locale) -> Unit) {
        for (locale in listOf(en, es)) {
            try {
                block(locale)
            } catch (e: AssertionError) {
                throw AssertionError("[$locale] ${e.message}", e)
            }
        }
    }

    private fun eachShape(block: (Locale, Boolean) -> Unit) {
        for (withSelection in listOf(true, false)) {
            eachLocale { locale ->
                try {
                    block(locale, withSelection)
                } catch (e: AssertionError) {
                    throw AssertionError("[${if (withSelection) "with the selection" else "selection unknown"}] ${e.message}", e)
                }
            }
        }
    }

    private fun mark(locale: Locale): String = if (locale == es) "," else "."

    private fun describe(view: AmountView) =
        "shown \"${view.shown}\", settled \"${view.settled}\", options ${view.options}, error ${view.error}"

    /** The field settled this amount (compared as a number: ".50" and "0.50" are the same amount) and nothing is open. */
    private fun assertAmount(
        expected: String,
        view: AmountView,
        what: String
    ) {
        assertNull(view.options, "$what: a question is open (${describe(view)})")
        assertNull(view.error, "$what: a refusal is open (${describe(view)})")
        assertTrue(view.settled.any(Char::isDigit), "$what: nothing settled (${describe(view)})")
        assertEquals(0, BigDecimal(expected).compareTo(BigDecimal(view.settled)), "$what: expected $expected (${describe(view)})")
    }

    /** Nothing is open and no amount counts (an empty field, or only a decimal mark). */
    private fun assertNoAmount(
        view: AmountView,
        what: String
    ) {
        assertNull(view.options, "$what: a question is open (${describe(view)})")
        assertNull(view.error, "$what: a refusal is open (${describe(view)})")
        assertTrue(view.settled.none(Char::isDigit), "$what: an amount counts (${describe(view)})")
    }

    private fun assertEmpty(
        view: AmountView,
        what: String
    ) {
        assertEquals("", view.shown, "$what: the field is not empty (${describe(view)})")
        assertNoAmount(view, what)
    }

    private fun assertRefusedOutLoud(
        view: AmountView,
        what: String
    ) {
        assertTrue(view.error != null, "$what: nothing was refused out loud (${describe(view)})")
        assertEquals("", view.settled, "$what: an amount counts while the text is refused (${describe(view)})")
        assertNull(view.options, "$what: it asked (${describe(view)})")
    }

    // ---- ZC-1759: the selection is unknown: a replacement that typing cannot read is refused out loud ----

    @Test
    fun zc1759_without_the_selection_a_select_all_paste_that_shares_its_edges_is_refused_out_loud_in_zec() {
        eachLocale { locale ->
            with(editor(locale, AmountUnit.ZEC, withSelection = false)) {
                val typed = empty().typed("15234.5")
                assertAmount("15234.5", typed, "precondition: typed 15234.5")
                val view = edit(typed, "1,234.5", null)
                assertRefusedOutLoud(view, "15234.5, select all, paste 1,234.5 (selection unknown)")
                assertNotEquals(typed.settled, view.settled, "the old amount stays valid")
            }
        }
    }

    @Test
    fun zc1759_without_the_selection_a_shorter_select_all_paste_in_dollars_is_refused_out_loud() {
        eachLocale { locale ->
            with(editor(locale, AmountUnit.USD, withSelection = false)) {
                for ((old, paste) in listOf("1000000" to "1,000", "25000" to "2,000", "350000" to "1,500", "1234567" to "1,234")) {
                    val typed = empty().typed(old)
                    val view = edit(typed, paste, null)
                    assertRefusedOutLoud(view, "USD $old, select all, paste $paste (selection unknown)")
                }
            }
        }
    }

    @Test
    fun guard_zc1759_with_the_selection_a_select_all_paste_is_read_as_a_paste() {
        eachLocale { locale ->
            with(editor(locale, AmountUnit.ZEC, withSelection = true)) {
                assertAmount("1234.5", empty().typed("15234.5").allReplacedBy("1,234.5"), "15234.5, select all, paste 1,234.5")
            }
            with(editor(locale, AmountUnit.USD, withSelection = true)) {
                val million = empty().typed("1000000").allReplacedBy("1,000")
                assertEquals(listOf("1", "1000"), million.options, "USD 1000000, select all, paste 1,000")
                val quarter = empty().typed("25000").allReplacedBy("2,000")
                assertEquals(listOf("2", "2000"), quarter.options, "USD 25000, select all, paste 2,000")
            }
        }
    }

    @Test
    fun guard_zc1759_a_typed_digit_or_a_deletion_is_still_taken_when_the_selection_is_unknown() {
        eachLocale { locale ->
            with(editor(locale, AmountUnit.ZEC, withSelection = false)) {
                assertAmount("12.5", empty().typed("12.5"), "typed 12.5")
                assertAmount("12.5", empty().typed("12.55").backspaced(), "typed 12.55, backspace")
                assertAmount("7", edit(empty().typed("12.5"), "7", null), "12.5, select all, type 7 (nothing of the old text stays)")
            }
        }
    }

    @Test
    fun guard_zc1759_a_pure_one_character_insertion_that_cannot_be_taken_is_not_taken() {
        eachLocale { locale ->
            for (withSelection in listOf(true, false)) {
                with(editor(locale, AmountUnit.USD, withSelection)) {
                    val view = empty().typed("12.34").typed("5")
                    assertAmount("12.34", view, "USD typed 12.345 (withSelection $withSelection)")
                }
            }
        }
    }

    // ---- ZC-1760: a real key on an open question empties the field and is applied to the empty field ----

    @Test
    fun zc1760_with_the_selection_a_dot_and_50_typed_on_an_open_question_is_0_50() {
        eachLocale { locale ->
            for (unit in listOf(AmountUnit.ZEC, AmountUnit.USD)) {
                with(editor(locale, unit, withSelection = true)) {
                    val question = paste("1,000")
                    assertTrue(question.options != null, "precondition: 1,000 asks")
                    assertAmount("0.50", question.typed("${mark(locale)}50"), "$unit paste 1,000, type ${mark(locale)}50")
                }
            }
        }
    }

    @Test
    fun zc1760_with_the_selection_a_digit_typed_on_an_open_question_is_that_digit() {
        eachLocale { locale ->
            for (unit in listOf(AmountUnit.ZEC, AmountUnit.USD)) {
                with(editor(locale, unit, withSelection = true)) {
                    assertAmount("5", paste("1,000").typed("5"), "$unit paste 1,000, type 5")
                    assertAmount("0", paste("1.500").typed("0"), "$unit paste 1.500, type 0")
                    assertAmount("75", paste("1,000").typed("75"), "$unit paste 1,000, type 75")
                }
            }
        }
    }

    @Test
    fun guard_zc1760_with_the_selection_a_mark_alone_typed_on_an_open_question_counts_nothing() {
        eachLocale { locale ->
            with(editor(locale, AmountUnit.ZEC, withSelection = true)) {
                assertNoAmount(paste("1,000").typed(mark(locale)), "paste 1,000, type ${mark(locale)}")
                assertNoAmount(paste("1,000").typed(other(locale)), "paste 1,000, type ${other(locale)}")
            }
        }
    }

    @Test
    fun zc1760_with_the_selection_a_key_in_the_middle_of_an_open_question_is_applied_alone() {
        eachLocale { locale ->
            with(editor(locale, AmountUnit.ZEC, withSelection = true)) {
                assertAmount("5", paste("1,000").insertedAt(2, "5"), "paste 1,000, type 5 after the comma")
                assertAmount("5", paste("1,000").insertedAt(0, "5"), "paste 1,000, type 5 before the 1")
            }
        }
    }

    @Test
    fun guard_zc1760_a_letter_typed_on_an_open_question_empties_the_field_and_applies_nothing() {
        eachLocale { locale ->
            with(editor(locale, AmountUnit.ZEC, withSelection = true)) {
                assertEmpty(paste("1,000").insertedAt(5, "x"), "paste 1,000, type x")
            }
        }
    }

    @Test
    fun guard_zc1760_a_backspace_on_an_open_question_leaves_the_field_empty() {
        eachShape { locale, withSelection ->
            for (unit in listOf(AmountUnit.ZEC, AmountUnit.USD)) {
                with(editor(locale, unit, withSelection)) {
                    assertEmpty(paste("1,000").backspaced(), "$unit paste 1,000, backspace")
                }
            }
        }
    }

    @Test
    fun guard_zc1760_without_the_selection_a_key_on_an_open_question_just_empties_the_field() {
        eachLocale { locale ->
            with(editor(locale, AmountUnit.ZEC, withSelection = false)) {
                val question = paste("1,000")
                assertEmpty(question.typed("5"), "selection unknown: paste 1,000, type 5")
                assertEmpty(question.typed(mark(locale)), "selection unknown: paste 1,000, type ${mark(locale)}")
                assertEmpty(question.insertedAt(2, "5"), "selection unknown: paste 1,000, type 5 in the middle")
            }
        }
    }

    @Test
    fun guard_zc1760_a_selection_that_does_not_match_the_new_text_does_not_prove_a_key_so_the_field_is_just_emptied() {
        eachLocale { locale ->
            with(editor(locale, AmountUnit.ZEC, withSelection = true)) {
                val question = paste("1,000")
                // The field claims the caret was at the end, but the new text has the character at the front.
                assertEmpty(edit(question, "51,000", 5..5), "paste 1,000, a 5 at the front with a caret claimed at the end")
            }
        }
    }

    @Test
    fun guard_zc1760_a_paste_into_an_open_question_still_asks_about_the_new_paste() {
        eachShape { locale, withSelection ->
            with(editor(locale, AmountUnit.ZEC, withSelection)) {
                val view = edit(paste("1,000"), "2,500", if (withSelection) 0..5 else null)
                assertEquals(listOf("2.5", "2500"), view.options, "question 1,000, select all, paste 2,500")
            }
        }
    }

    // ---- ZC-1761: a dollar paste of 0.000 keeps the decimals the field allows ----

    @Test
    fun zc1761_a_dollar_paste_of_0_000_is_0_00_and_a_typed_5_is_never_the_amount_5() {
        eachShape { locale, withSelection ->
            with(editor(locale, AmountUnit.USD, withSelection)) {
                val pasted = paste("0${mark(locale)}000")
                assertEquals("0.00", pasted.settled, "USD paste 0${mark(locale)}000 (${describe(pasted)})")
                assertNull(pasted.error)
                val after = pasted.typed("5")
                assertEquals("0.00", after.settled, "USD paste 0${mark(locale)}000, type 5: not taken (${describe(after)})")
                assertNull(after.error)
            }
        }
    }

    @Test
    fun zc1761_a_dollar_paste_of_0_0000_and_a_zec_paste_of_nine_decimal_zeros_keep_the_allowed_decimals() {
        eachShape { locale, withSelection ->
            with(editor(locale, AmountUnit.USD, withSelection)) {
                assertEquals("0.00", paste("0${mark(locale)}0000").settled, "USD paste 0${mark(locale)}0000")
            }
            with(editor(locale, AmountUnit.ZEC, withSelection)) {
                val pasted = paste("0${mark(locale)}000000000")
                assertEquals("0.00000000", pasted.settled, "ZEC paste 0${mark(locale)}000000000 (${describe(pasted)})")
                assertEquals("0.00000000", pasted.typed("5").settled, "ZEC paste 0.000000000, type 5")
            }
        }
    }

    @Test
    fun guard_zc1761_a_trimmed_dollar_paste_of_0_0005_ends_at_0_00_and_a_typed_5_is_not_taken() {
        eachShape { locale, withSelection ->
            with(editor(locale, AmountUnit.USD, withSelection)) {
                val refused = paste("0${mark(locale)}0005")
                assertTrue(refused.error is AmountInvalidReason.TooManyDecimals, "precondition: 0.0005 (${describe(refused)})")
                val trimmed = refused.backspaced(2)
                assertEquals("0.00", trimmed.settled, "USD paste 0.0005, two backspaces (${describe(trimmed)})")
                assertEquals("0.00", trimmed.typed("5").settled, "USD paste 0.0005, two backspaces, 5")
            }
        }
    }

    // ---- ZC-1762: a refusal of a raw paste that becomes numeric by an edit is read with paste rules ----

    @Test
    fun zc1762_a_zec_paste_of_dollar_1_000_minus_the_dollar_sign_asks_1_or_1000() {
        eachShape { locale, withSelection ->
            with(editor(locale, AmountUnit.ZEC, withSelection)) {
                val refused = paste("$1,000")
                assertEquals(AmountInvalidReason.OtherUnit(AmountUnit.USD), refused.error, "precondition (${describe(refused)})")
                val view = edit(refused, "1,000", if (withSelection) 0..1 else null)
                assertEquals(listOf("1", "1000"), view.options, "ZEC \$1,000 minus the \$ (${describe(view)})")
                assertEquals("", view.settled, "the amount counts before a tap (${describe(view)})")
            }
        }
    }

    @Test
    fun zc1762_a_spanish_style_dollar_1_500_minus_the_sign_asks_1_5_or_1500() {
        eachShape { locale, withSelection ->
            with(editor(locale, AmountUnit.ZEC, withSelection)) {
                val refused = paste("$1.500")
                assertTrue(refused.error is AmountInvalidReason.OtherUnit, "precondition (${describe(refused)})")
                val view = edit(refused, "1.500", if (withSelection) 0..1 else null)
                assertEquals(listOf("1.5", "1500"), view.options, "ZEC \$1.500 minus the \$ (${describe(view)})")
            }
        }
    }

    @Test
    fun zc1762_a_dollar_paste_of_zec_1_000_minus_the_sign_asks() {
        eachShape { locale, withSelection ->
            with(editor(locale, AmountUnit.USD, withSelection)) {
                val refused = paste("ZEC 1,000")
                assertEquals(AmountInvalidReason.OtherUnit(AmountUnit.ZEC), refused.error, "precondition (${describe(refused)})")
                val view = edit(refused, "1,000", if (withSelection) 0..4 else null)
                assertEquals(listOf("1", "1000"), view.options, "USD ZEC 1,000 minus the sign (${describe(view)})")
            }
        }
    }

    @Test
    fun zc1762_a_refused_paste_with_a_word_that_is_trimmed_to_1_000_asks() {
        eachShape { locale, withSelection ->
            with(editor(locale, AmountUnit.ZEC, withSelection)) {
                val refused = paste("1,000 zat")
                assertTrue(refused.error != null, "precondition: letters are refused (${describe(refused)})")
                val view = refused.backspaced(4)
                assertEquals(listOf("1", "1000"), view.options, "ZEC 1,000 zat minus the word (${describe(view)})")
            }
        }
    }

    @Test
    fun guard_zc1762_a_refused_paste_that_becomes_one_plain_number_settles_it() {
        eachShape { locale, withSelection ->
            with(editor(locale, AmountUnit.ZEC, withSelection)) {
                assertAmount("5", paste("5 BTC").backspaced(4), "ZEC 5 BTC minus the word")
                assertAmount("12.5", paste("$12.5").let { edit(it, "12.5", if (withSelection) 0..1 else null) }, "ZEC \$12.5 minus the \$")
            }
        }
    }

    @Test
    fun guard_zc1762_a_dollar_paste_refused_for_its_decimals_is_still_trimmed_by_typing() {
        eachShape { locale, withSelection ->
            with(editor(locale, AmountUnit.USD, withSelection)) {
                val refused = paste("12${mark(locale)}3456")
                assertEquals(AmountInvalidReason.TooManyDecimals(2), refused.error)
                val once = refused.backspaced()
                assertEquals(AmountInvalidReason.TooManyDecimals(2), once.error, "12.345 stays refused (${describe(once)})")
                assertAmount("12.34", once.backspaced(), "USD paste 12.3456, two backspaces")
            }
        }
    }

    // ---- ZC-1765: choose takes only a usable reading of the open question ----

    @Test
    fun zc1765_choose_never_settles_a_reading_that_does_not_fit_the_field() {
        eachLocale { locale ->
            with(editor(locale, AmountUnit.USD, withSelection = true)) {
                val question = paste("12.345")
                assertEquals(listOf("12345"), question.options, "precondition: only 12345 fits cents")
                val view = choose(question, "12.345")
                assertEquals("", view.settled, "choose(12.345) settled \"${view.settled}\" (a rounded 12.34?)")
                assertEquals(listOf("12345"), view.options, "the question stays open (${describe(view)})")
            }
        }
    }

    @Test
    fun zc1765_choose_never_settles_a_value_that_is_not_a_reading_of_the_question() {
        eachLocale { locale ->
            with(editor(locale, AmountUnit.ZEC, withSelection = true)) {
                val question = paste("1,000")
                val view = choose(question, "7")
                assertEquals("", view.settled, "choose(7) on the question 1,000 settled \"${view.settled}\"")
                assertEquals(listOf("1", "1000"), view.options, "the question stays open (${describe(view)})")
            }
        }
    }

    @Test
    fun guard_zc1765_choose_settles_a_usable_reading() {
        eachLocale { locale ->
            with(editor(locale, AmountUnit.USD, withSelection = true)) {
                assertAmount("12345", choose(paste("12.345"), "12345"), "tap 12345")
            }
            with(editor(locale, AmountUnit.ZEC, withSelection = true)) {
                assertAmount("1", choose(paste("1,000"), "1"), "tap 1")
                assertAmount("1000", choose(paste("1,000"), "1000"), "tap 1000")
            }
        }
    }

    private fun other(locale: Locale): String = if (locale == es) "." else ","
}

internal class ChatAmountM4ItemsTest : AmountM4ItemsContract() {
    override fun editor(
        locale: Locale,
        unit: AmountUnit,
        withSelection: Boolean
    ): AmountEditor = ChatAmountEditor(locale, unit, withSelection)
}

internal class FieldAmountM4ItemsTest : AmountM4ItemsContract() {
    override fun editor(
        locale: Locale,
        unit: AmountUnit,
        withSelection: Boolean
    ): AmountEditor = FieldAmountEditor(locale, unit, canAsk = true, withSelection = withSelection)
}

internal class SendAmountM4ItemsTest : AmountM4ItemsContract() {
    override fun editor(
        locale: Locale,
        unit: AmountUnit,
        withSelection: Boolean
    ): AmountEditor = SendAmountEditor(locale, unit, withSelection)
}
