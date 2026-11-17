package co.electriccoin.zcash.ui.screen.transactionprogress

import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * R2-6 (b), p2-f3754a9288f6: the send-confirmation screens speak Spanish everywhere, including
 * "Sent — Not Confirmed".
 *
 * At 566b866d2 `send_confirmation/values-es` has no `send_confirmation_unconfirmed_title` or
 * `send_confirmation_unconfirmed_subtitle`, so a Spanish user whose payment may already be on the wire reads
 * the one screen that tells them not to pay again in English, between Spanish screens.
 *
 * The parity rule covers every key of the folder, so the next English-only string fails here too.
 */
class SendConfirmationSpanishParityTest {
    @Test
    fun `every send-confirmation string has a Spanish entry`() {
        val english = strings("values")
        val spanish = strings("values-es")

        val missing = english.keys - spanish.keys
        assertEquals(emptySet(), missing, "send_confirmation/values-es/strings.xml has no Spanish for these keys")
    }

    @Test
    fun `sent but not confirmed is translated, not copied, and not the success line`() {
        val english = strings("values")
        val spanish = strings("values-es")

        for (key in UNCONFIRMED_KEYS) {
            val es = spanish[key] ?: fail("values-es has no $key")
            assertTrue(es.isNotBlank(), "$key is blank in Spanish")
            assertNotEquals(english[key]?.normalize(), es.normalize(), "$key is the English text in the Spanish file")
        }
        assertNotEquals(
            spanish["send_confirmation_success_title"]?.normalize(),
            spanish["send_confirmation_unconfirmed_title"]?.normalize(),
            "the Spanish 'not confirmed' title says the same as the success title",
        )
    }

    private fun String.normalize() = replace(Regex("\\s+"), " ").trim()

    private fun strings(qualifier: String): Map<String, String> {
        val file = uiLibDir.resolve("src/main/res/ui/send_confirmation/$qualifier/strings.xml")
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
        val UNCONFIRMED_KEYS = listOf("send_confirmation_unconfirmed_title", "send_confirmation_unconfirmed_subtitle")

        val uiLibDir: File by lazy {
            val start = File(System.getProperty("user.dir") ?: ".").absoluteFile
            generateSequence(start) { it.parentFile }
                .take(8)
                .flatMap { sequenceOf(it, it.resolve("ui-lib")) }
                .firstOrNull { it.resolve("src/main/res/ui/send_confirmation").isDirectory }
                ?: fail("could not locate the ui-lib module from ${start.path}")
        }
    }
}
