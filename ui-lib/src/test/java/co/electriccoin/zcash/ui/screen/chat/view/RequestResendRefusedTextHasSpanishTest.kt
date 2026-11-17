package co.electriccoin.zcash.ui.screen.chat.view

import co.electriccoin.zcash.ui.screen.chat.model.ChatSendCopy
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * ZC-421 (fix-all 2.13.0, train T6b; ZC-420-SPEC §14.10 app-texts, key decision (11); skill app-texts): the text a payment
 * request shows when it cannot be sent again as the same request (its relay key changed, it is 29 days old, the chat is
 * Shielded now, or a test-build row has no second and nothing is kept).
 *
 * At HEAD there is no such text: the Retry sends a second request. Wanted: `chat_send_request_resend_refused` in English
 * and Spanish, no placeholder; the English says it "can't be sent again" and that a second one could be "paid twice", and
 * never tells the user to "Tap Retry" (a Retry answers the same); ChatSendCopy.PAYMENT_REQUEST_RESEND_REFUSED is the English
 * text byte for byte and its map entry points at the key. The constant is reached by name (it does not exist at HEAD).
 */
class RequestResendRefusedTextHasSpanishTest {
    private val key = "chat_send_request_resend_refused"
    private val en by lazy { ChatResourceText.strings("values") }
    private val es by lazy { ChatResourceText.strings("values-es") }

    @Test
    fun `the text exists in English and Spanish with no placeholder`() {
        val english = en[key]
        val spanish = es[key]
        assertNotNull(english, "$key has no English text")
        assertNotNull(spanish, "$key has no Spanish text")
        assertFalse('%' in english || '%' in spanish, "the text must carry no placeholder")
    }

    @Test
    fun `the English says it cannot be sent again and could be paid twice, and never Tap Retry`() {
        val english = checkNotNull(en[key]) { "$key has no English text" }
        assertTrue("can't be sent again" in english, english)
        assertTrue("paid twice" in english, english)
        assertFalse("tap retry" in english.lowercase(), english)
    }

    @Test
    fun `ChatSendCopy holds the English text and maps it to the key`() {
        val constant =
            runCatching { ChatSendCopy::class.java.getField("PAYMENT_REQUEST_RESEND_REFUSED").get(null) as String }
                .getOrElse { throw AssertionError("ChatSendCopy.PAYMENT_REQUEST_RESEND_REFUSED does not exist") }
        assertEquals(en[key], constant)
        val id = ChatSendCopy.localizedResource(constant)
        assertNotNull(id, "ChatSendCopy's map does not know the text")
        assertEquals(key, ChatResourceText.stringName(id))
    }
}
