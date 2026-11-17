package co.electriccoin.zcash.ui.screen.chat.view

import co.electriccoin.zcash.ui.screen.chat.model.ChatSendCopy
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Round 8 P3 (PHONE_TRIAGE F1, item 4) — a payment request bubble offers Pay in a chat that has no real address.
 *
 * The chat screen already knows whether its key is a real Zcash address (`isValidAddress` in ChatDetailView gates
 * the composer and shows "Cannot reply"). The payment request bubble (PaymentRequestContent) and its Pay button do
 * not look at it, so a request keyed "unknown" or by a sender hash shows "Pay …", and Pay hands that key to
 * fulfillPaymentRequest as the destination.
 *
 * Wanted: the chat passes `canPay = isValidAddress` through MessageBubble to PaymentRequestContent; with
 * `canPay` false the bubble shows no Pay button and says why (chat_request_cannot_pay, English and Spanish). The
 * same text is the notice fulfillPaymentRequest reports when it refuses (ChatSendCopy.PAYMENT_SENDER_UNKNOWN).
 *
 * A source test: the bubble is a private composable of a 5,000-line screen, and this pins the wiring, not pixels.
 */
class PayButtonNeedsTheSendersAddressTest {
    private companion object {
        const val DETAIL = "ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/chat/view/ChatDetailView.kt"
        const val DIALOGS = "ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/chat/view/ChatDialogs.kt"
        const val CANNOT_PAY_EN = "Can't pay: the sender's address is unknown."
        const val CANNOT_PAY_ES = "No se puede pagar: se desconoce la dirección del remitente."
    }

    @Test
    fun the_chat_tells_each_bubble_whether_its_peer_is_a_real_address() {
        val detail = ChatResourceText.source(DETAIL)
        val bubbleCall = callArguments(detail, "MessageBubble(")
        assertTrue(
            Regex("""\bcanPay\s*=\s*isValidAddress\b""").containsMatchIn(bubbleCall),
            "the chat does not pass canPay = isValidAddress to MessageBubble",
        )
        val bubble = functionText(detail, "private fun MessageBubble(")
        assertTrue(
            Regex("""\bcanPay\s*:\s*Boolean""").containsMatchIn(bubble),
            "MessageBubble has no canPay parameter",
        )
        val requestCall = callArguments(bubble, "PaymentRequestContent(")
        assertTrue(
            Regex("""\bcanPay\s*=\s*canPay\b""").containsMatchIn(requestCall),
            "MessageBubble does not pass canPay to PaymentRequestContent",
        )
    }

    @Test
    fun the_request_bubble_shows_no_pay_button_without_a_real_address_and_says_why() {
        val content = functionText(ChatResourceText.source(DIALOGS), "internal fun PaymentRequestContent(")
        assertTrue(
            Regex("""\bcanPay\s*:\s*Boolean""").containsMatchIn(content),
            "PaymentRequestContent has no canPay parameter",
        )
        val payBlock =
            Regex("""if\s*\(([^{]*)\)\s*\{[^}]*FilledTonalButton""", RegexOption.DOT_MATCHES_ALL).find(content)
        assertNotNull(payBlock, "fixture: the Pay button block was not found")
        assertTrue(
            Regex("""\bcanPay\b""").containsMatchIn(payBlock.groupValues[1]),
            "the Pay button is shown whether or not the chat has a real address: `${payBlock.groupValues[1].trim()}`",
        )
        assertTrue(
            "R.string.chat_request_cannot_pay" in content,
            "the bubble does not say why it cannot be paid",
        )
    }

    @Test
    fun the_cannot_pay_text_is_english_and_spanish_and_is_the_refusal_notice() {
        val en = ChatResourceText.strings("values")
        val es = ChatResourceText.strings("values-es")
        assertEquals(CANNOT_PAY_EN, en["chat_request_cannot_pay"], "English chat_request_cannot_pay")
        assertEquals(CANNOT_PAY_ES, es["chat_request_cannot_pay"], "Spanish chat_request_cannot_pay")
        val id = assertNotNull(ChatSendCopy.localizedResource(CANNOT_PAY_EN), "the refusal notice has no resource")
        assertEquals("chat_request_cannot_pay", ChatResourceText.stringName(id))
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────────────────────

    /** The text of the function whose declaration starts with [signature], up to its balanced closing brace. */
    private fun functionText(
        source: String,
        signature: String,
    ): String {
        val start = source.indexOf(signature)
        assertTrue(start >= 0, "fixture: `$signature` not found")
        val bodyStart = source.indexOf(") {", start).let { if (it < 0) error("no body") else it + 2 }
        return source.substring(start, balancedEnd(source, bodyStart, '{', '}'))
    }

    /** The argument list of the first call [call] (which ends in "("), parentheses balanced. */
    private fun callArguments(
        source: String,
        call: String,
    ): String {
        val start = source.indexOf(call)
        assertTrue(start >= 0, "fixture: `$call` not found")
        val open = start + call.length - 1
        return source.substring(open, balancedEnd(source, open, '(', ')'))
    }

    private fun balancedEnd(
        source: String,
        openIndex: Int,
        open: Char,
        close: Char,
    ): Int {
        var depth = 0
        for (i in openIndex until source.length) {
            when (source[i]) {
                open -> depth++
                close -> {
                    depth--
                    if (depth == 0) return i + 1
                }
            }
        }
        error("unbalanced $open$close")
    }
}
