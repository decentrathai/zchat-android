package co.electriccoin.zcash.ui.design.util

import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * QA2 m4, ZC-1759 (high; Fable's review of m3 finding 1, GPT finding 1): the fix of m3 made the amount machine drop a key it
 * cannot take, and it told a key from a select-all paste by the selection before the edit. The shipped fields did not pass the
 * selection (the machine falls back to a text diff), so a select-all paste that shares its first and last characters with the
 * old text looked like one typed comma and was dropped while the old, larger amount stayed valid and Send stayed enabled
 * (main Send ZEC: 15234.5, select all, paste 1,234.5 keeps 15234.5).
 *
 * Every shipped amount field must hand the machine the selection it had before the edit:
 * - main Send's ZEC and USD fields (SendView) through [AmountState.edit] with the selection, not `selectionBefore = null`
 *   (the EnhancedTextFieldState overload of ZashiTextField reports the selection);
 * - the chat fields (Send Payment, time-lock, payment request, new-chat custom amount) through acceptAmountInput;
 * - the AI top-up custom USD field through acceptUsdAmountInput, which takes the selection like acceptAmountInput does.
 *
 * Compose cannot run on the JVM here, so the call sites are read from the sources (the CallHidesKeyboardTest idiom); the
 * keyboard itself, long-press Paste and the keyboard's clipboard chip are phone checks (m3 design section 8).
 */
class AmountFieldsPassTheSelectionTest {
    private val base = "ui-lib/src/main/java/co/electriccoin/zcash/ui/"

    /** Every call of [name] in [source]: the text from the name to the matching closing parenthesis. */
    private fun callsOf(
        source: String,
        name: String
    ): List<String> {
        val calls = mutableListOf<String>()
        var from = 0
        while (true) {
            val at = source.indexOf("$name(", from)
            if (at < 0) break
            from = at + name.length
            val lineStart = source.lastIndexOf('\n', at) + 1
            val before = source.substring(lineStart, at)
            if (before.trimStart().startsWith("import ") || before.contains("fun ")) continue
            var depth = 0
            var end = at + name.length
            do {
                when (source[end]) {
                    '(' -> depth++
                    ')' -> depth--
                }
                end++
            } while (depth > 0 && end < source.length)
            calls += source.substring(at, end)
        }
        return calls
    }

    private fun assertEveryCallPassesTheSelection(
        file: String,
        name: String
    ) {
        val calls = callsOf(AmountReadingProbe.source(base + file), name)
        // QA2 m8 (W-137 m8): the call sites call the machines' result variants; a scan that finds no call proves nothing.
        assertTrue(calls.isNotEmpty(), "$file has no call of $name")
        val bad =
            calls.filter { call ->
                !call.contains("election") || Regex("""election\w*\s*=\s*null\b""").containsMatchIn(call)
            }
        assertTrue(
            bad.isEmpty(),
            "ZC-1759: $file has ${bad.size} call(s) of $name that do not pass the selection before the edit: " +
                bad.joinToString(" | ") { it.replace(Regex("\\s+"), " ").take(140) }
        )
    }

    @Test
    fun main_send_zec_and_usd_fields_pass_the_selection() {
        assertEveryCallPassesTheSelection("screen/send/view/SendView.kt", "AmountState.editResult")
        val source = AmountReadingProbe.source(base + "screen/send/view/SendView.kt")
        assertTrue(
            !Regex("""selectionBefore\s*=\s*null""").containsMatchIn(source),
            "ZC-1759: SendView still passes selectionBefore = null"
        )
    }

    @Test
    fun the_chat_send_payment_time_lock_and_payment_request_fields_pass_the_selection() {
        assertEveryCallPassesTheSelection("screen/chat/view/ChatDialogs.kt", "acceptAmountInputResult")
    }

    @Test
    fun the_new_chat_custom_amount_field_passes_the_selection() {
        assertEveryCallPassesTheSelection("screen/chat/view/ZchatComposeView.kt", "acceptAmountInputResult")
    }

    @Test
    fun the_ai_topup_custom_usd_field_passes_the_selection() {
        assertEveryCallPassesTheSelection("screen/ai/AiTopupSheet.kt", "acceptUsdAmountInputResult")
    }

    @Test
    fun acceptUsdAmountInput_takes_the_selection_before_the_edit() {
        val file =
            runCatching { Class.forName("co.electriccoin.zcash.ui.screen.ai.UsdAmountInputKt") }.getOrNull()
                ?: fail("UsdAmountInputKt is gone")
        val methods = file.declaredMethods.filter { it.name == "acceptUsdAmountInput" }
        assertTrue(methods.isNotEmpty(), "acceptUsdAmountInput is gone")
        assertTrue(
            methods.any { method -> method.parameterTypes.any { it == kotlin.ranges.IntRange::class.java } },
            "ZC-1759: acceptUsdAmountInput takes no selection (parameters ${methods.map { m -> m.parameterTypes.map { it.simpleName } }})"
        )
    }
}
