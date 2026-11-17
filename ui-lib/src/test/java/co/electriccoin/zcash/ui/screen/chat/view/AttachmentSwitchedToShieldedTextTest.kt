package co.electriccoin.zcash.ui.screen.chat.view

import co.electriccoin.zcash.ui.screen.chat.model.ChatSendCopy
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * T5b cross-review X-04 (SR-A; skill app-texts): the row reason of a photo, file or voice note whose chat was switched
 * to Shielded while it was uploading. Its link is not sent without the exact price question, so the row is FAILED and
 * says why: the switch, that nothing was paid, and that Retry shows the price.
 *
 * Wanted: `chat_attachment_switched_to_shielded` in chat_attachment_shielded_strings.xml, English and Spanish, no
 * placeholder; ChatSendCopy.ATTACHMENT_SWITCHED_TO_SHIELDED is the English byte for byte and maps to the key. The
 * constant is reached by name (it does not exist at f0ae66e8a).
 */
class AttachmentSwitchedToShieldedTextTest {
    private val key = "chat_attachment_switched_to_shielded"

    @Test
    fun `the reason exists in English and Spanish, names the switch and Retry, and says nothing was paid`() {
        val en = assertNotNull(ChatResourceText.strings("values")[key], "no English $key")
        val es = assertNotNull(ChatResourceText.strings("values-es")[key], "no Spanish $key")
        assertFalse('%' in en || '%' in es, "$key must carry no placeholder")
        assertTrue(en.startsWith("Not sent"), en)
        assertTrue(es.startsWith("No enviado"), es)
        assertTrue("Shielded" in en && "Shielded" in es, "the mode name is missing or translated: $en / $es")
        assertTrue("Nothing was paid" in en, en)
        assertTrue("No se pagó nada" in es, es)
        assertTrue("Retry" in en, en)
        assertTrue("Reintentar" in es, es)
        assertNotEquals(en, es, "the Spanish text is the English one")
    }

    @Test
    fun `ChatSendCopy holds the English text and maps it to its key`() {
        val constant =
            runCatching { ChatSendCopy::class.java.getField("ATTACHMENT_SWITCHED_TO_SHIELDED").get(null) as String }
                .getOrElse { throw AssertionError("ChatSendCopy.ATTACHMENT_SWITCHED_TO_SHIELDED does not exist") }
        assertEquals(ChatResourceText.strings("values")[key], constant)
        val id = assertNotNull(ChatSendCopy.localizedResource(constant), "ChatSendCopy's map does not know the constant")
        assertEquals(key, ChatResourceText.stringName(id))
    }
}
