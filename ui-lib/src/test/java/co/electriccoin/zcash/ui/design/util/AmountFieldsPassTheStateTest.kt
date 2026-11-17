package co.electriccoin.zcash.ui.design.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * QA2 m8 (money-path rule 5, amount input), design section 2 and ZC-1782: no call site edits "the composition's machine".
 *
 * Inside a burst of callbacks the composition's state is stale after the first callback. The shipped call sites edit it
 * anyway: SendView (`AmountState.edit(current = amountState, ...)`), PaymentLockSettings, and the chat fields in a locale
 * where the callback has more than one part (ZC-1782: "1", then "." and "5" in one frame is a paste error). The design makes
 * the holder `AmountTextFieldSelection<S>`: the holder hands each edit the machine state it must edit (PENDING, the state
 * after the last taken callback of the frame, else the composition's), the lambda becomes
 * `(state, newText, selectionBefore) -> AmountEditResult<S>`, and a second lambda `onState` stores the result. That closes
 * ZC-1782 for free, provided that no call site reads its own variable inside the lambdas.
 *
 * All 7 call sites (SendView 2, ZchatComposeView 1, ChatDialogs 3, AiTopupSheet 1) must:
 * - call the holder with a three-parameter edit lambda (`{ state, newText, selectionBefore -> ... }`) and an `onState`;
 * - not mention the composition's own machine variable inside the call, except to read the text it shows
 *   (`amountState.value`, `customAmount.shown`), which is the holder's `shown` argument.
 *
 * Compose cannot run on the JVM here, so the call sites are read from the sources (the idiom of
 * AmountFieldsPassTheSelectionTest). The parameter names are free; a different shape of the holder call changes this test
 * with [test-change-ok] and the design, not silently.
 */
class AmountFieldsPassTheStateTest {
    private val base = "ui-lib/src/main/java/co/electriccoin/zcash/ui/"

    private class Site(
        val file: String,
        val variable: String,
        val count: Int
    )

    // the composition's machine variable per call site, in source order
    private val sites =
        listOf(
            Site("screen/send/view/SendView.kt", "amountState", 2),
            Site("screen/chat/view/ZchatComposeView.kt", "localCustom", 1),
            Site("screen/chat/view/ChatDialogs.kt", "amountField|amount", 3),
            Site("screen/ai/AiTopupSheet.kt", "customAmount", 1)
        )

    /** Every call of the holder in [source]: `xxxSelection.textFieldState(...)` or `xxxSelection.onValueChange(...)` with its lambda. */
    private fun holderCalls(source: String): List<String> {
        val calls = mutableListOf<String>()
        for (match in Regex("""\w+Selection\.(textFieldState|onValueChange)\(""").findAll(source)) {
            var depth = 0
            var end = match.range.last
            do {
                when (source[end]) {
                    '(' -> depth++
                    ')' -> depth--
                }
                end++
            } while (depth > 0 && end < source.length)
            // the trailing lambda, when the call has one outside its parentheses
            var tail = end
            while (tail < source.length && source[tail].isWhitespace()) tail++
            if (tail < source.length && source[tail] == '{') {
                var braces = 0
                var close = tail
                do {
                    when (source[close]) {
                        '{' -> braces++
                        '}' -> braces--
                    }
                    close++
                } while (braces > 0 && close < source.length)
                end = close
            }
            calls += source.substring(match.range.first, end)
        }
        return calls
    }

    private val threeParameterLambda = Regex("""\{\s*\w+\s*,\s*\w+\s*,\s*\w+\s*->""")

    @Test
    fun every_call_site_hands_the_holder_a_state_lambda_and_an_on_state() {
        val problems = mutableListOf<String>()
        var total = 0
        for (site in sites) {
            val calls = holderCalls(AmountReadingProbe.source(base + site.file))
            total += calls.size
            if (calls.size != site.count) problems += "${site.file}: ${calls.size} holder call(s), expected ${site.count}"
            for (call in calls) {
                val short = call.replace(Regex("\\s+"), " ").take(110)
                if (!threeParameterLambda.containsMatchIn(call)) {
                    problems += "${site.file}: the edit lambda does not take (state, newText, selectionBefore): $short"
                }
                if (!Regex("""\bonState\b""").containsMatchIn(call)) {
                    problems += "${site.file}: no onState to store the machine's new state: $short"
                }
            }
        }
        assertEquals(7, total, "the 7 amount call sites")
        assertTrue(problems.isEmpty(), "ZC-1782:\n" + problems.joinToString("\n"))
    }

    @Test
    fun no_call_site_edits_the_composition_s_machine_inside_the_holder_call() {
        val problems = mutableListOf<String>()
        for (site in sites) {
            val variable = Regex("""\b(${site.variable})\b""")
            for (call in holderCalls(AmountReadingProbe.source(base + site.file))) {
                // reading the text the field shows is the holder's `shown` argument and stays
                val withoutShownReads = call.replace(Regex("""\b(${site.variable})\.(shown|value|fiatValue)\b"""), "")
                val stale = variable.findAll(withoutShownReads).map { it.value }.toSet()
                if (stale.isNotEmpty()) {
                    val used = stale.joinToString()
                    val callText = call.replace(Regex("\\s+"), " ").take(110)
                    problems += "${site.file}: the holder call uses the composition's $used (stale after the first callback): $callText"
                }
            }
        }
        assertTrue(problems.isEmpty(), "ZC-1782: a lambda edits the composition's machine:\n" + problems.joinToString("\n"))
    }
}
