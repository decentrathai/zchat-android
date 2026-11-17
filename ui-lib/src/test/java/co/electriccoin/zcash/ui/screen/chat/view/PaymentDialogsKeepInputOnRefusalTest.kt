package co.electriccoin.zcash.ui.screen.chat.view

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * ZC-250 (fix-all 2.13.0, train T5b; t5b SPECS §6 items 3 and 4) — a refused payment request or payment loses what the
 * user typed.
 *
 * At 3ff26bc9a the Request Payment dialog's Send (ChatDetailView `if (showPaymentRequestDialog)`) calls
 * onSendPaymentRequest, typed `(Long, String) -> Unit`, and then closes the dialog whatever happened; AndroidChat drops
 * the Boolean sendPaymentRequest answers. In a key-changed chat (HONOR, 30 Sep) the request is refused and the typed
 * amount and reason are gone. The Send Payment dialog has the same shape: onSendPayment is `-> Unit`, sendPayment
 * answers nothing, and the dialog closes on every Send.
 *
 * Wanted (read from the source, comments stripped):
 * - both callbacks answer Boolean, in both ChatDetailView signatures;
 * - each dialog closes only inside `if (<the callback's answer>)`, never unconditionally after Send;
 * - AndroidChat hands back the view model's answer (sendPaymentRequest's, and sendPayment's, which now answers Boolean).
 */
class PaymentDialogsKeepInputOnRefusalTest {
    private val chatDetail: String by lazy { SourceScan.stripComments(ChatResourceText.source("$VIEW/ChatDetailView.kt")) }
    private val androidChat: String by lazy {
        SourceScan.stripComments(ChatResourceText.source("ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/chat/AndroidChat.kt"))
    }
    private val chatViewModel: String by lazy {
        SourceScan.stripComments(
            ChatResourceText.source("ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/chat/viewmodel/ChatViewModel.kt"),
        )
    }

    @Test
    fun the_request_and_payment_callbacks_answer_boolean() {
        val request = Regex("""onSendPaymentRequest:\s*\(amountZatoshi: Long, reason: String\)\s*->\s*Boolean""")
        val payment = Regex("""onSendPayment:\s*\(amountZec: Double, memo: String, quote: ChatSendQuote\)\s*->\s*Boolean""")
        assertEquals(2, request.findAll(chatDetail).count(), "onSendPaymentRequest must answer Boolean in both ChatDetailView signatures")
        assertEquals(2, payment.findAll(chatDetail).count(), "onSendPayment must answer Boolean in both ChatDetailView signatures")
    }

    @Test
    fun the_request_dialog_closes_only_when_the_request_was_taken() {
        val dialog = SourceScan.braceBlock(chatDetail, "if (showPaymentRequestDialog)")
        val send = SourceScan.braceBlock(dialog, "onSendRequest =")
        assertClosesOnlyOnTrue(
            send,
            "if (onSendPaymentRequest(amountZatoshi, reason))",
            "showPaymentRequestDialog = false",
            "Request Payment",
        )
    }

    @Test
    fun the_payment_dialog_closes_only_when_the_payment_was_taken() {
        val dialog = SourceScan.braceBlock(chatDetail, "if (showPaymentDialog)")
        val send = SourceScan.braceBlock(dialog, "onSendPayment =")
        assertClosesOnlyOnTrue(send, "if (onSendPayment(amount, memo, quote))", "showPaymentDialog = false", "Send Payment")
    }

    @Test
    fun android_chat_hands_back_the_view_model_answer() {
        assertTrue(
            Regex(
                """onSendPaymentRequest = \{ amountZatoshi, reason ->\s*""" +
                    """viewModel\.sendPaymentRequest\(peerAddress, amountZatoshi, reason\)\s*}""",
            ).containsMatchIn(androidChat),
            "AndroidChat must answer sendPaymentRequest's result",
        )
        assertTrue(
            Regex("""onSendPayment = \{ amountZec, memo, quote ->\s*viewModel\.sendPayment\(peerAddress, amountZec, memo, quote\)\s*}""")
                .containsMatchIn(androidChat),
            "AndroidChat must answer sendPayment's result",
        )
        assertTrue(
            Regex(
                """fun sendPayment\(\s*peerAddress: String,\s*amountZec: Double,\s*memo: String = "",\s*""" +
                    """quote: ChatSendQuote\? = null,\s*\)\s*:\s*Boolean""",
            ).containsMatchIn(chatViewModel),
            "sendPayment must answer Boolean (false when refused before anything was built)",
        )
    }

    private fun assertClosesOnlyOnTrue(
        send: String,
        guard: String,
        close: String,
        name: String,
    ) {
        val at = send.indexOf(guard)
        assertTrue(at >= 0, "the $name dialog's Send does not ask whether it was taken ($guard):\n$send")
        val taken = SourceScan.braceBlock(send.substring(at), guard)
        assertTrue(close in taken, "the $name dialog does not close when it was taken:\n$send")
        val outside = send.removeRange(at, at + taken.length)
        assertFalse(close in outside, "the $name dialog still closes when its Send was refused:\n$send")
    }

    private companion object {
        const val VIEW = "ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/chat/view"
    }
}

/** Kotlin source helpers for the ZC-250 source scans (as PaymentConfirmsShowExactPriceTest's). */
internal object SourceScan {
    /** Kotlin source without line and (nested) block comments; string and char literals are kept whole. */
    fun stripComments(source: String): String {
        val out = StringBuilder(source.length)
        var i = 0
        val n = source.length
        while (i < n) {
            val c = source[i]
            when {
                source.startsWith("\"\"\"", i) -> {
                    val end = source.indexOf("\"\"\"", i + 3).let { if (it < 0) n else it + 3 }
                    out.append(source, i, end)
                    i = end
                }
                c == '"' || c == '\'' -> {
                    var j = i + 1
                    while (j < n && source[j] != c && source[j] != '\n') j += if (source[j] == '\\') 2 else 1
                    val end = minOf(j + 1, n)
                    out.append(source, i, end)
                    i = end
                }
                source.startsWith("//", i) -> {
                    while (i < n && source[i] != '\n') i++
                }
                source.startsWith("/*", i) -> {
                    var depth = 0
                    while (i < n) {
                        if (source.startsWith("/*", i)) {
                            depth++
                            i += 2
                        } else if (source.startsWith("*/", i)) {
                            depth--
                            i += 2
                            if (depth == 0) break
                        } else {
                            i++
                        }
                    }
                }
                else -> {
                    out.append(c)
                    i++
                }
            }
        }
        return out.toString()
    }

    /** From [anchor] to the brace that closes the first `{` after it. */
    fun braceBlock(
        source: String,
        anchor: String,
    ): String {
        val start = source.indexOf(anchor)
        if (start < 0) throw AssertionError("\"$anchor\" not found")
        val open = source.indexOf('{', start)
        var depth = 0
        for (i in open until source.length) {
            when (source[i]) {
                '{' -> depth++
                '}' -> if (--depth == 0) return source.substring(start, i + 1)
            }
        }
        throw AssertionError("unbalanced braces after \"$anchor\"")
    }
}
