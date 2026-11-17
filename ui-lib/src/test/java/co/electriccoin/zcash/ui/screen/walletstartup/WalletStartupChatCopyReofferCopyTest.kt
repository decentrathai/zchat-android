package co.electriccoin.zcash.ui.screen.walletstartup

import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * C6, R6-4 review finding 15 (P2): the words of the paused screen and of the offer that follows it must not promise
 * more than the code keeps.
 *
 * The paused screen said "your new chats are not affected", yet a later Restore replaces them. The offer after the
 * pause reused the first offer's text ("ZCHAT started them empty") and never said that restoring sets the chats of
 * the pause aside. Required, in English and Spanish alike: the paused text says the new chats are kept on this phone
 * (not that they are unaffected), and the re-offer after the user chatted during the pause has its own text, with no
 * placeholders, that says the current chats are kept as a copy and offered again, and that nothing is deleted.
 */
class WalletStartupChatCopyReofferCopyTest {
    private val english = strings("values")
    private val spanish = strings("values-es")

    @Test
    fun `every chat copy text exists in English and in Spanish`() {
        val enKeys = english.keys.filter { it.startsWith(CHAT_COPY_PREFIX) }.toSortedSet()
        val esKeys = spanish.keys.filter { it.startsWith(CHAT_COPY_PREFIX) }.toSortedSet()
        assertEquals(enKeys, esKeys, "the English and Spanish chat copy texts differ in their keys")
        for (key in enKeys) {
            assertTrue(english.getValue(key).isNotBlank(), "$key is blank in English")
            assertTrue(spanish.getValue(key).isNotBlank(), "$key is blank in Spanish")
        }
    }

    @Test
    fun `the paused screen does not promise the new chats are unaffected`() {
        val en = english[PAUSED_MESSAGE] ?: fail("no $PAUSED_MESSAGE in English")
        val es = spanish[PAUSED_MESSAGE] ?: fail("no $PAUSED_MESSAGE in Spanish")
        assertFalse(en.contains("are not affected", ignoreCase = true), "English: $en")
        assertFalse(es.contains("no se ven afectados", ignoreCase = true), "Spanish: $es")
        assertTrue(en.contains("kept on this phone", ignoreCase = true), "English: $en")
        assertTrue(es.contains("se quedan en este teléfono", ignoreCase = true), "Spanish: $es")
    }

    @Test
    fun `the re-offer after use has its own text in both languages with no placeholders`() {
        val en = english[REOFFER_MESSAGE] ?: fail("no $REOFFER_MESSAGE in English")
        val es = spanish[REOFFER_MESSAGE] ?: fail("no $REOFFER_MESSAGE in Spanish")
        for ((language, text) in listOf("English" to en, "Spanish" to es)) {
            assertFalse(PLACEHOLDER.containsMatchIn(text), "$language re-offer text has a placeholder: $text")
        }
        assertNotEquals(english[FIRST_OFFER_MESSAGE], en, "the English re-offer reuses the first offer's text")
        assertNotEquals(spanish[FIRST_OFFER_MESSAGE], es, "the Spanish re-offer reuses the first offer's text")
        assertNotEquals(en.normalize(), es.normalize(), "the Spanish text is the English text")
        assertFalse(en.contains("started them empty", ignoreCase = true), "English: $en")
        assertFalse(es.contains("los empezó vacíos", ignoreCase = true), "Spanish: $es")
        assertTrue(en.contains("kept as a copy on this phone", ignoreCase = true), "English: $en")
        assertTrue(en.contains("nothing is deleted", ignoreCase = true), "English: $en")
        assertTrue(es.contains("como copia en este teléfono", ignoreCase = true), "Spanish: $es")
        assertTrue(es.contains("no se borra nada", ignoreCase = true), "Spanish: $es")
    }

    private fun String.normalize() = replace(Regex("\\s+"), " ").trim()

    private fun strings(qualifier: String): Map<String, String> {
        val file = uiLibDir.resolve("src/main/res/ui/error/$qualifier/strings.xml")
        assertTrue(file.isFile, "missing ${file.path}")
        val nodes =
            DocumentBuilderFactory
                .newInstance()
                .newDocumentBuilder()
                .parse(file)
                .getElementsByTagName("string")
        return (0 until nodes.length)
            .map { nodes.item(it) as Element }
            .associate { it.getAttribute("name") to it.textContent }
    }

    private companion object {
        const val CHAT_COPY_PREFIX = "wallet_startup_chat_copy_"
        const val PAUSED_MESSAGE = "wallet_startup_chat_copy_paused_message"
        const val FIRST_OFFER_MESSAGE = "wallet_startup_chat_copy_message"
        const val REOFFER_MESSAGE = "wallet_startup_chat_copy_reoffer_after_use_message"

        /** A format argument (%s, %1$d, %%) or an ICU-style {name}. */
        val PLACEHOLDER = Regex("%|\\{[^}]*\\}")

        val uiLibDir: File by lazy {
            val start = File(System.getProperty("user.dir") ?: ".").absoluteFile
            generateSequence(start) { it.parentFile }
                .take(8)
                .flatMap { sequenceOf(it, it.resolve("ui-lib")) }
                .firstOrNull { it.resolve("src/main/res/ui/error").isDirectory }
                ?: fail("could not locate the ui-lib module from ${start.path}")
        }
    }
}
