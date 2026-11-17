package co.electriccoin.zcash.ui.design.component

import co.electriccoin.zcash.ui.design.util.AmountReadingProbe
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * F12 (fix-all 2.13.0, train T3): the question a Swap or Pay amount field asks is on the screen, and its texts speak
 * English and Spanish.
 *
 * The chooser of F1 (AmountReadingChooser) labels its readings "1.5 ZEC" or "$1.5". A token field has no ZEC or dollar
 * unit, so F12 adds a label with the token's ticker ("1.5 USDC"), and two errors for a field with no currency: its own
 * precision ("at most 6 decimals") and a currency sign pasted into it. They live in number_field_strings.xml
 * (ui-design-lib), in values and values-es with the same placeholders.
 */
class NumberFieldQuestionShownTest {
    private fun strings(dir: String): Map<String, String> {
        val file = File(AmountReadingProbe.repoRoot, "ui-design-lib/src/main/res/ui/common/$dir/number_field_strings.xml")
        assertTrue(file.isFile, "missing: ${file.path}")
        return Regex("""<string name="([^"]+)"[^>]*>(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
            .findAll(file.readText())
            .associate { it.groupValues[1] to it.groupValues[2] }
    }

    private fun placeholders(text: String): List<String> = Regex("""%\d+\$[sd]""").findAll(text).map { it.value }.toList()

    @Test
    fun every_number_field_text_exists_in_english_and_spanish() {
        val en = strings("values")
        val es = strings("values-es")
        for (key in KEYS) {
            val english = assertNotNull(en[key], "no English $key")
            val spanish = assertNotNull(es[key], "no Spanish $key")
            assertEquals(placeholders(english), placeholders(spanish), "$key: the Spanish placeholders differ")
            if (key !in SAME_IN_BOTH) assertNotEquals(english, spanish, "$key is not translated")
        }
        assertEquals(listOf("%1\$d"), placeholders(en.getValue("number_field_too_many_decimals")))
        assertEquals(listOf("%1\$s", "%2\$s"), placeholders(en.getValue("number_field_option_ticker")))
    }

    @Test
    fun swap_and_pay_show_the_question_under_their_amount_fields() {
        val swap =
            AmountReadingProbe.source("ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/swap/ui/SwapAmountTextField.kt")
        assertTrue("ZashiNumberTextFieldQuestion(" in swap, "Swap never shows the question, so its button stays disabled")
        val pay = AmountReadingProbe.source("ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/pay/PayView.kt")
        assertEquals(2, Regex("""ZashiNumberTextFieldQuestion\(""").findAll(pay).count(), "Pay shows a question for each field")
    }

    private companion object {
        val KEYS = listOf("number_field_option_ticker", "number_field_too_many_decimals", "number_field_other_unit")
        val SAME_IN_BOTH = setOf("number_field_option_ticker")
    }
}
