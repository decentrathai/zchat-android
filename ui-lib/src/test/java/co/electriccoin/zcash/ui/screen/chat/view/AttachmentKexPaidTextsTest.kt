package co.electriccoin.zcash.ui.screen.chat.view

import co.electriccoin.zcash.ui.screen.chat.model.ChatSendCopy
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * T5b cross-review X-06 (SR-A; skill app-texts, "status comes from what was paid"): a Shielded photo, file or voice
 * note whose owed key exchange was paid and whose link was then refused (the transaction was not the quoted one). At
 * f0ae66e8a the row says "Nothing was paid" and its Retry question "The earlier try was not paid"; both are false.
 *
 * Wanted, English and Spanish, no placeholder:
 * - `chat_attachment_price_changed_kex_paid` (chat_attachment_shielded_strings.xml): not sent, the key exchange was
 *   paid, the attachment was not, Retry shows the new price; never "Nothing was paid".
 *   ChatSendCopy.ATTACHMENT_PRICE_CHANGED_KEX_PAID is the English byte for byte and maps to the key.
 * - `paid_action_shielded_attachment_retry_kex_paid` (chat_money_strings.xml): the Retry question of such a row; it says
 *   the earlier try paid for the key exchange only, never "was not paid", and keeps "never through the relays".
 * The constant is reached by name (it does not exist at f0ae66e8a).
 */
class AttachmentKexPaidTextsTest {
    private val en by lazy { ChatResourceText.strings("values") }
    private val es by lazy { ChatResourceText.strings("values-es") }
    private val rowKey = "chat_attachment_price_changed_kex_paid"
    private val retryKey = "paid_action_shielded_attachment_retry_kex_paid"

    @Test
    fun `the row reason says the key exchange was paid and never that nothing was paid`() {
        val english = assertNotNull(en[rowKey], "no English $rowKey")
        val spanish = assertNotNull(es[rowKey], "no Spanish $rowKey")
        assertFalse('%' in english || '%' in spanish, "$rowKey must carry no placeholder")
        assertTrue(english.startsWith("Not sent"), english)
        assertTrue(spanish.startsWith("No enviado"), spanish)
        assertFalse("Nothing was paid" in english, english)
        assertFalse("No se pagó nada" in spanish, spanish)
        assertTrue("key exchange" in english && "paid" in english, english)
        assertTrue("intercambio de claves" in spanish && "pagó" in spanish, spanish)
        assertTrue("Retry" in english, english)
        assertTrue("Reintentar" in spanish, spanish)
        assertNotEquals(english, spanish, "the Spanish text is the English one")
    }

    @Test
    fun `the Retry question says the earlier try paid for the key exchange only`() {
        val english = assertNotNull(en[retryKey], "no English $retryKey")
        val spanish = assertNotNull(es[retryKey], "no Spanish $retryKey")
        assertFalse('%' in english || '%' in spanish, "$retryKey must carry no placeholder")
        assertFalse("was not paid" in english, english)
        assertFalse("no se pagó" in spanish, spanish)
        assertTrue("key exchange" in english, english)
        assertTrue("intercambio de claves" in spanish, spanish)
        assertTrue("never through the relays" in english, english)
        assertTrue("nunca por los relays" in spanish, spanish)
        assertNotEquals(english, spanish, "the Spanish text is the English one")
    }

    @Test
    fun `ChatSendCopy holds the English row reason and maps it to its key`() {
        val constant =
            runCatching { ChatSendCopy::class.java.getField("ATTACHMENT_PRICE_CHANGED_KEX_PAID").get(null) as String }
                .getOrElse { throw AssertionError("ChatSendCopy.ATTACHMENT_PRICE_CHANGED_KEX_PAID does not exist") }
        assertEquals(en[rowKey], constant)
        val id = assertNotNull(ChatSendCopy.localizedResource(constant), "ChatSendCopy's map does not know the constant")
        assertEquals(rowKey, ChatResourceText.stringName(id))
    }
}
