package co.electriccoin.zcash.ui.screen.chat.model

import co.electriccoin.zcash.ui.R
import co.electriccoin.zcash.ui.screen.chat.util.toZchatUserMessage
import co.electriccoin.zcash.ui.screen.chat.view.ChatResourceText
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * ZC-1949 (Fable review of fix/2130-msg 4efd9a54f, 16 Nov 2026; register low) — ZC-1909 added the row text
 * ChatSendCopy.FAILED_SHORT_SETUP_PAID (the setup step of this chat was paid; the wallet cannot pay the message now) and
 * its resource chat_send_failed_short_setup_paid, and no test named either. Pinned here: the English resource is the
 * constant, the Spanish one exists, ChatSendCopy maps the constant to the resource (a Spanish phone finds it), the toast
 * path keeps it, and it never says "Add ZEC" or that nothing was paid (money-path rule 4).
 */
class SetupPaidShortfallTextIsMappedTest {
    private val en by lazy { ChatResourceText.strings("values") }
    private val es by lazy { ChatResourceText.strings("values-es") }

    @Test
    fun `the setup-paid shortfall text is a mapped constant with English and Spanish resources`() {
        val constant = ChatSendCopy.FAILED_SHORT_SETUP_PAID
        assertEquals(constant, en["chat_send_failed_short_setup_paid"], "the English resource must be the constant")
        assertTrue(!es["chat_send_failed_short_setup_paid"].isNullOrBlank(), "the Spanish resource is missing")
        assertEquals(R.string.chat_send_failed_short_setup_paid, ChatSendCopy.localizedResource(constant))
        assertEquals(constant, constant.toZchatUserMessage("fallback"), "the toast path must not replace the text")
    }

    @Test
    fun `the setup-paid shortfall text never says Add ZEC or that nothing was paid`() {
        for (text in listOfNotNull(ChatSendCopy.FAILED_SHORT_SETUP_PAID, en["chat_send_failed_short_setup_paid"])) {
            assertFalse(text.contains("Add ZEC", ignoreCase = true), text)
            assertFalse(text.contains("Nothing was paid", ignoreCase = true), text)
            assertTrue(text.contains("went out"), "the text does not say the setup went out: $text")
        }
    }
}
