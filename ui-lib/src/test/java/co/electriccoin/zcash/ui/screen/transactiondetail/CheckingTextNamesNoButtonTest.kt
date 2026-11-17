package co.electriccoin.zcash.ui.screen.transactiondetail

import co.electriccoin.zcash.ui.screen.chat.view.ChatResourceText
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Checking copy warns against another payment without assuming which button exists. */
class CheckingTextNamesNoButtonTest {
    @Test
    fun english_and_spanish_checking_copy_warn_without_naming_buttons() {
        val en = ChatResourceText.strings("values")
        val es = ChatResourceText.strings("values-es")
        val english = assertNotNull(en["transaction_detail_checking_body"])
        val spanish = assertNotNull(es["transaction_detail_checking_body"])
        assertTrue("do not send it again" in english.lowercase())
        assertTrue("no la envíes de nuevo" in spanish.lowercase())
        for (copy in listOf(english, spanish)) {
            for (button in listOf("Send again", "Enviar de nuevo", "Save address")) {
                assertFalse(button in copy, "checking copy names '$button'")
            }
        }
        for (phrase in listOf("keeps trying", "will retry", "retries", "try again", "automatically")) {
            assertFalse(phrase in english.lowercase(), "English checking copy promises '$phrase'")
        }
        for (phrase in listOf("inténtalo", "reintent", "automáticamente")) {
            assertFalse(phrase in spanish.lowercase(), "Spanish checking copy promises '$phrase'")
        }
        val placeholders = Regex("""%\d+\$[sd]""")
        assertEquals(placeholders.findAll(english).map { it.value }.toList(), placeholders.findAll(spanish).map { it.value }.toList())
        val expiredEnglish = assertNotNull(en["transaction_detail_expired_checking_title"])
        val expiredSpanish = assertNotNull(es["transaction_detail_expired_checking_title"])
        assertNotEquals(expiredEnglish, expiredSpanish)
        assertEquals("Expired — still checking", expiredEnglish)
        assertEquals("Caducada — aún comprobando", expiredSpanish)
    }
}
