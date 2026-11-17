package co.electriccoin.zcash.ui.screen.ai

import co.electriccoin.zcash.ui.design.util.AmountReadingProbe
import co.electriccoin.zcash.ui.screen.ai.AiTopupCustomAmountTest.Companion.accept
import org.junit.Test
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * F1 (fix-all 2.13.0, train T1): the AI top-up custom amount reads a pasted amount the way it was meant.
 *
 * At HEAD, normalizeUsdAmountInput keeps the first mark as the decimal point and cuts after two decimals, so a pasted
 * "1,000.50" and a pasted "1,000" are both $1.00, and the sheet offers a $1 top-up; customUsdTier rounds to the
 * nearest dollar, so $1000.50 would bill $1001.
 *
 * Wanted: "1,000.50" is $1000.50 and bills $1000 (whole dollars rounded down, shown on the sheet before paying);
 * "1,000" asks "$1 or $1000?"; "12.345" (more than cents one way, $12345 the other) asks to confirm $12345; the reader
 * uses the phone's language as the app sees it (rememberDesiredFormatLocale), not the JVM default.
 */
class AiTopupPasteTest {
    private val empty get() = AmountReadingProbe.emptyField()

    @Test
    fun `a pasted 1,000_50 is a thousand dollars fifty and bills 1000`() {
        val field = accept(empty, "1,000.50")
        assertEquals("1000.50", field.settled)
        assertEquals(1000, customUsdTier(field.settled), "never more than typed")
    }

    @Test
    fun `a pasted 1,000 asks 1 or 1000 and offers no top-up until a tap`() {
        val field = accept(empty, "1,000")
        assertEquals(listOf("1", "1000"), field.ask)
        assertEquals(null, customUsdTier(field.settled))
    }

    @Test
    fun `a Spanish 1_000,50 reads the same`() {
        assertEquals("1000.50", accept(empty, "1.000,50", Locale.forLanguageTag("es-ES")).settled)
    }

    @Test
    fun `a paste with more than cents one way asks to confirm the other`() {
        assertEquals(listOf("12345"), accept(empty, "12.345").ask)
    }

    @Test
    fun `the sheet reads with the app's language, asks under the field and uses the decimal keypad`() {
        val source = AmountReadingProbe.source(AiTopupCustomAmountTest.SHEET)
        assertTrue("val formatLocale = rememberDesiredFormatLocale()" in source)
        assertTrue("AmountReadingChooser(" in source)
        assertTrue("KeyboardType.Decimal" in source)
    }
}
