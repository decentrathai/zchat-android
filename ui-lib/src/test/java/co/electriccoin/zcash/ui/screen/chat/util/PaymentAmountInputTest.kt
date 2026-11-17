package co.electriccoin.zcash.ui.screen.chat.util

import org.junit.Test
import java.text.DecimalFormatSymbols
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * RED pin for SEND-PAYMENT-COMMA: the chat Send Payment amount field reads "0,5" as 5 ZEC.
 *
 * The three chat amount fields (Send Payment, the time-lock required amount, the payment-request
 * amount) accepted a keystroke only when the whole new value matched `^\d*\.?\d*$`. A comma — the
 * decimal mark a Spanish or Serbian keyboard gives — failed the match, so the keystroke was ignored
 * and the next digit was appended to the integer part: typing 0 , 5 left "05", which the dialog
 * parses as 5 ZEC. That is ten times the amount the user meant to send.
 *
 * [acceptAmountKeystroke] is the one rule the fields use: it takes the value Compose proposes after
 * a keystroke (or a paste) and returns the text the field should hold, or null to ignore the
 * keystroke. A comma is read as the decimal point; at most one decimal mark and at most eight
 * decimals (one zatoshi) are accepted; anything else is refused.
 */
class PaymentAmountInputTest {
    /** What a Compose field does: each keystroke proposes `current + key`, the rule keeps or ignores it. */
    private fun type(vararg keys: String): String =
        keys.fold("") { current, key -> acceptAmountKeystroke(current + key) ?: current }

    @Test
    fun a_comma_is_read_as_the_decimal_point() {
        assertEquals("0.", acceptAmountKeystroke("0,"))
        assertEquals("0.5", acceptAmountKeystroke("0,5"))
        assertEquals(".", acceptAmountKeystroke(","))
    }

    @Test
    fun typing_0_comma_5_gives_half_a_zec_not_five() {
        val shown = type("0", ",", "5")

        assertEquals("0.5", shown, "0 , 5 on a Spanish keyboard must read as half a ZEC")
        assertEquals(0.5, shown.toDouble())
    }

    @Test
    fun a_comma_and_a_point_type_the_same_amount() {
        assertEquals(type("1", "2", ".", "2", "5"), type("1", "2", ",", "2", "5"))
        assertEquals("12.25", type("1", "2", ",", "2", "5"))
    }

    @Test
    fun a_second_decimal_mark_is_rejected() {
        assertNull(acceptAmountKeystroke("1.2,"))
        assertNull(acceptAmountKeystroke("1,2,"))
        assertNull(acceptAmountKeystroke("1.2."))
        assertNull(acceptAmountKeystroke("1,2."))
        assertEquals("1.25", type("1", ",", "2", ",", "5"), "the second mark is ignored, the value keeps its first one")
    }

    @Test
    fun letters_and_other_characters_are_rejected() {
        assertNull(acceptAmountKeystroke("1a"))
        assertNull(acceptAmountKeystroke("a"))
        assertNull(acceptAmountKeystroke("-1"))
        assertNull(acceptAmountKeystroke("1 000"))
        assertNull(acceptAmountKeystroke("1e5"))
        assertNull(acceptAmountKeystroke("+1"))
    }

    @Test
    fun empty_is_allowed() {
        assertEquals("", acceptAmountKeystroke(""))
    }

    @Test
    fun plain_amounts_are_unchanged() {
        assertEquals("0.5", acceptAmountKeystroke("0.5"))
        assertEquals("12", acceptAmountKeystroke("12"))
        assertEquals("0.00000001", acceptAmountKeystroke("0.00000001"))
        assertEquals("0.12345678", acceptAmountKeystroke("0,12345678"))
    }

    @Test
    fun more_than_8_decimals_is_rejected() {
        assertNull(acceptAmountKeystroke("0.123456789"))
        assertNull(acceptAmountKeystroke("0,123456789"))
        assertEquals("0.12345678", type("0", ".", "1", "2", "3", "4", "5", "6", "7", "8", "9"))
    }

    @Test
    fun the_magnitude_never_changes_silently() {
        // A keystroke is either taken as typed (a decimal mark shown as the point) or ignored whole.
        // It never yields some third value, which is how "0,5" became "05". No result is skipped: the expected
        // outcome of every keystroke is spelled out, so a rule that ignores a decimal mark fails here
        // (C8, round 7 review finding 24: the old version skipped null results and passed on the old rule).
        val starts = listOf("", "0", "5", "12", "0.", "0.5", "1.2", "0.1234567", "0.12345678")
        val keys = listOf("0", "1", "5", "9", ",", ".", "\u066B", "a", " ", "-")
        for (start in starts) {
            for (key in keys) {
                val decimals = if ('.' in start) start.substringAfter('.').length else -1
                val expected =
                    when {
                        key.single().isAsciiDigit() -> if (decimals >= MAX_AMOUNT_DECIMALS) null else start + key
                        key in DECIMAL_MARKS -> if ('.' in start) null else "$start."
                        else -> null
                    }
                assertEquals(expected, acceptAmountKeystroke(start + key), "'$start' + '$key'")
            }
        }
    }

    @Test
    fun every_decimal_mark_reads_as_the_point() {
        for (mark in DECIMAL_MARKS) {
            assertEquals("0.5", acceptAmountKeystroke("0${mark}5"), "0 + '$mark' + 5")
            assertEquals("0.5", type("0", mark, "5"), "typing 0 '$mark' 5")
            assertNull(acceptAmountKeystroke("0.5$mark"), "a second mark ('$mark') is still refused")
        }
        assertNull(acceptAmountKeystroke("1,2\u066B"), "a comma and an Arabic separator are two marks")
    }

    @Test
    fun the_decimal_mark_of_the_phone_language_reads_as_the_point() {
        val saved = Locale.getDefault(Locale.Category.FORMAT)
        try {
            for (tag in listOf("en-US", "es-419", "es-ES", "sr-Latn-RS", "de-DE", "fr-FR", "ar-EG", "fa-IR", "ru-RU")) {
                Locale.setDefault(Locale.Category.FORMAT, Locale.forLanguageTag(tag))
                val mark = DecimalFormatSymbols.getInstance().decimalSeparator
                if (mark.isDigit() || mark.isWhitespace()) continue
                val label = "$tag decimal mark '$mark' (U+%04X)".format(mark.code)
                assertEquals("0.5", acceptAmountKeystroke("0${mark}5"), "chat amount field, $label")
            }
        } finally {
            Locale.setDefault(Locale.Category.FORMAT, saved)
        }
    }

    @Test
    fun a_thousands_comma_keeps_the_b8_reading() {
        // B8 reads a pasted "1,000" as 1.000 (one ZEC): a comma is the decimal mark. Whether that is acceptable is
        // an open owner decision (B8 REVIEW.md point 2); this pin keeps C8 from changing it silently.
        assertEquals("1.000", acceptAmountKeystroke("1,000"))
        assertEquals("1.000", acceptAmountKeystroke("1\u066B000"))
        assertNull(acceptAmountKeystroke("1,000.50"))
        assertNull(acceptAmountKeystroke("1.000,50"))
    }

    @Test
    fun a_decimal_mark_typed_into_a_whole_number_is_never_ignored() {
        // The bug in one line: ignoring the first decimal mark makes every later digit ten times bigger.
        for (whole in listOf("", "0", "5", "12", "300")) {
            assertEquals("$whole.", acceptAmountKeystroke("$whole,"), "'$whole' + ','")
            assertEquals("$whole.", acceptAmountKeystroke("$whole."), "'$whole' + '.'")
        }
    }

    private companion object {
        /** The decimal marks a keyboard may send: the point, the comma and the Arabic decimal separator. */
        val DECIMAL_MARKS = listOf(".", ",", "\u066B")

        fun Char.isAsciiDigit() = this in '0'..'9'
    }
}
