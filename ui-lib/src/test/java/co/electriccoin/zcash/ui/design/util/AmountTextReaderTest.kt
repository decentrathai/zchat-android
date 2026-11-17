package co.electriccoin.zcash.ui.design.util

import co.electriccoin.zcash.ui.design.util.AmountReadingProbe.Read
import co.electriccoin.zcash.ui.design.util.AmountReadingProbe.formatForField
import co.electriccoin.zcash.ui.design.util.AmountReadingProbe.read
import java.math.BigDecimal
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * F1 (fix-all 2.13.0, train T1): one amount reader for every money field, where how the text arrived decides the rule.
 *
 * At HEAD there is no such reader. Each field has its own rule, and one phone gives different answers on different
 * screens: a pasted "1,000" is 1 ZEC in the chat dialogs and 1000 ZEC on main Send (en-US); a pasted "1.500" on a
 * Spanish phone is 1500 ZEC on main Send, 1000 times the English 1.5 an exchange meant, with no question; a pasted
 * "1,000.50" or "0.123456789" is silently dropped in chat; the AI top-up reads "1,000.50" as $1.
 *
 * Wanted:
 * - PASTE: a text that reads two ways (1-3 digits, one mark, 3 digits) asks, smaller reading first, on every locale;
 *   anything else reads one way only (mixed marks, repeated grouping, symbols, other scripts) or is refused.
 * - KEYSTROKE: a single mark is the decimal point (1 , 0 0 0 typed is 1), a second mark is refused. Never asks.
 * - STORED: the field's own text ("1,5" on a Spanish phone, "1.000" on an English one) is read as it stands.
 * - formatForField: what the app writes is never grouped and keeps every zatoshi.
 */
class AmountTextReaderTest {
    private val en = Locale.US
    private val es = Locale.forLanguageTag("es-ES")
    private val deCh = Locale.forLanguageTag("de-CH")
    private val arEg = Locale.forLanguageTag("ar-EG")

    private fun exact(text: String) = Read.Exact(text)

    private fun ask(
        raw: String,
        vararg options: String
    ) = Read.Ask(options.toList(), raw)

    private fun value(read: Read): String {
        assertTrue(read is Read.Exact, "expected one reading, got $read")
        return BigDecimal(read.text).stripTrailingZeros().toPlainString()
    }

    @Test
    fun `a pasted 1,000 asks 1 or 1000 on every locale, smaller first`() {
        for (locale in listOf(en, es, deCh, arEg, Locale.forLanguageTag("es-419"))) {
            assertEquals(ask("1,000", "1", "1000"), read("1,000", locale), "1,000 @ $locale")
        }
    }

    @Test
    fun `a pasted 1_500 on a Spanish phone asks 1_5 or 1500, never pays 1500 unasked`() {
        assertEquals(ask("1.500", "1.5", "1500"), read("1.500", es))
        assertEquals(ask("1.500", "1.5", "1500"), read("1.500", en))
    }

    @Test
    fun `mixed marks and repeated grouping read one way`() {
        for (locale in listOf(en, es)) {
            assertEquals("1000.5", value(read("1,000.50", locale)), "1,000.50 @ $locale")
            assertEquals("1000.5", value(read("1.000,50", locale)), "1.000,50 @ $locale")
            assertEquals("1234567", value(read("1.234.567", locale)))
            assertEquals("1234567", value(read("1,234,567", locale)))
            assertEquals("1234567.8", value(read("12,34,567.8", locale)), "Indian grouping @ $locale")
        }
    }

    @Test
    fun `marks that can't be read one way are refused`() {
        for (text in listOf("1,2,3", "1.2.3", "1.000.50", "1,000,5", "12,34.5.6", "abc", "1e5", "-5", "1,,000")) {
            assertEquals(Read.Invalid("Unreadable"), read(text, en), "'$text'")
        }
    }

    @Test
    fun `a single mark that is not the thousands shape is the decimal point`() {
        assertEquals("0.5", value(read("0,5", en)))
        assertEquals("12.5", value(read("12,50", en)))
        assertEquals("1234.5", value(read("1234,5", en)))
        assertEquals("0.5", value(read(",5", en)))
        assertEquals("0.5", value(read("0.500", es)))
        assertEquals("1.23456", value(read("1,23456", es)))
    }

    @Test
    fun `currency symbols, spaces, apostrophes and Arabic script are understood`() {
        // T1 fixup (X-F1-1): a sign names a unit, so "$" reads only in a dollar field; a ZEC field refuses it.
        assertEquals("1234.56", value(read("$1,234.56", en, maxDecimals = 2)))
        assertEquals(Read.Invalid("OtherUnit(USD)"), read("$1,234.56", en), "a dollar sign in a ZEC field")
        assertEquals("1234.56", value(read("1 234,56", es)), "narrow no-break space grouping")
        assertEquals("1234.56", value(read("1 234,56 ZEC", es)))
        assertEquals("1234.5", value(read("1’234.50", deCh)), "Swiss apostrophe grouping")
        assertEquals("1234.5", value(read("١٬٢٣٤٫٥", arEg)), "Arabic digits and marks")
        assertEquals("1000", value(read("١٬٠٠٠", arEg)), "the Arabic grouping mark only groups")
        assertEquals(Read.Invalid("Unreadable"), read("12 5", es), "a space that is not between thousands groups")
    }

    @Test
    fun `more than 8 ZEC decimals is refused with a reason, not dropped`() {
        assertEquals(Read.Invalid("TooManyDecimals(8)"), read("0.123456789", en))
        assertEquals("0.12345678", value(read("0.123456780", en)), "a trailing zero is not a ninth decimal")
    }

    @Test
    fun `a dollar paste that only fits one reading still asks as a confirmation`() {
        assertEquals(ask("12.345", "12345"), read("12.345", en, maxDecimals = 2))
    }

    @Test
    fun `a field that can't ask refuses the ambiguous shape itself and reads a grouped number (QA2 m3, ZC-1739)`() {
        // m2: a field that can't ask read the single usable reading ("428,143" in the restore height was 428143, "12.345" in
        // a dollar field was 12345). m3 (owner decision W-137, design section 1 point 6): refuse on the ambiguous shape
        // itself, however many readings fit the precision. A number that reads one way still reads.
        assertEquals(Read.Invalid("Unreadable"), read("428,143", en, maxDecimals = 0, ask = false))
        assertEquals(Read.Invalid("Unreadable"), read("12.345", en, maxDecimals = 2, ask = false))
        assertEquals("3428143", value(read("3,428,143", en, maxDecimals = 0, ask = false)))
        // H7: slippage (2 decimals, no question) "1,500" is 1.5 or 1500: refused.
        assertEquals(Read.Invalid("Unreadable"), read("1,500", en, maxDecimals = 2, ask = false))
    }

    @Test
    fun `a keystroke mark is the decimal point and never asks`() {
        assertEquals(exact("1."), read("1,", en, entry = "KEYSTROKE", old = "1"))
        assertEquals(exact("1.000"), read("1,000", en, entry = "KEYSTROKE", old = "1,00"))
        assertEquals(exact("1.5"), read("1.5", es, entry = "KEYSTROKE", old = "1."))
        assertEquals(Read.Invalid("Unreadable"), read("1.2,", en, entry = "KEYSTROKE", old = "1.2"), "a second mark")
        assertEquals(Read.Invalid("TooManyDecimals(8)"), read("0.123456789", en, entry = "KEYSTROKE", old = "0.12345678"))
    }

    @Test
    fun `H6 a paste inside typed text`() {
        // Typed "1." then pasted "000": the typed mark stands, 1.000 is one ZEC, no question.
        assertEquals(exact("1.000"), read("1.000", en, old = "1."))
        // A pasted piece with marks inside a typed amount can't be merged safely.
        assertEquals(Read.Invalid("PasteIntoAmount"), read("51,05", en, old = "55"))
    }

    @Test
    fun `stored text is read as it stands`() {
        assertEquals("1.5", value(read("1,5", es, entry = "STORED")))
        assertEquals("1", value(read("1.000", en, entry = "STORED")))
        assertEquals("1000.5", value(read("1000٫5", arEg, entry = "STORED")))
    }

    @Test
    fun `what the app writes is never grouped and keeps every zatoshi`() {
        val grouping = listOf(',', '’', '٬', ' ', ' ', ' ')
        for (locale in listOf(en, es, deCh, arEg)) {
            val thousand = formatForField(BigDecimal("1000"), locale, 8)
            assertFalse(thousand.any { it in grouping }, "1000 @ $locale is grouped: '$thousand'")
            assertEquals("1000", thousand, "1000 @ $locale")
            assertEquals("1000", value(read(thousand, locale, entry = "STORED")), "1000 re-read @ $locale")
            val exactZec = formatForField(BigDecimal("0.12345678"), locale, 8)
            assertEquals("0.12345678", value(read(exactZec, locale, entry = "STORED")), "0.12345678 @ $locale")
        }
        assertEquals("0,12345678", formatForField(BigDecimal("0.12345678"), es, 8))
        assertEquals("12.35", formatForField(BigDecimal("12.346"), en, 2), "a fiat figure is shown to the cent")
    }
}
