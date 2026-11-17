package co.electriccoin.zcash.ui.screen.chat.view

import cash.z.ecc.android.sdk.ext.convertZecToZatoshi
import co.electriccoin.zcash.ui.design.util.AmountReadingProbe
import co.electriccoin.zcash.ui.design.util.AmountReadingProbe.choose
import co.electriccoin.zcash.ui.design.util.AmountReadingProbe.edit
import co.electriccoin.zcash.ui.design.util.AmountReadingProbe.emptyField
import org.junit.Test
import java.math.BigDecimal
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * F1 (fix-all 2.13.0, train T1): a pasted "1,000" in chat Send Payment asks "1 ZEC or 1000 ZEC?" instead of paying 1.
 *
 * This replaces PastedThousandPaysOneZecTest (Round 8 D8), which pinned that the chat fields read every comma as the
 * decimal mark, so a pasted "1,000" was 1 ZEC with no question. On the same phone main Send read it as 1000 ZEC, and a
 * Spanish phone read a pasted "1.500" (an English 1.5) as 1500 on main Send. F1 makes every money field ask when a
 * paste reads two ways, smaller reading first and nothing preselected.
 *
 * PaymentDialog (ChatDialogs.kt) is Compose, so this pins the lines it is built from and evaluates them:
 * - the field shows `amountField.shown` and each change goes through `acceptAmountInputResult` (QA2 m8: on the state the
 *   holder hands it);
 * - the amount that counts is `amountField.settled`, which a raw paste never reaches (H4);
 * - the chooser is drawn under the field;
 * - while the question is open the amount is 0 and Send stays disabled; a tap on 1000 pays 100,000,000,000 zatoshi,
 *   a tap on 1 pays 100,000,000.
 */
class PastedThousandAsksTest {
    private val paymentDialog: String by lazy {
        val source = AmountReadingProbe.source(CHAT_DIALOGS)
        val start = source.indexOf("internal fun PaymentDialog(")
        assertTrue(start >= 0, "ChatDialogs.kt has no PaymentDialog")
        source.substring(start, source.indexOf("\n}\n", start))
    }

    @Test
    fun the_dialog_is_built_from_the_lines_this_test_evaluates() {
        for (line in listOf(FIELD_VALUE, SETTLED_LINE, PARSE_LINE, ENABLED_LINE, SEND_LINE, CHOOSER)) {
            assertTrue(line in paymentDialog, "PaymentDialog does not contain: $line")
        }
        assertTrue(Regex("""\{ state, newValue, selectionBefore ->\s+acceptAmountInputResult\(state,""").containsMatchIn(paymentDialog))
    }

    @Test
    fun a_pasted_1_000_asks_and_keeps_send_disabled() {
        val field = emptyField().edit("1,000", Locale.US)
        assertEquals(listOf("1", "1000"), field.ask, "1 ZEC first, then 1000 ZEC")

        // val amountText = amountField.settled; val amountZec = amountText.toDoubleOrNull() ?: 0.0
        val amountZec = field.settled.toDoubleOrNull() ?: 0.0
        assertEquals(0.0, amountZec, "enabled = amountZec > 0 && hasEnoughBalance: Send is disabled")
    }

    @Test
    fun a_raw_spanish_1_500_never_reaches_the_amount() {
        val field = emptyField().edit("1.500", Locale.forLanguageTag("es-ES"))
        assertEquals("", field.settled, "the raw paste read by toDoubleOrNull would be 1.5, unasked")
        assertEquals(listOf("1.5", "1500"), field.ask)
    }

    @Test
    fun the_chosen_reading_is_what_is_paid() {
        for ((choice, zatoshi) in listOf("1000" to 100_000_000_000L, "1" to 100_000_000L)) {
            val field = emptyField().edit("1,000", Locale.US).choose(choice)
            val amountZec = field.settled.toDoubleOrNull() ?: 0.0
            // onClick = { if (exactQuote != null) onSendPayment(amountZec, memo, exactQuote.quote) }; sendPayment converts it
            // exactly like this.
            assertEquals(zatoshi, BigDecimal.valueOf(amountZec).convertZecToZatoshi().value, "chose $choice")
        }
    }

    private companion object {
        const val CHAT_DIALOGS = "ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/chat/view/ChatDialogs.kt"
        const val FIELD_VALUE = "value = amountSelection.valueFor(amountField.shown)"
        const val SETTLED_LINE = "val amountText = amountField.settled"
        const val PARSE_LINE = "val amountZec = amountText.toDoubleOrNull() ?: 0.0"
        const val ENABLED_LINE = "enabled = amountZec > 0 && hasEnoughBalance"

        // D-6 (fix-all 2.13.0, owner W-10): Send hands on the exact quote of the typed amount and memo.
        const val SEND_LINE = "onClick = { if (exactQuote != null) onSendPayment(amountZec, memo, exactQuote.quote) }"
        const val CHOOSER = "AmountReadingChooser("
    }
}
