package co.electriccoin.zcash.ui.screen.chat.view

import co.electriccoin.zcash.ui.screen.chat.viewmodel.LedgerSources
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * F8 (ZC-112) — the note of a locked message that never reached the chain exists in English and Spanish, says that it
 * did not go out and that nothing was paid, and offers no resend the app does not have.
 */
class LockedMessageStringsHaveSpanishTest {
    @Test
    fun `the note exists in both languages and says only what happened`() {
        val en = LedgerSources.string("values", "chat_surface_strings.xml", "chat_note_locked_message_not_sent")
        val es = LedgerSources.string("values-es", "chat_surface_strings.xml", "chat_note_locked_message_not_sent")
        assertTrue(en.contains("did not go out") && en.contains("never reached the Zcash blockchain") && en.contains("nothing was paid"))
        assertTrue(es.contains("no salió") && es.contains("nunca llegó a la cadena de bloques de Zcash") && es.contains("no se pagó nada"))
        assertTrue("it takes no argument (no message text is kept)", !en.contains("%") && !es.contains("%"))
        for (text in listOf(en, es)) {
            assertTrue(
                "no Retry or Resend is promised: $text",
                !text.contains("Retry") && !text.contains("Reintentar") && !text.contains("Resend"),
            )
        }
    }
}
