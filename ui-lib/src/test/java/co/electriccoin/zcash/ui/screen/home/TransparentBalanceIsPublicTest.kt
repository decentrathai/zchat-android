package co.electriccoin.zcash.ui.screen.home

import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * R2-7 (d), plan step p2-99ab9cd379eb (TX3e1-150): the Home "Transparent Balance Detected" banner calls
 * transparent ZEC "private but not spendable yet". Transparent ZEC is public on the blockchain until it is
 * shielded (the wallet's own label is "Transparent (Not Private)"). values-es has no such key, so a Spanish
 * user reads the English claim.
 *
 * Must hold: the banner line (HomeVM ShieldFunds) says transparent ZEC is public until shielded, in English
 * and Spanish, keeping both placeholders (amount, ticker).
 */
class TransparentBalanceIsPublicTest {
    private val homeRes: File by lazy {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "ui-lib/src/main/res/ui/home").isDirectory) dir = dir.parentFile
        File(assertNotNull(dir, "repo root not found"), "ui-lib/src/main/res/ui/home")
    }

    @Test
    fun the_banner_says_transparent_zec_is_public_in_english() {
        val en = strings("values")[KEY] ?: fail("values has no $KEY")
        assertFalse("private but not spendable" in en.lowercase(), en)
        assertTrue("public" in en.lowercase() && "shield" in en.lowercase(), en)
        assertTrue("%1\$s" in en && "%2\$s" in en, "placeholders lost: $en")
    }

    @Test
    fun the_banner_says_transparent_zec_is_public_in_spanish() {
        val en = strings("values")[KEY]
        val es = strings("values-es")[KEY] ?: fail("values-es has no $KEY, so Spanish users read the English line")
        assertFalse("privado" in es.lowercase(), es)
        assertTrue("público" in es.lowercase() || "publico" in es.lowercase(), es)
        assertTrue("%1\$s" in es && "%2\$s" in es, "placeholders lost: $es")
        assertNotEquals(en, es, "the Spanish line is the English text")
    }

    @Test
    fun home_uses_this_line_for_the_banner() {
        val main = homeRes.parentFile?.parentFile?.parentFile ?: fail("ui-lib/src/main not found")
        val vm = File(main, "java/co/electriccoin/zcash/ui/screen/home/HomeVM.kt").readText()
        assertTrue("R.string.$KEY" in vm, "HomeVM no longer shows $KEY")
    }

    private fun strings(qualifier: String): Map<String, String> {
        val f = File(homeRes, "$qualifier/strings.xml")
        val nodes =
            DocumentBuilderFactory
                .newInstance()
                .newDocumentBuilder()
                .parse(f)
                .getElementsByTagName("string")
        return (0 until nodes.length)
            .map { nodes.item(it) as Element }
            .associate { it.getAttribute("name") to it.textContent }
    }

    private companion object {
        const val KEY = "home_message_transparent_balance_subtitle_explain"
    }
}
