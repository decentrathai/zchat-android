package co.electriccoin.zcash.ui.screen.chat.view

import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * ZC-739 (T7B-HANDSHAKE-SPEC rev 2, section 5, text T6) — the key-changed banner promised "Nothing is sent in this chat
 * until you decide". After ZC-739 the free signed rotation announce still goes on purpose (holding it loses incoming
 * messages) and call signalling was not traced (step R8), so the banner says only what is held: none of the user's
 * messages and no payment (ZC-739-R1: also no set-up already waiting for a block; the English sentence's number
 * agreement fixed after the review). Only its last sentence changes; the rest, `%1$s` included, stays. English and Spanish.
 */
class KeyChangedBannerPromisesOnlyWhatIsHeldTest {
    private val repoRoot: File by lazy {
        var dir: File? = File("").absoluteFile
        while (dir != null && !(File(dir, "gradle.properties").isFile && File(dir, "README.md").isFile)) {
            dir = dir.parentFile
        }
        assertNotNull(dir, "repo root not found above the test working dir")
    }

    private fun banner(dir: String): String {
        val xml = File(repoRoot, "ui-lib/src/main/res/ui/common/$dir/chat_send_strings.xml").readText()
        val pattern = Regex("""<string name="chat_key_changed_banner_body_pending"[^>]*>(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
        val match = assertNotNull(pattern.find(xml), "$dir: chat_key_changed_banner_body_pending is missing")
        return match.groupValues[1].replace("\\'", "'")
    }

    @Test
    fun `the English banner says no message and no payment goes out, not that nothing is sent`() {
        val text = banner("values")
        assertFalse("Nothing is sent in this chat" in text, "the banner still promises nothing is sent: $text")
        assertTrue(
            text.endsWith("Until you decide, none of your messages are sent and nothing is paid in this chat."),
            "the banner's last sentence is not T6: $text",
        )
        assertEquals(1, Regex("""%1\${'$'}s""").findAll(text).count(), "the banner lost its %1\$s: $text")
    }

    @Test
    fun `the Spanish banner says the same`() {
        val text = banner("values-es")
        assertFalse("No se envía nada en este chat" in text, "the Spanish banner still promises nothing is sent: $text")
        assertTrue(
            text.endsWith("Hasta que decidas, no sale de este chat ningún mensaje tuyo ni ningún pago."),
            "the Spanish banner's last sentence is not T6: $text",
        )
        assertEquals(1, Regex("""%1\${'$'}s""").findAll(text).count(), "the Spanish banner lost its %1\$s: $text")
    }
}
