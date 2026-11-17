package co.electriccoin.zcash.ui.screen.chat.view

import co.electriccoin.zcash.ui.screen.chat.model.ChatSendCopy
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * ZC-1395 (skill app-texts): the reason a queued first message stops when another account accepted its price. English
 * and Spanish, no placeholder, says nothing was paid and names Retry; the ChatSendCopy constant is the English byte for
 * byte and maps to its key.
 */
class OtherAccountAcceptedTextHasSpanishTest {
    private val en by lazy { ChatResourceText.strings("values") }
    private val es by lazy { ChatResourceText.strings("values-es") }

    private val key = "chat_send_failed_other_account_accepted"

    @Test
    fun `the reason exists in English and Spanish, says nothing was paid and names Retry`() {
        val english = assertNotNull(en[key], "$key has no English text")
        val spanish = assertNotNull(es[key], "$key has no Spanish text")
        assertFalse('%' in english || '%' in spanish, "$key must carry no placeholder")
        assertTrue(english.startsWith("Not sent") && "Nothing was paid" in english && "Retry" in english, english)
        assertTrue(spanish.startsWith("No enviado") && "No se pagó nada" in spanish && "Reintentar" in spanish, spanish)
    }

    @Test
    fun `ChatSendCopy holds the English text and maps it to its key`() {
        assertEquals(en[key], ChatSendCopy.FAILED_OTHER_ACCOUNT_ACCEPTED)
        val id = assertNotNull(ChatSendCopy.localizedResource(ChatSendCopy.FAILED_OTHER_ACCOUNT_ACCEPTED))
        assertEquals(key, ChatResourceText.stringName(id))
    }
}
