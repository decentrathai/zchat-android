package co.electriccoin.zcash.ui.screen.chat.view

import co.electriccoin.zcash.ui.design.util.AmountReadingProbe
import co.electriccoin.zcash.ui.design.util.AmountReadingProbe.Field
import org.junit.Test
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * T1 fixup, X-F1-1: every chat amount field reads a currency sign only when it names the field's own unit.
 *
 * At d27d46dba (F1) `acceptAmountInput` takes the field's precision but not its unit, and the reader drops "$", "US$",
 * "USD", "ZEC" and "ⓩ" in every field. So a "$5" copied from a price or an invoice and pasted into chat Send Payment,
 * the time-lock amount or the new-chat custom amount reads as 5 ZEC (at $50 a ZEC, about $250), and a "5 ZEC" pasted
 * into the payment request in dollar mode reads as $5.
 *
 * Wanted:
 * - `acceptAmountInput` takes the field's unit (AmountUnit), which also gives the precision;
 * - Send Payment, the time-lock amount and the new-chat custom amount pass AmountUnit.ZEC;
 * - the payment request passes the unit of its switch, both when the text changes and when the switch re-reads it;
 * - the other unit's sign is refused with a reason and nothing counts; the field's own sign still reads.
 */
class ChatAmountFieldsTakeTheirUnitTest {
    private val en = Locale.US

    private val chatDialogs: String by lazy { AmountReadingProbe.source(CHAT_DIALOGS) }
    private val composeView: String by lazy { AmountReadingProbe.source(COMPOSE_VIEW) }

    private fun body(
        source: String,
        function: String
    ): String {
        val start = source.indexOf("fun $function(")
        assertTrue(start >= 0, "no fun $function")
        return source.substring(start, source.indexOf("\n}\n", start))
    }

    /** acceptAmountInput(field, text, locale, unit); before the fixup its last parameter is the precision. */
    private fun accept(
        text: String,
        unit: String
    ): Field {
        val owner = AmountReadingProbe.type("co.electriccoin.zcash.ui.screen.chat.util.PaymentAmountInputKt")
        val method = AmountReadingProbe.method(owner, "acceptAmountInput", 4)
        val last =
            if (method.parameterTypes[3].name == AmountReadingProbe.UNIT) {
                AmountReadingProbe.unit(unit)
            } else if (unit == "USD") {
                2
            } else {
                8
            }
        val empty = AmountReadingProbe.emptyField()
        return AmountReadingProbe.field(AmountReadingProbe.call(method, null, empty.handle, text, en, last)!!)
    }

    @Test
    fun `a pasted dollar amount in a chat ZEC field is refused, not paid as ZEC`() {
        for (paste in listOf("$5", "US$5", "5 USD")) {
            val field = accept(paste, "ZEC")
            assertEquals("OtherUnit(USD)", field.error, "$paste in a chat ZEC field: $field")
            assertEquals("", field.settled, "$paste must not count as an amount")
        }
    }

    @Test
    fun `a pasted ZEC amount in the dollar request is refused, not requested as dollars`() {
        for (paste in listOf("5 ZEC", "ⓩ5")) {
            val field = accept(paste, "USD")
            assertEquals("OtherUnit(ZEC)", field.error, "$paste in the dollar request: $field")
            assertEquals("", field.settled)
        }
    }

    @Test
    fun `the field's own sign still reads (control)`() {
        assertEquals("5", accept("5 ZEC", "ZEC").settled)
        assertEquals("5", accept("$5", "USD").settled)
    }

    @Test
    fun `the ZEC fields pass the ZEC unit`() {
        assertTrue(
            "acceptAmountInputResult(state, newValue, formatLocale, AmountUnit.ZEC, selectionBefore)" in body(chatDialogs, "PaymentDialog"),
            "Send Payment does not pass its unit"
        )
        assertTrue(
            "acceptAmountInputResult(state, newValue, formatLocale, AmountUnit.ZEC, selectionBefore)" in
                body(chatDialogs, "PaymentLockSettings"),
            "the time-lock amount does not pass its unit"
        )
        assertTrue(
            "acceptAmountInputResult(state, newText, formatLocale, AmountUnit.ZEC, selectionBefore)" in
                body(composeView, "AmountSelectionDialog"),
            "the new-chat custom amount does not pass its unit"
        )
    }

    @Test
    fun `the payment request passes the unit of its switch`() {
        val request = body(chatDialogs, "PaymentRequestComposerDialog")
        assertTrue("val unit = if (useUsd) AmountUnit.USD else AmountUnit.ZEC" in request)
        assertTrue("acceptAmountInputResult(state, newValue, formatLocale, unit, selectionBefore)" in request)
        assertTrue(
            ".reread(formatLocale, if (it) AmountUnit.USD else AmountUnit.ZEC)" in request,
            "the switch must re-read the amount for the new unit"
        )
    }

    @Test
    fun `no chat amount field passes a bare precision`() {
        for ((name, source) in listOf("ChatDialogs.kt" to chatDialogs, "ZchatComposeView.kt" to composeView)) {
            assertFalse(
                Regex("""acceptAmountInput(Result)?\([^)]*(MAX_AMOUNT_DECIMALS|maxDecimals)""").containsMatchIn(source),
                "$name still passes a precision without a unit"
            )
        }
    }

    private companion object {
        const val CHAT_DIALOGS = "ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/chat/view/ChatDialogs.kt"
        const val COMPOSE_VIEW = "ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/chat/view/ZchatComposeView.kt"
    }
}
