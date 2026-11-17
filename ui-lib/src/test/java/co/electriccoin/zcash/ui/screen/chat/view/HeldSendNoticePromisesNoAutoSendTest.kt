package co.electriccoin.zcash.ui.screen.chat.view

import co.electriccoin.zcash.ui.screen.chat.crypto.RestoreKexPolicy
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Round 7c (R6-4 review finding 18): the notice for a send the history settlement holds
 * ([RestoreKexPolicy.STILL_READING_HISTORY], chat_send_still_reading_history) told the user "your message goes
 * then" («tu mensaje sale después»). It does not: the refused send becomes a FAILED row with this text as its
 * reason, the only uses of the waiting-peer set never re-send a FAILED row, and nothing else re-sends one by
 * itself. The user has to tap the row's Retry button (chat_retry) once the history is read.
 *
 * Wanted, in English and in Spanish: no promise that the message goes by itself; the user is told to tap Retry,
 * with the button's own label; it still says nothing was sent and nothing was paid; and the Kotlin constant the
 * app reports is byte-equal to the English resource, so the translation lookup keeps finding it.
 */
class HeldSendNoticePromisesNoAutoSendTest {
    private val repoRoot: File by lazy {
        var dir: File? = File("").absoluteFile
        while (dir != null && !(File(dir, "gradle.properties").isFile && File(dir, "README.md").isFile)) {
            dir = dir.parentFile
        }
        assertNotNull(dir, "repo root not found above the test working dir")
    }

    private fun strings(dir: String): Map<String, String> =
        File(repoRoot, "ui-lib/src/main/res/ui")
            .listFiles()
            .orEmpty()
            .flatMap { module -> File(module, dir).listFiles { f -> f.extension == "xml" }.orEmpty().toList() }
            .fold(emptyMap()) { acc, f ->
                acc +
                    Regex("""<string name="([^"]+)"[^>]*>(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
                        .findAll(f.readText())
                        .associate { m -> m.groupValues[1] to m.groupValues[2].replace("\\'", "'") }
            }

    private val en by lazy { strings("values") }
    private val es by lazy { strings("values-es") }

    @Test
    fun the_constant_is_the_english_resource() {
        assertEquals(RestoreKexPolicy.STILL_READING_HISTORY, en[NAME], "constant and English resource differ")
    }

    @Test
    fun no_language_promises_the_message_goes_by_itself() {
        val english = assertNotNull(en[NAME])
        val spanish = assertNotNull(es[NAME])
        for (text in listOf(RestoreKexPolicy.STILL_READING_HISTORY, english)) {
            assertFalse("goes then" in text || "message goes" in text, "English promises an automatic send: $text")
        }
        assertFalse(
            "sale después" in spanish || "mensaje sale" in spanish,
            "Spanish promises an automatic send: $spanish",
        )
    }

    @Test
    fun both_languages_tell_the_user_to_tap_the_retry_button_by_its_label() {
        val retryEn = assertNotNull(en["chat_retry"], "English Retry label")
        val retryEs = assertNotNull(es["chat_retry"], "Spanish Retry label")
        assertEquals("Retry", retryEn, "fixture: the FAILED row's button label")
        assertEquals("Reintentar", retryEs, "fixture: the FAILED row's button label in Spanish")

        val english = assertNotNull(en[NAME])
        val spanish = assertNotNull(es[NAME])
        assertTrue("tap $retryEn" in english, "English must tell the user to tap $retryEn: $english")
        assertTrue("tap $retryEn" in RestoreKexPolicy.STILL_READING_HISTORY, "constant must say tap $retryEn")
        assertTrue("toca $retryEs" in spanish, "Spanish must tell the user to tap $retryEs: $spanish")
    }

    @Test
    fun control_both_languages_still_say_nothing_was_sent_or_paid() {
        val english = assertNotNull(en[NAME])
        val spanish = assertNotNull(es[NAME])
        assertTrue("Nothing was sent and nothing was paid" in english, english)
        assertTrue("history" in english, english)
        assertTrue("No se envió nada y no se pagó nada" in spanish, spanish)
        assertTrue("historial" in spanish, spanish)
    }

    private companion object {
        const val NAME = "chat_send_still_reading_history"
    }
}
