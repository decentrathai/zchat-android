package co.electriccoin.zcash.ui.design.util

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * F1 (fix-all 2.13.0, train T1): the amount question and the amount errors speak English and Spanish.
 *
 * The strings live in ui-design-lib (amount_input_strings.xml), next to the chooser that shows them. Every key exists
 * in values and values-es, carries the same placeholders, and is translated (the unit-only "%1$s ZEC" is the one text
 * that is the same in both).
 */
class AmountInputStringsAreTranslatedTest {
    private fun strings(dir: String): Map<String, String> {
        val file = File(AmountReadingProbe.repoRoot, "ui-design-lib/src/main/res/ui/common/$dir/amount_input_strings.xml")
        assertTrue(file.isFile, "missing: ${file.path}")
        return Regex("""<string name="([^"]+)"[^>]*>(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
            .findAll(file.readText())
            .associate { it.groupValues[1] to it.groupValues[2] }
    }

    private fun placeholders(text: String): List<String> = Regex("""%\d+\$[sd]""").findAll(text).map { it.value }.toList()

    @Test
    fun every_amount_text_exists_in_english_and_spanish() {
        val en = strings("values")
        val es = strings("values-es")
        for (key in KEYS) {
            val english = assertNotNull(en[key], "no English $key")
            val spanish = assertNotNull(es[key], "no Spanish $key")
            assertEquals(placeholders(english), placeholders(spanish), "$key: the Spanish placeholders differ")
            if (key !in SAME_IN_BOTH) assertNotEquals(english, spanish, "$key is not translated")
        }
        assertEquals(KEYS.toSet(), en.keys, "English keys")
        assertEquals(KEYS.toSet(), es.keys, "Spanish keys")
    }

    @Test
    fun the_question_names_both_readings_and_the_errors_say_what_to_do() {
        val en = strings("values")
        assertTrue("%1\$s" in en.getValue("amount_paste_which_body"), "the question quotes the paste")
        assertEquals(listOf("%1\$s", "%2\$s"), placeholders(en.getValue("amount_paste_confirm_body")))
        assertTrue("8" in en.getValue("amount_too_many_decimals_zec"))
        assertTrue("2" in en.getValue("amount_too_many_decimals_usd"))
    }

    @Test
    fun the_other_unit_error_names_the_sign_found_and_the_field_s_unit() {
        // T1 fixup (X-F1-1): a ZEC field refuses a dollar sign and a dollar field refuses a ZEC sign, and says so.
        val en = strings("values")
        val inZec = en.getValue("amount_other_unit_zec")
        assertTrue("dollars" in inZec && "ZEC" in inZec, inZec)
        val inUsd = en.getValue("amount_other_unit_usd")
        assertTrue("ZEC" in inUsd && "dollars" in inUsd, inUsd)
        val chooser =
            File(
                AmountReadingProbe.repoRoot,
                "ui-design-lib/src/main/java/co/electriccoin/zcash/ui/design/component/AmountReadingChooser.kt"
            ).readText()
        assertTrue("R.string.amount_other_unit_zec" in chooser && "R.string.amount_other_unit_usd" in chooser)
    }

    private companion object {
        val KEYS =
            listOf(
                "amount_paste_which_title",
                "amount_paste_which_body",
                "amount_paste_confirm_body",
                "amount_paste_option_zec",
                "amount_paste_option_usd",
                "amount_paste_unreadable",
                "amount_paste_into_amount",
                "amount_too_many_decimals_zec",
                "amount_too_many_decimals_usd",
                "amount_other_unit_zec",
                "amount_other_unit_usd",
            )
        val SAME_IN_BOTH = setOf("amount_paste_option_zec")
    }
}
