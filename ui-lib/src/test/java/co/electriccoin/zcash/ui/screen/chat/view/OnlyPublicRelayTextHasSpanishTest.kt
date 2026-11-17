package co.electriccoin.zcash.ui.screen.chat.view

import co.electriccoin.zcash.ui.screen.chat.model.ChatSendCopy
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * ZC-393 (fix-all 2.13.0, train T5b; t5b-add SPECS §2.5; skill app-texts): the reason above Retry on a message only a
 * public relay took.
 *
 * At HEAD such a message shows SENT (or, with no relay at all, "Not sent — no relay accepted it", which would be false
 * here: a public relay did). Wanted: `chat_send_failed_only_public_relay` in English and Spanish, no placeholder; the
 * English names the version that cannot read that relay ("2.12.4") and the action ("Retry") and does not say no relay
 * accepted it; the Spanish names "2.12.4" and "Reintentar"; ChatSendCopy.FAILED_ONLY_PUBLIC_RELAY is the English text
 * byte for byte and its map entry points at the key. The constant is reached by name (it does not exist at HEAD).
 */
class OnlyPublicRelayTextHasSpanishTest {
    private val key = "chat_send_failed_only_public_relay"
    private val en by lazy { ChatResourceText.strings("values") }
    private val es by lazy { ChatResourceText.strings("values-es") }

    @Test
    fun `the reason exists in English and Spanish with no placeholder`() {
        val english = en[key]
        val spanish = es[key]
        assertNotNull(english, "$key has no English text")
        assertNotNull(spanish, "$key has no Spanish text")
        assertFalse('%' in english || '%' in spanish, "the reason must carry no placeholder")
    }

    @Test
    fun `the English names the version and Retry and never says no relay accepted it`() {
        val english = checkNotNull(en[key]) { "$key has no English text" }
        assertTrue("2.12.4" in english, english)
        assertTrue("Retry" in english, english)
        assertFalse("no relay accepted" in english.lowercase(), english)
        assertFalse("sent" in english.lowercase().replace("not sent", ""), "it must not read as sent: $english")
    }

    @Test
    fun `the Spanish names the version and Reintentar`() {
        val spanish = checkNotNull(es[key]) { "$key has no Spanish text" }
        assertTrue("2.12.4" in spanish, spanish)
        assertTrue("Reintentar" in spanish, spanish)
    }

    @Test
    fun `ChatSendCopy holds the English text and maps it to the key`() {
        val constant =
            runCatching { ChatSendCopy::class.java.getField("FAILED_ONLY_PUBLIC_RELAY").get(null) as String }
                .getOrElse { throw AssertionError("ChatSendCopy.FAILED_ONLY_PUBLIC_RELAY does not exist") }
        assertEquals(en[key], constant)
        val id = ChatSendCopy.localizedResource(constant)
        assertNotNull(id, "ChatSendCopy's map does not know the reason")
        assertEquals(key, ChatResourceText.stringName(id))
    }
}
