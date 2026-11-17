package co.electriccoin.zcash.ui.screen.chat.view

import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * ES-W (fix-all 2.13.0, train T4c; Spanish fold spec §5 step 4) — the Welcome ZEC card and its confirm were English
 * literals in ChatDetailView ("Send Welcome ZEC", "Send 0.005 ZEC", "Send 0.005 ZEC?", "This sends 0.005 ZEC from your
 * wallet to … as a welcome"), so a Spanish phone showed them in English with a US decimal point, and the confirm named
 * no fee. The four `welcome_zec_*` keys live in `ui/common/values{,-es}/chat_money_strings.xml`, next to
 * `pay_request_confirm_*`, with the same placeholders in both languages, and the body names what leaves the wallet the
 * way `pay_request_confirm_body` does: D-6 (fix-all 2.13.0, owner W-10) the exact figure ("exactly" / "exactamente"),
 * no longer the floor ("at least" / "al menos").
 */
class WelcomeZecStringsHaveSpanishTest {
    private val keys = listOf("welcome_zec_card_title", "welcome_zec_send_button", "welcome_zec_confirm_title", "welcome_zec_confirm_body")

    private fun moneyFile(dir: String): String =
        File(ChatResourceText.repoRoot, "ui-lib/src/main/res/ui/common/$dir/chat_money_strings.xml").readText()

    @Test
    fun the_four_keys_are_in_the_money_strings_in_english_and_spanish() {
        for (dir in listOf("values", "values-es")) {
            val xml = moneyFile(dir)
            for (key in keys) assertTrue("<string name=\"$key\"" in xml, "$key is not in $dir/chat_money_strings.xml")
        }
    }

    @Test
    fun the_spanish_differs_and_keeps_the_placeholders() {
        val en = ChatResourceText.strings("values")
        val es = ChatResourceText.strings("values-es")
        val placeholders = Regex("""%\d\$[sd]""")
        for (key in keys) {
            val english = assertNotNull(en[key], "no English $key")
            val spanish = assertNotNull(es[key], "no Spanish $key")
            assertNotEquals(english, spanish, "$key: the Spanish is the English")
            assertEquals(
                placeholders.findAll(english).map { it.value }.toSet(),
                placeholders.findAll(spanish).map { it.value }.toSet(),
                "$key: English and Spanish use different placeholders: \"$english\" / \"$spanish\"",
            )
        }
    }

    @Test
    fun the_body_names_the_fees_and_the_floor() {
        val en = ChatResourceText.strings("values")
        val es = ChatResourceText.strings("values-es")
        val enBody = assertNotNull(en["welcome_zec_confirm_body"], "no English welcome_zec_confirm_body")
        val esBody = assertNotNull(es["welcome_zec_confirm_body"], "no Spanish welcome_zec_confirm_body")
        for (part in listOf("%1\$s", "%2\$s", "exactly %3\$s", "platform fee", "network fee", "can't be undone")) {
            assertTrue(part in enBody, "the English body lacks \"$part\": $enBody")
        }
        for (part in listOf("%1\$s", "%2\$s", "exactamente %3\$s", "comisión de la plataforma", "comisión de red")) {
            assertTrue(part in esBody, "the Spanish body lacks \"$part\": $esBody")
        }
        for (english in listOf("This sends", "from your wallet", "can't be undone", "at least")) {
            assertTrue(english !in esBody, "the Spanish body is still English (\"$english\"): $esBody")
        }
        // The English card texts are the ones the card showed before, with the amount as a placeholder.
        assertEquals("Send Welcome ZEC", en["welcome_zec_card_title"])
        assertEquals("Send %1\$s ZEC", en["welcome_zec_send_button"])
        assertEquals("Send %1\$s ZEC?", en["welcome_zec_confirm_title"])
    }
}
