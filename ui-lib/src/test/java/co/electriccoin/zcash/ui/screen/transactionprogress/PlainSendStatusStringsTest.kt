package co.electriccoin.zcash.ui.screen.transactionprogress

import co.electriccoin.zcash.ui.screen.chat.view.ChatResourceText
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * F5 (fix-all 2.13.0, train T5; SPECS §1.3 W4, §1.5): the texts a plain Wallet send's verdict is shown with, on the
 * progress screen and on the transaction detail, exist in English and Spanish with the same placeholders, and promise
 * nothing the code does not do: nothing "keeps trying" or "will retry", and no "try again" (a send that may still be
 * mined is never an invitation to pay again). The stored-failure texts stay as they are. At 3df251d7f none of the new
 * keys exists.
 */
class PlainSendStatusStringsTest {
    private val keys =
        listOf(
            "send_confirmation_waiting_title",
            "send_confirmation_waiting_subtitle",
            "send_confirmation_not_sent_title",
            "send_confirmation_not_sent_subtitle",
            "transaction_detail_waiting_title",
            "transaction_detail_waiting_body",
            "transaction_detail_checking_body",
            "transaction_detail_not_sent_title",
            "transaction_detail_not_sent_body",
        )

    private val en = ChatResourceText.strings("values")
    private val es = ChatResourceText.strings("values-es")

    private fun placeholders(text: String): Set<String> = Regex("""%\d+\$[sd]""").findAll(text).map { it.value }.toSet()

    @Test
    fun every_new_text_exists_in_english_and_spanish_with_the_same_placeholders() {
        for (key in keys) {
            val english = assertNotNull(en[key], "no English $key")
            val spanish = assertNotNull(es[key], "no Spanish $key")
            assertNotEquals(english, spanish, "the Spanish $key is the English text")
            assertEquals(placeholders(english), placeholders(spanish), "the placeholders of $key differ")
        }
        for (key in listOf("send_confirmation_waiting_subtitle", "transaction_detail_waiting_body")) {
            assertEquals(setOf("%1\$s", "%2\$d"), placeholders(en.getValue(key)), "$key names the block and the minutes")
        }
    }

    @Test
    fun no_text_promises_a_retry_or_invites_paying_again_too_early() {
        for (key in keys) {
            val english = en[key].orEmpty().lowercase()
            val spanish = es[key].orEmpty().lowercase()
            for (phrase in listOf("keeps trying", "will retry", "retries", "try again", "automatically")) {
                assertFalse(phrase in english, "English $key says '$phrase': $english")
            }
            for (phrase in listOf("inténtalo", "reintent", "automáticamente")) {
                assertFalse(phrase in spanish, "Spanish $key says '$phrase': $spanish")
            }
        }
        assertTrue("nothing was paid" in en["send_confirmation_not_sent_subtitle"].orEmpty(), "Not Sent does not say nothing was paid")
        assertTrue("do not send it again" in en["send_confirmation_waiting_subtitle"].orEmpty().lowercase(), "Waiting does not warn")
    }

    @Test
    fun the_stored_failure_texts_are_unchanged() {
        assertEquals("Send Not Confirmed", en["send_confirmation_failure_stored_title"])
        assertEquals("Envío sin confirmar", es["send_confirmation_failure_stored_title"])
        assertTrue(
            en["send_confirmation_failure_stored_subtitle"].orEmpty().startsWith("The network reported an error, but your wallet"),
        )
        assertTrue(
            es["send_confirmation_failure_stored_subtitle"].orEmpty().startsWith("La red informó de un error, pero tu billetera"),
        )
    }
}
