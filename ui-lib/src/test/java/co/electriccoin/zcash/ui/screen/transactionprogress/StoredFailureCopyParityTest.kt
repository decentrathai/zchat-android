package co.electriccoin.zcash.ui.screen.transactionprogress

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
 * Round 8 D3 (REVIEW_7C F30): the "Send Not Confirmed" screen for a Failure that names a stored transaction exists
 * in English and Spanish, says the same thing in both, and promises nothing the code does not do.
 *
 * The code guarantees only this: the wallet created (stored) the transaction, the network reported an error, and
 * the SDK may still broadcast it. So the text must not say "try it again" or "will retry", and must send the user
 * to the transaction history before any new send.
 */
class StoredFailureCopyParityTest {
    @Test
    fun `the stored-failure title and text exist in English and Spanish with the reviewed wording`() {
        val english = strings("values")
        val spanish = strings("values-es")

        for ((key, expected) in ENGLISH) {
            assertEquals(expected, english[key]?.normalize() ?: fail("values has no $key"), "English $key")
        }
        for ((key, expected) in SPANISH) {
            val es = spanish[key]?.normalize() ?: fail("values-es has no $key")
            assertEquals(expected, es, "Spanish $key")
            assertNotEquals(english[key]?.normalize(), es, "$key is the English text in the Spanish file")
        }
    }

    @Test
    fun `the stored-failure text never says try again or will retry`() {
        val english = strings("values")[SUBTITLE]?.normalize()?.lowercase() ?: fail("values has no $SUBTITLE")
        val spanish = strings("values-es")[SUBTITLE]?.normalize()?.lowercase() ?: fail("values-es has no $SUBTITLE")

        for (phrase in listOf("try it again", "try again", "will retry")) {
            assertFalse(phrase in english, "the English text says '$phrase'")
        }
        for (phrase in listOf("inténtalo de nuevo", "reintentará")) {
            assertFalse(phrase in spanish, "the Spanish text says '$phrase'")
        }
        assertTrue("transaction history" in english, "the English text does not send the user to the history")
        assertTrue("historial de transacciones" in spanish, "the Spanish text does not send the user to the history")
    }

    private fun String.normalize() = replace("\\'", "'").replace(Regex("\\s+"), " ").trim()

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
        const val TITLE = "send_confirmation_failure_stored_title"
        const val SUBTITLE = "send_confirmation_failure_stored_subtitle"

        val ENGLISH =
            mapOf(
                TITLE to "Send Not Confirmed",
                SUBTITLE to
                    "The network reported an error, but your wallet created this transaction and may still send " +
                    "it. Check your transaction history before you send again.",
            )

        val SPANISH =
            mapOf(
                TITLE to "Envío sin confirmar",
                SUBTITLE to
                    "La red informó de un error, pero tu billetera creó esta transacción y aún podría enviarla. " +
                    "Revisa tu historial de transacciones antes de volver a enviar.",
            )

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
