package co.electriccoin.zcash.ui.screen.chat.model

import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * R2-2 (b) — the paid-action confirmation says, in English and Spanish, what the tap does, how many on-chain
 * transactions it sends and the least they cost ("at least"; a wallet with many notes pays a larger fee).
 */
class PaidActionCopyStringsTest {
    private val resRoot: File by lazy {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "ui-lib/src/main/res/ui/common").isDirectory) dir = dir.parentFile
        File(assertNotNull(dir, "repo root not found"), "ui-lib/src/main/res/ui/common")
    }

    private fun doc(locale: String) =
        DocumentBuilderFactory
            .newInstance()
            .newDocumentBuilder()
            .parse(File(resRoot, "$locale/chat_money_strings.xml"))

    private fun strings(locale: String): Map<String, String> {
        val nodes = doc(locale).getElementsByTagName("string")
        return (0 until nodes.length).associate {
            val e = nodes.item(it) as Element
            e.getAttribute("name") to e.textContent
        }
    }

    private fun plurals(
        locale: String,
        name: String
    ): Map<String, String> {
        val nodes = doc(locale).getElementsByTagName("plurals")
        val plural =
            (0 until nodes.length)
                .map { nodes.item(it) as Element }
                .single { it.getAttribute("name") == name }
        val items = plural.getElementsByTagName("item")
        return (0 until items.length).associate {
            val e = items.item(it) as Element
            e.getAttribute("quantity") to e.textContent
        }
    }

    @Test
    fun every_paid_action_is_described_in_english_and_spanish() {
        val en = strings("values")
        val es = strings("values-es")
        for (action in PaidAction.entries) {
            val key = "paid_action_" + action.name.lowercase()
            val english = assertNotNull(en[key], "English text missing for $action")
            val spanish = assertNotNull(es[key], "Spanish text missing for $action")
            assertNotEquals(english, spanish, "$action is not translated")
        }
        for (key in listOf("paid_action_title", "paid_action_confirm", "paid_action_cancel")) {
            assertNotEquals(assertNotNull(en[key], key), assertNotNull(es[key], key))
        }
    }

    @Test
    fun the_cost_line_carries_the_count_and_the_total_as_a_floor() {
        for ((locale, floor) in listOf("values" to "at least", "values-es" to "al menos")) {
            val items = plurals(locale, "paid_action_cost")
            assertTrue("one" in items && "other" in items, "$locale: one and other are required")
            items.forEach { (quantity, text) ->
                assertTrue("%1\$d" in text && "%2\$s" in text, "$locale/$quantity must carry the count and the ZEC total: $text")
                assertTrue(floor in text, "$locale/$quantity must say the total is a floor: $text")
            }
        }
    }

    @Test
    fun one_transaction_is_quoted_at_no_less_than_its_outputs_and_a_one_input_fee() {
        // 1,000 carrier + 1,000 platform fee + 15,000 ZIP 317 fee for three actions.
        assertEquals(17_000L, onChainSendMinimumCost().value)
        assertEquals(51_000L, paidActionConfirmation(PaidAction.GROUP_LEAVE, 3).totalCost.value)
        assertEquals("0.00017", PaidActionCopy.zec(onChainSendMinimumCost(), java.util.Locale.US))
    }
}
