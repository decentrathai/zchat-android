package co.electriccoin.zcash.ui.screen.chat.view

import co.electriccoin.zcash.ui.screen.chat.viewmodel.LedgerSources
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * F8 (ZC-112) — the notice and the Retry's question exist in English and Spanish, say what the code did (the
 * transaction never reached the chain, nothing was paid, the price is shown before anything is paid), and never
 * promise an automatic retry: nothing retries by itself.
 */
class HandshakeNotSentStringsHaveSpanishTest {
    private val keys = listOf("chat_handshake_not_sent_short", "chat_handshake_not_sent_full", "paid_action_handshake_retry")

    @Test
    fun `every key has an English and a Spanish text, with no placeholder`() {
        for (key in keys) {
            val en = LedgerSources.string("values", "chat_money_strings.xml", key)
            val es = LedgerSources.string("values-es", "chat_money_strings.xml", key)
            assertTrue("$key (EN) is empty", en.isNotBlank())
            assertTrue("$key (ES) is empty or untranslated", es.isNotBlank() && es != en)
            assertTrue("$key takes no argument", !en.contains("%") && !es.contains("%"))
        }
    }

    @Test
    fun `the texts are the agreed ones and promise nothing automatic`() {
        assertEquals("Key exchange not sent", LedgerSources.string("values", "chat_money_strings.xml", "chat_handshake_not_sent_short"))
        assertEquals(
            "Intercambio de claves no enviado",
            LedgerSources.string("values-es", "chat_money_strings.xml", "chat_handshake_not_sent_short"),
        )
        val en = LedgerSources.string("values", "chat_money_strings.xml", "chat_handshake_not_sent_full")
        val es = LedgerSources.string("values-es", "chat_money_strings.xml", "chat_handshake_not_sent_full")
        assertTrue(en.contains("nothing was paid") && en.contains("Tap Retry") && en.contains("price is shown before anything is paid"))
        assertTrue(es.contains("no se pagó nada") && es.contains("Reintentar") && es.contains("el precio se muestra antes de pagar"))
        for (text in listOf(en, es)) {
            assertTrue(
                "no promise of an automatic retry: $text",
                !text.contains("automatic", ignoreCase = true) && !text.contains("automátic", ignoreCase = true),
            )
        }
        assertEquals(
            "the label the notice names is the shipped one",
            "Retry",
            LedgerSources.string("values", "chat_send_strings.xml", "chat_retry"),
        )
        assertEquals("Reintentar", LedgerSources.string("values-es", "chat_send_strings.xml", "chat_retry"))
    }
}
