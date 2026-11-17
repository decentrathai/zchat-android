package co.electriccoin.zcash.ui.design.util

import co.electriccoin.zcash.ui.design.component.AmountUnit
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * QA2 m3 (money-path rule 5, the amount parsing path): the register items ZC-1737, ZC-1738, ZC-1740, ZC-1745, ZC-1746,
 * ZC-1750, ZC-1751 and ZC-1753, the open items of ZC-1724 and ZC-1725 that the m2 fix pinned the wrong way round, and the
 * behaviours the m3 design keeps (ZC-1714, ZC-1728, the ask on "1,000" and "1.500"). Every figure below is a check of the
 * designer's Python model (items.py: 69 checks per locale), replayed on the real fields.
 *
 * ONE RULE (m3 design section 2): the rule set is chosen by the event, never by history. A key is read with typing rules
 * (digits, at most one mark, any mark is the decimal point, decimals within the precision), a paste with paste rules
 * (grouping, "1,000" and "1.500" ask). There is no "paste origin" that makes later keys read as a paste.
 * - A key that cannot be taken is not taken: the text does not change (a third dollar decimal, a second mark).
 * - A key typed on an open question discards the paste: the field is empty, nothing settles.
 * - A key typed on a refused paste reads the text on screen as typing reads it, so a refused paste can be trimmed.
 * - A text that is not the field's own cannot settle a value above any reading of it.
 *
 * The contract runs on the chat fields (ChatAmountM3ItemsTest) and on main Send's ZEC and USD fields
 * (SendAmountM3ItemsTest), both in English and Spanish. The editors in AmountEditors.kt are the only call shape.
 */
internal abstract class AmountM3ItemsContract {
    protected val en: Locale = Locale.US
    protected val es: Locale = Locale.forLanguageTag("es-ES")

    protected abstract fun editor(
        locale: Locale,
        unit: AmountUnit
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

    private fun mark(locale: Locale): String = if (locale == es) "," else "."

    private fun other(locale: Locale): String = if (locale == es) "." else ","

    private fun assertSettled(
        expected: String,
        view: AmountView,
        what: String
    ) {
        assertEquals(
            expected,
            view.settled,
            "$what: settled \"${view.settled}\" (shown \"${view.shown}\", options ${view.options}, error ${view.error})"
        )
        assertNull(view.options, "$what: a question is open (shown \"${view.shown}\")")
        assertNull(view.error, "$what: a refusal is open (shown \"${view.shown}\", error ${view.error})")
    }

    /**
     * QA2 m4 (ZC-1760): the key that closed a question is applied to the emptied field, so the amount is that key, compared as
     * a number (".5" and "0.5" are one amount), and nothing is open.
     */
    private fun assertNumber(
        expected: String,
        view: AmountView,
        what: String
    ) {
        assertNull(view.options, "$what: a question is open (shown \"${view.shown}\")")
        assertNull(view.error, "$what: a refusal is open (${view.error})")
        assertTrue(view.settled.any(Char::isDigit), "$what: nothing settled (shown \"${view.shown}\")")
        assertEquals(
            0,
            java.math.BigDecimal(expected).compareTo(java.math.BigDecimal(view.settled)),
            "$what: expected $expected, settled \"${view.settled}\" (shown \"${view.shown}\")"
        )
    }

    /** QA2 m4 (ZC-1760): a lone decimal mark typed on a question counts for nothing; nothing is open. */
    private fun assertNoAmountOpen(
        view: AmountView,
        what: String
    ) {
        assertNull(view.options, "$what: the question is still open (options ${view.options})")
        assertNull(view.error, "$what: a refusal is open (${view.error})")
        assertTrue(view.settled.none(Char::isDigit), "$what: an amount settled (\"${view.settled}\")")
    }

    private fun assertClosedAndEmpty(
        view: AmountView,
        what: String
    ) {
        assertEquals(
            "",
            view.shown,
            "$what: the field is not empty (shown \"${view.shown}\", options ${view.options}, error ${view.error})"
        )
        assertEquals("", view.settled, "$what: an amount settled (\"${view.settled}\")")
        assertNull(view.options, "$what: the question is still open (options ${view.options})")
        assertNull(view.error, "$what: a refusal is open (${view.error})")
    }

    private fun assertRefused(
        view: AmountView,
        what: String,
        reason: AmountInvalidReason? = null
    ) {
        assertEquals("", view.settled, "$what: an amount settled (\"${view.settled}\", shown \"${view.shown}\")")
        assertNull(view.options, "$what: it asked (options ${view.options}, shown \"${view.shown}\")")
        assertTrue(view.error != null, "$what: nothing was refused (shown \"${view.shown}\")")
        if (reason != null) assertEquals(reason, view.error, "$what: wrong reason")
    }

    private fun assertAsks(
        expected: List<String>,
        view: AmountView,
        what: String
    ) {
        assertEquals("", view.settled, "$what: an amount settled before a tap (\"${view.settled}\")")
        assertEquals(expected, view.options, "$what: wrong readings (shown \"${view.shown}\", error ${view.error})")
    }

    // ---- ZC-1737: trimming a paste must never move the decimal point ----

    @Test
    fun zc1737_dollars_a_pasted_0_5_two_backspaces_then_dot_0005_is_0_00_never_5() {
        eachLocale { locale ->
            with(editor(locale, AmountUnit.USD)) {
                val view = paste("0.5").backspaced(2).typed(".0005")
                assertSettled("0.00", view, "USD paste 0.5, BS BS, .0005")
            }
        }
    }

    @Test
    fun zc1737_zec_a_pasted_0_5_two_backspaces_then_dot_and_nine_zeros_and_5_is_eight_zeros_never_5() {
        eachLocale { locale ->
            with(editor(locale, AmountUnit.ZEC)) {
                val view = paste("0.5").backspaced(2).typed(".0000000005")
                assertSettled("0.00000000", view, "ZEC paste 0.5, BS BS, .0000000005")
            }
        }
    }

    // ---- ZC-1738: a second mark after trimming a paste is not taken ----

    @Test
    fun zc1738_a_pasted_1_5_two_backspaces_then_dot_234_comma_is_1_234_the_comma_is_not_taken() {
        eachLocale { locale ->
            with(editor(locale, AmountUnit.ZEC)) {
                val view = paste("1.5").backspaced(2).typed(".234,")
                assertSettled("1.234", view, "ZEC paste 1.5, BS BS, .234,")
            }
        }
    }

    // ---- ZC-1740: typing alone keeps the rule: a third dollar decimal and a second mark are not taken ----

    @Test
    fun zc1740_typed_12_345_in_dollars_keeps_12_34() {
        eachLocale { locale ->
            with(editor(locale, AmountUnit.USD)) {
                assertSettled("12.34", empty().typed("12.345"), "typed 12.345 (USD)")
            }
        }
    }

    @Test
    fun zc1740_typed_1_2_comma_3_keeps_1_23() {
        eachLocale { locale ->
            with(editor(locale, AmountUnit.ZEC)) {
                assertSettled("1.23", empty().typed("1.2,3"), "typed 1.2,3")
            }
        }
    }

    // ---- ZC-1745: a key never makes a question; a pasted piece with marks into a typed amount is refused ----

    @Test
    fun zc1745_a_typed_5_then_a_pasted_comma_000_is_refused_and_the_next_key_reads_typing() {
        eachLocale { locale ->
            with(editor(locale, AmountUnit.ZEC)) {
                val refused = paste("5").pastedAtEnd(",000")
                assertRefused(refused, "type 5, paste ,000", AmountInvalidReason.PasteIntoAmount)
                assertEquals("5,000", refused.shown, "the refused text stays on screen")
                assertSettled("5.0005", refused.typed("5"), "type 5, paste ,000, then 5 (typing reads the text on screen)")
            }
        }
    }

    @Test
    fun zc1745_a_second_pasted_comma_000_stays_refused() {
        eachLocale { locale ->
            with(editor(locale, AmountUnit.ZEC)) {
                val refused = paste("5").pastedAtEnd(",000")
                assertRefused(refused.pastedAtEnd(",000"), "type 5, paste ,000 twice")
            }
        }
    }

    // ---- kept: a paste that reads two ways asks, smaller reading first, and nothing settles before a tap ----

    @Test
    fun guard_a_pasted_1_000_asks_1_or_1000() {
        eachLocale { locale ->
            with(editor(locale, AmountUnit.ZEC)) {
                val asked = paste("1,000")
                assertAsks(listOf("1", "1000"), asked, "paste 1,000")
                assertEquals("1,000", asked.shown)
            }
        }
    }

    @Test
    fun guard_a_pasted_1_500_asks_1_5_or_1500_in_both_languages() {
        eachLocale { locale ->
            with(editor(locale, AmountUnit.ZEC)) {
                assertAsks(listOf("1.5", "1500"), paste("1.500"), "paste 1.500")
            }
        }
    }

    @Test
    fun guard_a_dollar_paste_of_12_345_asks_with_the_one_reading_that_fits() {
        eachLocale { locale ->
            with(editor(locale, AmountUnit.USD)) {
                val asked = paste("12.345")
                assertAsks(listOf("12345"), asked, "USD paste 12.345")
                assertEquals("12.345", asked.shown, "the question is about the text on screen")
            }
        }
    }

    @Test
    fun guard_a_tapped_reading_settles_and_typing_goes_on_from_it() {
        eachLocale { locale ->
            with(editor(locale, AmountUnit.ZEC)) {
                val big = choose(paste("1,000"), "1000")
                assertSettled("1000", big, "tap 1000")
                assertSettled("1000.5", big.typed(".5"), "tap 1000, type .5")
                val small = choose(paste("1,000"), "1")
                assertSettled("1", small, "tap 1")
                assertSettled("1.5", small.typed(".5"), "tap 1, type .5")
            }
        }
    }

    // ---- ZC-1746, ZC-1724, ZC-1725: a key on an open question discards the paste (the field is empty) ----

    @Test
    fun zc1746_a_backspace_on_an_open_question_empties_the_field_and_closes_it() {
        eachLocale { locale ->
            with(editor(locale, AmountUnit.ZEC)) {
                assertClosedAndEmpty(paste("1,000").backspaced(), "paste 1,000, BS")
            }
        }
    }

    @Test
    fun zc1746_a_backspace_on_a_dollar_question_empties_the_field_and_closes_it() {
        eachLocale { locale ->
            with(editor(locale, AmountUnit.USD)) {
                assertClosedAndEmpty(paste("12.345").backspaced(), "USD paste 12.345, BS")
            }
        }
    }

    @Test
    fun zc1724_a_typed_mark_on_an_open_question_never_takes_the_larger_reading() {
        eachLocale { locale ->
            with(editor(locale, AmountUnit.ZEC)) {
                assertNoAmountOpen(paste("1,000").typed("."), "paste 1,000, type .")
                assertNoAmountOpen(paste("1,000").typed(","), "paste 1,000, type ,")
            }
        }
    }

    @Test
    fun zc1724_a_spanish_style_paste_of_1_000_then_the_decimal_comma_counts_no_amount() {
        eachLocale { locale ->
            with(editor(locale, AmountUnit.ZEC)) {
                assertNoAmountOpen(paste("1.000").typed(","), "paste 1.000, type ,")
            }
        }
    }

    @Test
    fun zc1724_in_dollars_a_typed_mark_on_an_open_question_counts_no_amount() {
        eachLocale { locale ->
            with(editor(locale, AmountUnit.USD)) {
                assertNoAmountOpen(paste("1,000").typed("."), "USD paste 1,000, type .")
            }
        }
    }

    @Test
    fun zc1725_a_typed_digit_on_an_open_question_never_answers_it_the_digit_starts_the_new_amount() {
        eachLocale { locale ->
            with(editor(locale, AmountUnit.ZEC)) {
                assertNumber("5", paste("1,000").typed("5"), "paste 1,000, type 5")
                assertNumber("0", paste("1.500").typed("0"), "paste 1.500, type 0")
            }
        }
    }

    @Test
    fun zc1725_the_key_that_closes_a_question_is_applied_to_the_empty_field_and_the_next_keys_follow() {
        eachLocale { locale ->
            with(editor(locale, AmountUnit.ZEC)) {
                assertNumber("0.5", paste("1,000").typed(".5"), "paste 1,000, type .5")
                assertSettled("5", paste("1,000").backspaced(2).typed("5"), "paste 1,000, BS BS, 5")
            }
        }
    }

    @Test
    fun zc1725_a_second_real_paste_over_an_open_question_asks_about_the_new_paste() {
        eachLocale { locale ->
            with(editor(locale, AmountUnit.ZEC)) {
                assertAsks(listOf("2.5", "2500"), edit(paste("1,000"), "2,500", null), "question 1,000, then a paste of 2,500")
            }
        }
    }

    @Test
    fun zc1725_a_pasted_piece_with_marks_into_an_open_question_is_refused_never_settled() {
        eachLocale { locale ->
            with(editor(locale, AmountUnit.ZEC)) {
                val question = paste("1,000")
                val view = question.pastedAtEnd(".50")
                assertRefused(view, "question 1,000, then .50 pasted at the end", AmountInvalidReason.PasteIntoAmount)
            }
        }
    }

    // ---- ZC-1750: a refused dollar paste can be trimmed to cents ----

    @Test
    fun zc1750_a_dollar_paste_of_12_3456_is_refused_and_two_backspaces_trim_it_to_12_34() {
        eachLocale { locale ->
            with(editor(locale, AmountUnit.USD)) {
                val text = "12${mark(locale)}3456"
                val pasted = paste(text)
                assertRefused(pasted, "USD paste $text", AmountInvalidReason.TooManyDecimals(2))
                val once = pasted.backspaced()
                assertRefused(once, "USD paste $text, BS", AmountInvalidReason.TooManyDecimals(2))
                assertEquals("12${mark(locale)}345", once.shown, "the first backspace trims the refused text")
                assertSettled("12.34", once.backspaced(), "USD paste $text, BS BS")
            }
        }
    }

    @Test
    fun zc1750_a_trimmed_dollar_paste_takes_no_third_decimal_and_never_becomes_a_12345_question() {
        eachLocale { locale ->
            with(editor(locale, AmountUnit.USD)) {
                val view = paste("12${mark(locale)}3456").backspaced(2).typed("7")
                assertSettled("12.34", view, "USD paste 12.3456, BS BS, 7 (a third decimal is not taken)")
            }
        }
    }

    @Test
    fun zc1750_a_typed_12_then_a_pasted_dot_345_is_refused_and_one_backspace_trims_it_to_12_34() {
        eachLocale { locale ->
            with(editor(locale, AmountUnit.USD)) {
                val refused = paste("12").pastedAtEnd(".345")
                assertRefused(refused, "USD type 12, paste .345", AmountInvalidReason.PasteIntoAmount)
                assertSettled("12.34", refused.backspaced(), "USD type 12, paste .345, BS")
            }
        }
    }

    // ---- ZC-1751: a typed mark inside a refused paste never reads the text as a whole paste ----

    @Test
    fun zc1751_a_refused_1234_567_with_a_dot_typed_after_the_1_stays_refused() {
        eachLocale { locale ->
            with(editor(locale, AmountUnit.USD)) {
                val refused = paste("1234.567")
                assertRefused(refused, "USD paste 1234.567", AmountInvalidReason.TooManyDecimals(2))
                assertRefused(refused.insertedAt(1, "."), "USD paste 1234.567, type . after the 1", AmountInvalidReason.Unreadable)
            }
        }
    }

    @Test
    fun zc1751_a_refused_12_3456_with_a_comma_typed_before_the_last_digit_stays_refused() {
        eachLocale { locale ->
            with(editor(locale, AmountUnit.USD)) {
                val refused = paste("12.3456")
                assertRefused(refused.insertedAt(6, ","), "USD paste 12.3456, type , before the 6")
            }
        }
    }

    // ---- ZC-1714 and ZC-1728, kept: typing goes on from a settled paste as typing reads it ----

    @Test
    fun guard_zc1714_a_pasted_12_then_a_typed_mark_and_5_is_12_5_with_either_mark() {
        eachLocale { locale ->
            with(editor(locale, AmountUnit.ZEC)) {
                assertSettled("12.5", paste("12").typed(".5"), "paste 12, type .5")
                assertSettled("12.5", paste("12").typed(",5"), "paste 12, type ,5")
            }
        }
    }

    @Test
    fun guard_a_pasted_12_then_typing_reaches_eight_zec_decimals() {
        eachLocale { locale ->
            with(editor(locale, AmountUnit.ZEC)) {
                assertSettled("12.12345678", paste("12").typed(".12345678"), "paste 12, type .12345678")
            }
        }
    }

    @Test
    fun zc1740_a_ninth_zec_decimal_is_not_taken_and_not_an_error() {
        eachLocale { locale ->
            with(editor(locale, AmountUnit.ZEC)) {
                assertSettled("12.12345678", paste("12").typed(".123456789"), "paste 12, type .123456789 (the ninth is not taken)")
                assertSettled("12.12345678", empty().typed("12.123456789"), "typed 12.123456789")
            }
        }
    }

    @Test
    fun guard_a_pasted_0_5_a_backspace_and_a_7_is_0_7() {
        eachLocale { locale ->
            with(editor(locale, AmountUnit.ZEC)) {
                assertSettled("0.7", paste("0.5").backspaced().typed("7"), "paste 0.5, BS, 7")
                assertSettled("1.5", paste("100").backspaced(2).typed(".5"), "paste 100, BS BS, .5")
            }
        }
    }

    @Test
    fun zc1728_a_second_typed_mark_after_a_pasted_12_is_not_taken_and_not_an_error() {
        eachLocale { locale ->
            with(editor(locale, AmountUnit.ZEC)) {
                assertSettled("12.", paste("12").typed(".."), "paste 12, type ..")
                assertSettled("12.5", paste("12").typed("..5"), "paste 12, type ..5")
            }
        }
    }

    @Test
    fun guard_a_pasted_grouped_amount_reads_at_once() {
        eachLocale { locale ->
            with(editor(locale, AmountUnit.ZEC)) {
                assertSettled("1234.5", paste("1,234.5"), "paste 1,234.5")
                assertSettled("1000.50", paste("1,000.50"), "paste 1,000.50")
            }
        }
    }

    @Test
    fun zc1740_a_mark_typed_after_a_grouped_paste_is_not_taken_and_not_grouping() {
        eachLocale { locale ->
            with(editor(locale, AmountUnit.ZEC)) {
                assertSettled("1234.5", paste("1,234.5").typed(","), "paste 1,234.5, type ,")
                assertSettled("1234.5", paste("1,234.5").typed("."), "paste 1,234.5, type .")
            }
        }
    }

    @Test
    fun guard_a_paste_with_more_than_eight_decimals_is_refused_with_its_text_on_screen() {
        eachLocale { locale ->
            with(editor(locale, AmountUnit.ZEC)) {
                val long = paste("0.123456789")
                assertRefused(long, "paste 0.123456789", AmountInvalidReason.TooManyDecimals(8))
                assertEquals("0.123456789", long.shown)
                assertRefused(paste("5 BTC"), "paste 5 BTC")
            }
        }
    }

    @Test
    fun zc1750_a_refused_paste_with_a_foreign_word_is_trimmed_by_backspaces_to_its_number() {
        eachLocale { locale ->
            with(editor(locale, AmountUnit.ZEC)) {
                val word = paste("5 BTC")
                assertRefused(word.backspaced(3), "paste 5 BTC, three backspaces (the text \"5 \" is still not a number)")
                assertSettled("5", word.backspaced(4), "paste 5 BTC, four backspaces")
            }
        }
    }

    @Test
    fun guard_select_all_and_one_typed_7_replaces_the_text() {
        eachLocale { locale ->
            with(editor(locale, AmountUnit.ZEC)) {
                assertSettled("7", paste("1,000").allReplacedBy("7"), "question 1,000, select all, type 7")
                assertSettled("7", paste("12.5").allReplacedBy("7"), "12.5, select all, type 7")
            }
        }
    }

    // ---- ZC-1753: a whole replace of an open question must not keep the old question ----

    @Test
    fun zc1753_without_the_selection_the_old_question_never_stays() {
        eachLocale { locale ->
            with(editor(locale, AmountUnit.ZEC)) {
                val question = paste("1,000")
                for (text in listOf("1,500", "2,000", "11,000", "1,250")) {
                    val view = edit(question, text, null)
                    assertEquals(
                        "",
                        view.settled,
                        "question 1,000, then $text over all of it, without the selection: settled \"${view.settled}\""
                    )
                    assertTrue(
                        !(view.options == question.options && view.shown == question.shown),
                        "question 1,000, then $text over all of it, without the selection: the old question is still open"
                    )
                }
            }
        }
    }

    @Test
    fun zc1753_without_the_selection_select_all_and_one_typed_1_leaves_no_question() {
        eachLocale { locale ->
            with(editor(locale, AmountUnit.ZEC)) {
                val view = edit(paste("1,000"), "1", null)
                assertNull(view.options, "the old question is still open (shown \"${view.shown}\")")
                assertNull(view.error)
                assertTrue(view.settled == "" || view.settled == "1", "settled \"${view.settled}\"")
            }
        }
    }

    @Test
    fun zc1753_with_the_selection_a_select_all_paste_over_a_question_asks_about_the_new_paste() {
        eachLocale { locale ->
            with(editor(locale, AmountUnit.ZEC)) {
                val question = paste("1,000")
                assertAsks(listOf("1.5", "1500"), question.allReplacedBy("1,500"), "question 1,000, select all, paste 1,500")
                assertAsks(listOf("2", "2000"), question.allReplacedBy("2,000"), "question 1,000, select all, paste 2,000")
                assertAsks(listOf("11", "11000"), question.allReplacedBy("11,000"), "question 1,000, select all, paste 11,000")
                assertAsks(listOf("1.25", "1250"), question.allReplacedBy("1,250"), "question 1,000, select all, paste 1,250")
            }
        }
    }

    @Test
    fun zc1753_with_the_selection_select_all_and_one_typed_1_replaces_the_text() {
        eachLocale { locale ->
            with(editor(locale, AmountUnit.ZEC)) {
                assertSettled("1", paste("1,000").allReplacedBy("1"), "question 1,000, select all, type 1")
            }
        }
    }

    @Test
    fun zc1753_with_the_selection_a_select_all_paste_is_a_paste_even_one_character_from_the_old_text() {
        eachLocale { locale ->
            with(editor(locale, AmountUnit.ZEC)) {
                assertAsks(listOf("1", "1000"), paste("1000").allReplacedBy("1,000"), "1000, select all, paste 1,000")
                assertSettled("1234.5", paste("15234.5").allReplacedBy("1,234.5"), "15234.5, select all, paste 1,234.5")
            }
        }
    }

    /**
     * QA2 m4 (ZC-1759): the test above is the call shape of a field that reports the selection. A field that does not know it
     * (the API stays) sees the same sequence as a text diff: one comma replaced the middle of the old text. A change that is
     * not a pure one-character insertion and that typing cannot read is refused out loud (as m2 and the base of m3 did), and
     * the old amount does not stay valid.
     */
    @Test
    fun zc1759_without_the_selection_a_select_all_paste_that_typing_cannot_read_is_refused_out_loud() {
        eachLocale { locale ->
            with(editor(locale, AmountUnit.ZEC)) {
                val typed = paste("15234.5")
                val view = edit(typed, "1,234.5", null)
                assertRefused(view, "15234.5, select all, paste 1,234.5 (selection unknown)")
                assertNotEquals(typed.settled, view.settled, "the old amount stays valid")
            }
        }
    }

    @Test
    fun zc1753_a_select_all_paste_equals_the_same_paste_into_an_empty_field() {
        eachLocale { locale ->
            with(editor(locale, AmountUnit.ZEC)) {
                val seeds = listOf(paste("1,000"), paste("1000"), paste("15234.5"), paste("12.5"), paste("1,500"))
                for (seed in seeds) {
                    for (text in listOf("1,000", "1,500", "1,234.5", "2,500", "12.345")) {
                        val replaced = seed.allReplacedBy(text)
                        val fresh = paste(text)
                        assertEquals(
                            fresh.copy(handle = Unit),
                            replaced.copy(handle = Unit),
                            "${seed.shown} select all, paste $text, differs from the same paste into an empty field"
                        )
                    }
                }
            }
        }
    }

    // ---- an empty paste or a deleted text is the empty field ----

    @Test
    fun guard_emptying_the_field_ends_a_question_and_typing_goes_on_fresh() {
        eachLocale { locale ->
            with(editor(locale, AmountUnit.ZEC)) {
                val cleared = edit(paste("1,000"), "", 0..5)
                assertClosedAndEmpty(cleared, "paste 1,000, select all, delete")
                assertSettled("1.000", cleared.typed("1${mark(locale)}000"), "then typing 1${mark(locale)}000")
                assertNotEquals("1000", cleared.typed("1${other(locale)}000").settled)
            }
        }
    }
}

internal class ChatAmountM3ItemsTest : AmountM3ItemsContract() {
    override fun editor(
        locale: Locale,
        unit: AmountUnit
    ): AmountEditor = ChatAmountEditor(locale, unit)
}

internal class SendAmountM3ItemsTest : AmountM3ItemsContract() {
    override fun editor(
        locale: Locale,
        unit: AmountUnit
    ): AmountEditor = SendAmountEditor(locale, unit)
}
