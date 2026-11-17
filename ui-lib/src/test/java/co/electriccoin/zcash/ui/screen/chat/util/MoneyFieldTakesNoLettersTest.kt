package co.electriccoin.zcash.ui.screen.chat.util

import co.electriccoin.zcash.ui.design.component.AmountUnit
import co.electriccoin.zcash.ui.design.util.AmountFieldState
import org.junit.Test
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * QA2 Q2-46 (money-path rule 5, the amount parsing path): the Request Payment amount field took letters ("0.000013request",
 * typed by adb as one multi-character insertion, which the field reads as a paste and keeps verbatim). The field is a money
 * field: whatever else arrives, only digits and one decimal separator may stay in it, and the amount that counts is the
 * digits that were there.
 */
class MoneyFieldTakesNoLettersTest {
    private val locale = Locale.US

    @Test
    fun guard_a_typed_letter_is_ignored() {
        var field = AmountFieldState.settled("0.00001")
        field = acceptAmountInput(field, "0.00001r", locale, AmountUnit.ZEC)
        assertEquals("0.00001", field.shown)
    }

    @Test
    fun a_multi_character_insertion_with_letters_keeps_only_the_digits_and_the_separator() {
        val field = acceptAmountInput(AmountFieldState(), "0.000013request", locale, AmountUnit.ZEC)
        assertTrue(Regex("""[0-9.,]*""").matches(field.shown), "the field still shows letters: \"${field.shown}\"")
        assertEquals("0.000013", field.settled, "the amount that counts must be the digits that were typed")
    }

    // QA2 U7 review follow-up, Normal 1 (money-path rule 5): a paste whose letters name an amount word ("zat", "zats",
    // "zatoshi", "sat", "sats") or sit inside the number ("1e5") is never read again with the letters dropped: "1000 zat"
    // must not become 1000 ZEC, "17000 zats" 17000 ZEC, "1e5" 15 ZEC. It is refused out loud (the error stays shown with the
    // paste) or kept visibly invalid; the amount that counts stays empty.
    private fun assertRefusedOutLoud(paste: String) {
        val field = acceptAmountInput(AmountFieldState(), paste, locale, AmountUnit.ZEC)
        assertEquals("", field.settled, "\"$paste\" became the amount \"${field.settled}\" ZEC with its letters dropped")
        assertTrue(field.isQuestionOpen, "\"$paste\" was dropped without an error or a question: shown \"${field.shown}\"")
    }

    @Test
    fun a_pasted_amount_in_zatoshi_words_is_refused_never_read_as_zec() {
        for (paste in listOf("1000 zat", "17000 zats", "1000zat", "1000 zatoshi", "1000 zatoshis", "5 sat", "5000 sats", "1000 ZAT")) {
            assertRefusedOutLoud(paste)
        }
    }

    @Test
    fun a_pasted_exponent_is_refused_never_read_as_digits_run_together() {
        assertRefusedOutLoud("1e5")
        assertRefusedOutLoud("1E5")
    }

    @Test
    fun letters_between_digits_do_not_end_up_in_the_amount() {
        // Letters inside the number are not a trailing word: "1abc.5" is not 1.5 ZEC. Refused, the letters stay visible.
        assertRefusedOutLoud("1abc.5")
        assertRefusedOutLoud("1a5")
    }

    @Test
    fun guard_a_pasted_zec_sign_and_a_plain_paste_still_read() {
        assertEquals("1.5", acceptAmountInput(AmountFieldState(), "1.5 ZEC", locale, AmountUnit.ZEC).settled)
        assertEquals("1000.50", acceptAmountInput(AmountFieldState(), "1,000.50", locale, AmountUnit.ZEC).settled)
    }

    // QA2 U7 second follow-up (Fable review #13, finding 2; money-path rule 5): the eight-word deny list left every other
    // trailing word to be dropped, so "500 mZEC" settled 500 ZEC (1,000 times the intent), "5 BTC" 5 ZEC, "10k" 10 ZEC. A
    // paste whose trailing letters are not a harmless word is refused out loud: the error is set, no amount settles.

    /** Every paste is tried; the failure names all that settled or were dropped without an error. */
    private fun assertAllRefusedWithError(pastes: List<String>) {
        val bad =
            pastes.mapNotNull { paste ->
                val field = acceptAmountInput(AmountFieldState(), paste, locale, AmountUnit.ZEC)
                when {
                    field.settled.isNotEmpty() -> "\"$paste\" settled \"${field.settled}\" ZEC with its unit word dropped"
                    field.error == null -> "\"$paste\" was dropped without an error: shown \"${field.shown}\""
                    else -> null
                }
            }
        assertTrue(bad.isEmpty(), bad.joinToString("; "))
    }

    @Test
    fun a_pasted_milli_or_micro_zec_amount_is_refused_never_read_as_whole_zec() {
        assertAllRefusedWithError(listOf("500 mZEC", "0.5 mZEC", "500 uZEC", "500 \u00B5ZEC"))
    }

    @Test
    fun a_pasted_amount_in_another_currency_or_a_word_is_refused_never_read_as_zec() {
        assertAllRefusedWithError(
            listOf("5 BTC", "100 USDT", "100 EUR", "25 dollars", "1000 \u0437\u0430\u0442", "10k", "5 mil"),
        )
    }

    private fun refusedZat(): AmountFieldState {
        val refused = acceptAmountInput(AmountFieldState(), "1000 zat", locale, AmountUnit.ZEC)
        assertEquals("", refused.settled)
        assertNotNull(refused.error, "the \"1000 zat\" paste was not refused to begin with")
        return refused
    }

    // "editing never silently answers the question" (AmountTextReader.entryOf): the refusal stays open after one
    // backspace or one typed character, and nothing settles.
    @Test
    fun one_backspace_after_a_refused_paste_does_not_settle_an_amount() {
        val backspace = acceptAmountInput(refusedZat(), "1000 za", locale, AmountUnit.ZEC)
        assertEquals("", backspace.settled, "one backspace settled \"${backspace.settled}\" ZEC from a refused \"1000 zat\"")
        assertNotNull(backspace.error, "one backspace closed the refusal without an answer")
    }

    @Test
    fun one_typed_character_after_a_refused_paste_does_not_settle_an_amount() {
        val typed = acceptAmountInput(refusedZat(), "1000 zatx", locale, AmountUnit.ZEC)
        assertEquals("", typed.settled, "one typed character settled \"${typed.settled}\" ZEC from a refused \"1000 zat\"")
        assertNotNull(typed.error, "one typed character closed the refusal without an answer")
    }

    @Test
    fun pasting_letters_only_over_a_typed_amount_leaves_an_error_not_a_silent_clear() {
        val typed = AmountFieldState.settled("5")
        val field = acceptAmountInput(typed, "abc", locale, AmountUnit.ZEC)
        assertEquals("", field.settled, "letters over \"5\" left the amount \"${field.settled}\"")
        assertNotNull(field.error, "\"abc\" over a typed amount cleared the field with no error: shown \"${field.shown}\"")
    }

    @Test
    fun control_the_words_kept_on_purpose_still_read() {
        // Q2-46: one trailing word the reader could not take ("0.000013request") is read without that word; the ZEC sign
        // and plain grouped pastes read as they did.
        assertEquals("0.000013", acceptAmountInput(AmountFieldState(), "0.000013request", locale, AmountUnit.ZEC).settled)
        assertEquals("1.5", acceptAmountInput(AmountFieldState(), "1.5 ZEC", locale, AmountUnit.ZEC).settled)
        assertEquals("1000.50", acceptAmountInput(AmountFieldState(), "1,000.50", locale, AmountUnit.ZEC).settled)
    }
}
