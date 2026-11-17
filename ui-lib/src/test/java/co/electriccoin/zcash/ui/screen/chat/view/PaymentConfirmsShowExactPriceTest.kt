package co.electriccoin.zcash.ui.screen.chat.view

import org.junit.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * D-6 (fix-all 2.13.0, train T5b; t5b SPECS §5, owner W-10) — the three payment confirms quote a floor:
 * - the Send Payment dialog (ChatDialogs.kt `PaymentDialog`) shows "+ about … ZEC in fees" from `paymentFees` (formatted
 *   with a DecimalFormat) and enables Send by the value-based `canAffordPayment` alone;
 * - the pay-request confirm (ChatDetailView `pendingPayRequest?.let`) shows "at least" `PaidActionCopy.payRequestTotal`;
 * - the Welcome ZEC confirm (ChatDetailView `if (showWelcomeZecConfirm)`) shows "at least" `WelcomeZec.outlayFloor()`.
 * Pay is enabled before any price is known, and nothing reaches the send that would let it refuse a proposal whose fee
 * is not the one shown.
 *
 * Wanted (read from the source, comments stripped, as WelcomeZecQuotesWhatLeavesTheWalletTest does):
 * - no confirm computes a floor (`payRequestTotal(`, `outlayFloor()`, `paymentFees(`, the old `send_payment_dialog_fees`);
 * - each asks for the exact quote (`onQuotePayment(` in ChatDetailView, `quotePayment(` in the dialog, which re-prices
 *   about 400 ms after the amount or memo stops changing, for the current amount and memo only);
 * - Pay / Send is enabled only by a `PaymentQuote.Exact` and hands that quote to its callback (`<exact>.quote`);
 * - the bodies name the exact total, the platform fee and the network fee of that quote; the dialog's fee line is
 *   `send_payment_dialog_fees_exact` with `PaidActionCopy.zec` (no DecimalFormat for money);
 * - the screen shows the other answers (working, not enough, still confirming, unavailable);
 * - AndroidChat passes the quote on to `sendPayment` / `fulfillPaymentRequest` and asks `viewModel.quotePayment`.
 */
class PaymentConfirmsShowExactPriceTest {
    private val chatDetail: String by lazy { stripComments(ChatResourceText.source("$VIEW/ChatDetailView.kt")) }
    private val chatDialogs: String by lazy { stripComments(ChatResourceText.source("$VIEW/ChatDialogs.kt")) }
    private val androidChat: String by lazy {
        stripComments(ChatResourceText.source("ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/chat/AndroidChat.kt"))
    }

    /** PaymentDialog, from its declaration to the time-lock dialog's banner. */
    private val paymentDialog: String by lazy {
        val raw = ChatResourceText.source("$VIEW/ChatDialogs.kt")
        val start = raw.indexOf("internal fun PaymentDialog(")
        val end = raw.indexOf("// TIME-LOCK COMPOSER DIALOG")
        if (start < 0 || end <= start) fail("fixture: PaymentDialog and the time-lock banner in ChatDialogs.kt")
        stripComments(raw.substring(start, end))
    }
    private val payRequest: String by lazy { braceBlock(chatDetail, "pendingPayRequest?.let") }
    private val welcome: String by lazy { braceBlock(chatDetail, "if (showWelcomeZecConfirm)") }

    private val confirms: List<Pair<String, String>> by lazy {
        listOf("the Send Payment dialog" to paymentDialog, "the pay-request confirm" to payRequest, "the Welcome confirm" to welcome)
    }

    @Test
    fun no_confirm_quotes_a_floor() {
        for ((name, block) in confirms) {
            for (floor in FLOORS) {
                assertTrue(floor !in block, "$name still quotes a floor ($floor):\n$block")
            }
        }
    }

    @Test
    fun each_confirm_asks_for_the_exact_quote() {
        assertTrue("onQuotePayment(" in payRequest, "the pay-request confirm asks for no quote:\n$payRequest")
        assertTrue("onQuotePayment(" in welcome, "the Welcome confirm asks for no quote:\n$welcome")
        assertTrue("quotePayment(" in paymentDialog, "the Send Payment dialog asks for no quote")
        // Re-priced about 400 ms after the amount or the memo stops changing.
        assertTrue("LaunchedEffect(" in paymentDialog && "delay(PAYMENT_QUOTE_DEBOUNCE_MS)" in paymentDialog, "no debounced re-price")
        assertTrue(Regex("""PAYMENT_QUOTE_DEBOUNCE_MS\s*=\s*400L""").containsMatchIn(chatDialogs), "the debounce is not 400 ms")
        // A quote counts only for the amount and memo it priced.
        assertTrue("val quoteKey = amountZatoshi.value to memo" in paymentDialog, "the dialog does not key its quote by amount and memo")
        assertTrue(
            Regex("""takeIf\s*\{\s*it\.first\s*==\s*quoteKey\s*}""").containsMatchIn(paymentDialog),
            "the dialog may use a quote of an earlier amount or memo",
        )
    }

    @Test
    fun pay_is_enabled_only_by_an_exact_quote_and_hands_it_on() {
        val calls =
            mapOf(
                "the Send Payment dialog" to { exact: String -> Regex("""onSendPayment\(amountZec, memo, $exact\.quote\)""") },
                "the pay-request confirm" to { exact: String ->
                    Regex("""onFulfillPaymentRequest\(payAmountZatoshi, payRequestId, $exact\.quote\)""")
                },
                "the Welcome confirm" to { exact: String -> Regex("""onSendWelcomeZec\?\.invoke\($exact\.quote\)""") },
            )
        for ((name, block) in confirms) {
            val exact =
                Regex("""val (\w+) = [^\n]*as\? PaymentQuote\.Exact""").find(block)?.groupValues?.get(1)
                    ?: fail("$name reads no PaymentQuote.Exact:\n$block")
            val confirm = braceBlock(block, "confirmButton = {")
            val enabled =
                Regex("""enabled\s*=\s*([^\n]+)""").find(confirm)?.groupValues?.get(1)
                    ?: fail("$name: Pay has no enabled:\n$confirm")
            assertTrue(Regex("""\b$exact\b""").containsMatchIn(enabled), "$name: Pay is enabled without an exact quote: enabled = $enabled")
            assertTrue(calls.getValue(name)(exact).containsMatchIn(confirm), "$name does not hand its exact quote to the send:\n$confirm")
        }
    }

    @Test
    fun the_bodies_and_the_fee_line_name_the_exact_figures() {
        for ((name, block, key) in listOf(
            Triple("the pay-request confirm", payRequest, "pay_request_confirm_body"),
            Triple("the Welcome confirm", welcome, "welcome_zec_confirm_body"),
        )) {
            val exact = Regex("""val (\w+) = [^\n]*as\? PaymentQuote\.Exact""").find(block)?.groupValues?.get(1) ?: fail(name)
            val call = block.substring(block.indexOf("R.string.$key").also { if (it < 0) fail("$name does not show $key") })
            for (figure in listOf("total", "platformFee", "networkFee")) {
                assertTrue(
                    Regex("""PaidActionCopy\.zec\(\s*$exact\.$figure\s*,""").containsMatchIn(call.take(BODY_CALL)),
                    "$name's body does not show the quote's $figure with PaidActionCopy.zec:\n${call.take(BODY_CALL)}",
                )
            }
        }
        val fees = paymentDialog.indexOf("R.string.send_payment_dialog_fees_exact")
        assertTrue(fees >= 0, "the Send Payment dialog does not show send_payment_dialog_fees_exact")
        val feeLine = paymentDialog.substring(fees, minOf(paymentDialog.length, fees + BODY_CALL))
        assertTrue("PaidActionCopy.zec(" in feeLine, "the fee line is not formatted with PaidActionCopy.zec:\n$feeLine")
        assertTrue("Format.format(" !in feeLine, "the fee line still uses a DecimalFormat:\n$feeLine")
    }

    @Test
    fun the_screen_says_why_there_is_no_exact_price() {
        val both = chatDetail + chatDialogs
        for (key in STATUS_KEYS) {
            assertTrue("R.string.$key" in both, "no confirm shows $key")
        }
        for ((name, block) in confirms) {
            assertTrue("PaymentQuoteStatus(" in block, "$name does not show the answer when there is no exact price")
        }
    }

    @Test
    fun android_chat_passes_the_quote_to_the_send() {
        assertTrue("viewModel.quotePayment(peerAddress" in androidChat, "AndroidChat asks the view model for no quote")
        for (name in listOf("sendPayment", "fulfillPaymentRequest")) {
            val calls = Regex("""viewModel\.$name\(([^)]*)\)""").findAll(androidChat).map { it.groupValues[1] }.toList()
            assertTrue(calls.isNotEmpty(), "AndroidChat does not call $name")
            for (args in calls) {
                assertTrue(
                    args.split(',').map { it.trim() }.let { it.size == 4 && it.last() == "quote" },
                    "AndroidChat calls $name without the confirm's quote: $name($args)",
                )
            }
        }
    }

    private companion object {
        const val VIEW = "ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/chat/view"
        const val BODY_CALL = 700
        val FLOORS =
            listOf(
                "payRequestTotal(",
                "outlayFloor()",
                "paymentFees(",
                "R.string.send_payment_dialog_fees,",
                "R.string.send_payment_dialog_fees)",
            )
        val STATUS_KEYS =
            listOf("payment_quote_working", "payment_quote_unavailable", "payment_quote_not_enough", "chat_send_pending_balance_wait")

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
            if (start < 0) fail("\"$anchor\" not found")
            val open = source.indexOf('{', start)
            var depth = 0
            for (i in open until source.length) {
                when (source[i]) {
                    '{' -> depth++
                    '}' -> if (--depth == 0) return source.substring(start, i + 1)
                }
            }
            fail("unbalanced braces after \"$anchor\"")
        }
    }
}
