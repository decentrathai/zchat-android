package co.electriccoin.zcash.ui.screen.chat.model

import cash.z.ecc.android.sdk.model.Zatoshi
import co.electriccoin.zcash.ui.common.model.Zip317
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * R2-2 (c), plan step p2-089b7b0c7ee2 — cost figures the user is shown must never be below what is paid.
 *
 * 1. The handshake consent quotes 17,000 zatoshi per handshake ("about 0.00017 ZEC"): the outputs plus the
 *    ZIP 317 fee of a ONE-input send. A wallet whose balance sits in four or more small notes spends more
 *    inputs and pays 20,000 or more in fee alone, so "about" understates it. The figure is a floor: the
 *    consent dialog (hard-coded English in AndroidChat) and the Move-old-balance line must say "at least",
 *    in English and Spanish.
 * 2. Create Group says "Each message costs ~0.0001 ZEC per member". Each member gets its own transaction
 *    (1,000 carrier + 1,000 platform fee + 15,000 fee), 0.00017 ZEC at the least, so the line understates
 *    the cost by 41 %. It must state at least 0.00017 per member and the total for the members chosen, in
 *    English and Spanish.
 */
class TrueCostFiguresTest {
    private val repoRoot: File by lazy {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "ui-lib/src/main/res").isDirectory) dir = dir.parentFile
        assertNotNull(dir, "repo root not found")
    }

    private fun source(path: String) = File(repoRoot, "ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/$path").readText()

    private fun plural(
        file: String,
        locale: String,
        name: String
    ): Map<String, String> {
        val f = File(repoRoot, "ui-lib/src/main/res/ui/common/$locale/$file")
        assertTrue(f.isFile, "missing ${f.path}")
        val nodes =
            DocumentBuilderFactory
                .newInstance()
                .newDocumentBuilder()
                .parse(f)
                .getElementsByTagName("plurals")
        val plural =
            (0 until nodes.length).map { nodes.item(it) as Element }.firstOrNull { it.getAttribute("name") == name }
        assertNotNull(plural, "$locale: plurals $name missing")
        val items = plural.getElementsByTagName("item")
        return (0 until items.length).associate {
            val e = items.item(it) as Element
            e.getAttribute("quantity") to e.textContent
        }
    }

    private fun string(
        dir: String,
        file: String,
        locale: String,
        name: String
    ): String {
        val f = File(repoRoot, "ui-lib/src/main/res/ui/$dir/$locale/$file")
        val nodes =
            DocumentBuilderFactory
                .newInstance()
                .newDocumentBuilder()
                .parse(f)
                .getElementsByTagName("string")
        return assertNotNull(
            (0 until nodes.length).map { nodes.item(it) as Element }.firstOrNull { it.getAttribute("name") == name }?.textContent,
            "$locale: $name missing",
        )
    }

    /** Pin (passes before and after): why the handshake figure is a floor. */
    @Test
    fun a_four_note_wallet_pays_more_fee_than_the_handshake_quote_assumes() {
        assertTrue(Zip317.conventionalFeeZatoshi(4) > HANDSHAKE_FEE_ZAT)
    }

    @Test
    fun the_handshake_consent_says_at_least_in_english_and_spanish() {
        assertFalse(
            "about \${zecDisplay(ask.estimatedCost)}" in source("chat/AndroidChat.kt"),
            "the consent dialog still quotes the handshake floor as \"about\"",
        )
        for ((locale, floor) in listOf("values" to "at least", "values-es" to "al menos")) {
            val items = plural("chat_money_strings.xml", locale, "handshake_consent_cost")
            assertTrue("one" in items && "other" in items, "$locale: one and other are required")
            items.forEach { (q, text) ->
                assertTrue("%1\$d" in text && "%2\$s" in text && floor in text, "$locale/$q: $text")
            }
        }
        for ((locale, floor) in listOf("values" to "at least", "values-es" to "al menos")) {
            val text = string("send_confirmation", "move_old_balance_strings.xml", locale, "move_old_balance_consent_handshakes")
            assertTrue(floor in text, "$locale: the Move-old-balance handshake line must say $floor: $text")
        }
    }

    @Test
    fun the_create_group_line_states_the_real_per_member_floor_in_english_and_spanish() {
        assertFalse(
            "~0.0001 ZEC per member" in source("chat/view/CreateGroupView.kt"),
            "Create Group still says ~0.0001 ZEC per member; one transaction per member costs at least 0.00017",
        )
        for ((locale, floor) in listOf("values" to "at least", "values-es" to "al menos")) {
            val items = plural("chat_money_strings.xml", locale, "create_group_cost_note")
            // QA2 Q2-58 (W-137): the one-member note names "the other member" and one figure (%2$s, equal to the
            // total for one); every other form keeps the count and the total.
            items.forEach { (q, text) ->
                assertTrue("%2\$s" in text && floor in text, "$locale/$q: $text")
                if (q != "one") assertTrue("%1\$d" in text && "%3\$s" in text, "$locale/$q: $text")
            }
        }
        val floor =
            Class
                .forName("co.electriccoin.zcash.ui.screen.chat.model.PaidActionConfirmationKt")
                .getMethod("groupMessageCostFloor", Int::class.javaPrimitiveType)
        for (members in listOf(1, 5)) {
            val total = (floor.invoke(null, members) as Zatoshi).value
            assertTrue(total >= members * 17_000L, "$members member(s): $total is below the real total")
        }
    }
}
