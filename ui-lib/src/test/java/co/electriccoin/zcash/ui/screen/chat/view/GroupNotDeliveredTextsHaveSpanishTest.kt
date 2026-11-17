package co.electriccoin.zcash.ui.screen.chat.view

import co.electriccoin.zcash.ui.screen.chat.viewmodel.LedgerSources
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * F8-G G8 (ZC-112 part 2; app-texts rules 1, 2 and 9) — the two member-row badges of Group Settings exist in English
 * and in Spanish.
 *
 * "Key not delivered" and "Message not delivered" say only what the code proved: the member's key message, or its copy
 * of a group message, never reached the chain. They promise no automatic re-send (there is none). The button beside
 * each is the existing Resend ("Reenviar"), and the invite badge is the existing "Invite failed". Neither new text has
 * a placeholder.
 */
class GroupNotDeliveredTextsHaveSpanishTest {
    private val file = "group_screen_strings.xml"

    @Test
    fun `the two badges exist in English and Spanish, with no placeholder`() {
        assertEquals("Key not delivered", LedgerSources.string("values", file, "group_member_key_not_delivered_status"))
        assertEquals("Message not delivered", LedgerSources.string("values", file, "group_member_message_not_delivered_status"))
        val keyEs = LedgerSources.string("values-es", file, "group_member_key_not_delivered_status")
        val messageEs = LedgerSources.string("values-es", file, "group_member_message_not_delivered_status")
        assertEquals("Clave no entregada", keyEs)
        assertEquals("Mensaje no entregado", messageEs)
        for (text in listOf(keyEs, messageEs)) assertTrue("a badge has a placeholder: $text", '%' !in text)
    }

    @Test
    fun `the button and the invite badge are the existing texts`() {
        assertEquals("Resend", LedgerSources.string("values", file, "group_resend"))
        assertEquals("Reenviar", LedgerSources.string("values-es", file, "group_resend"))
        assertEquals("Invite failed", LedgerSources.string("values", file, "group_invite_failed_status"))
        assertTrue(LedgerSources.string("values-es", file, "group_invite_failed_status").isNotBlank())
    }

    @Test
    fun `neither badge promises a re-send the app does not make`() {
        for (folder in listOf("values", "values-es")) {
            for (key in listOf("group_member_key_not_delivered_status", "group_member_message_not_delivered_status")) {
                val text = LedgerSources.string(folder, file, key).lowercase()
                for (promise in listOf("retry", "again", "automatic", "reintent", "de nuevo", "automátic")) {
                    assertTrue("`$key` ($folder) promises \"$promise\": $text", promise !in text)
                }
            }
        }
    }
}
