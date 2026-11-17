package co.electriccoin.zcash.ui.screen.chat.view

import androidx.compose.ui.text.input.VisualTransformation
import co.electriccoin.zcash.ui.design.util.AmountReadingProbe
import co.electriccoin.zcash.ui.design.util.AmountReadingProbe.edit
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * C8 (round 7 adversarial review of B8, finding 23) — the chat amount fields must keep using the keystroke rule.
 *
 * B8 (SEND-PAYMENT-COMMA) fixed the three chat amount fields (Send Payment, the time-lock required amount and the
 * payment-request amount) by routing every keystroke through `acceptAmountKeystroke`, which reads a comma as the
 * decimal point. The old rule, `newValue.matches(Regex("^\\d*\\.?\\d*$"))`, ignored the comma, so 0 , 5 left "05"
 * and Send Payment offered 5 ZEC. The helper has its own test, but nothing pinned the call sites: putting the old
 * regex back into ChatDialogs.kt broke no test.
 *
 * This is a source scan of ChatDialogs.kt: every decimal (amount) OutlinedTextField must call
 * `acceptAmountInput` in its onValueChange, each of the three amount dialogs must have such a field, and the
 * old literal regex must be gone. [the_scan_fails_on_the_source_before_b8] runs the same scan on the file as it was
 * before the B8 fix and requires it to fail there.
 *
 * F1 (fix-all 2.13.0, train T1): the rule is now `acceptAmountInput`, which keeps B8's keystroke rule (a typed mark
 * is the decimal point) and reads a paste with the shared amount reader, so a pasted "1,000" asks instead of paying 1
 * ZEC. At HEAD the fields call `acceptAmountKeystroke`, the payment-request field shows the stored '.' even on a
 * Spanish phone (it has no DecimalSeparatorTransformation), a question has nowhere to be shown, the request's USD
 * switch keeps 8 decimals, and the new-chat custom amount reads a pasted "1.500" as 1500 on a Spanish phone.
 */
class ChatAmountFieldsUseKeystrokeRuleTest {
    private val repoRoot: File by lazy {
        var dir: File? = File("").absoluteFile
        while (dir != null && !(File(dir, "gradle.properties").isFile && File(dir, "README.md").isFile)) {
            dir = dir.parentFile
        }
        assertNotNull(dir, "repo root not found above the test working dir")
    }

    private val chatDialogs: String by lazy { File(repoRoot, CHAT_DIALOGS).readText() }

    @Test
    fun every_amount_field_calls_the_keystroke_rule() {
        val problems = violations(chatDialogs)
        assertTrue(problems.isEmpty(), "ChatDialogs.kt amount fields:\n" + problems.joinToString("\n"))
    }

    @Test
    fun the_three_amount_dialogs_each_have_an_amount_field_that_calls_the_rule() {
        for (function in AMOUNT_DIALOGS) {
            val body = assertNotNull(functionBody(chatDialogs, function), "ChatDialogs.kt has no fun $function")
            val fields = outlinedTextFields(body).filter { it.isAmountField() }
            assertTrue(fields.isNotEmpty(), "$function has no decimal amount field")
            assertTrue(fields.all { it.callsTheRule() }, "$function has an amount field that does not call $RULE")
        }
    }

    @Test
    fun the_rule_is_called_at_least_three_times() {
        val calls = Regex("""\b$RULE(Result)?\s*\(""").findAll(chatDialogs).count()
        assertTrue(calls >= 3, "ChatDialogs.kt calls $RULE $calls time(s); the three amount fields need it")
    }

    @Test
    fun the_old_literal_regex_is_gone() {
        for (old in OLD_REGEX_FORMS) {
            assertFalse(old in chatDialogs, "ChatDialogs.kt still contains the old amount regex $old")
        }
    }

    @Test
    fun every_amount_field_shows_the_phones_mark_except_while_a_question_is_open() {
        // H9 + the request field (round 8 P9 left it out): the stored '.' is shown as the phone's mark, and not while
        // a question is open, when the field shows the raw paste the question quotes.
        for (function in AMOUNT_DIALOGS) {
            val body = assertNotNull(functionBody(chatDialogs, function), "ChatDialogs.kt has no fun $function")
            val fields = outlinedTextFields(body).filter { it.isAmountField() }
            assertTrue(
                fields.all { "visualTransformation = amountFieldTransformation(" in it },
                "$function has an amount field without amountFieldTransformation",
            )
            assertTrue("AmountReadingChooser(" in body, "$function does not show the question under its amount field")
        }
    }

    @Test
    fun the_transformation_is_off_while_a_question_is_open() {
        val owner = AmountReadingProbe.type("co.electriccoin.zcash.ui.screen.chat.util.PaymentAmountInputKt")
        val transformation = AmountReadingProbe.method(owner, "amountFieldTransformation", 2)
        val open = AmountReadingProbe.emptyField().edit("1.500", Locale.forLanguageTag("es-ES"))
        val settled = AmountReadingProbe.settledField("1.5")
        assertEquals(VisualTransformation.None, AmountReadingProbe.call(transformation, null, open.handle, ','))
        assertEquals(
            "DecimalSeparatorTransformation",
            AmountReadingProbe.call(transformation, null, settled.handle, ',')!!.javaClass.simpleName,
        )
    }

    @Test
    fun the_request_usd_switch_re_reads_the_amount() {
        val body = assertNotNull(functionBody(chatDialogs, "PaymentRequestComposerDialog"))
        assertTrue(".reread(formatLocale," in body, "H13: switching to USD must re-read the amount (2 decimals)")
    }

    @Test
    fun the_new_chat_custom_amount_uses_the_same_rule() {
        val view = File(repoRoot, COMPOSE_VIEW).readText()
        val dialog = assertNotNull(functionBody(view, "AmountSelectionDialog"), "ZchatComposeView.kt has no AmountSelectionDialog")
        val fields = outlinedTextFields(dialog).filter { it.isAmountField() }
        assertTrue(fields.isNotEmpty() && fields.all { it.callsTheRule() }, "the custom amount field does not call $RULE")
        assertTrue("AmountReadingChooser(" in dialog)
        // The field hands the view model canonical '.' text, which must never be re-read with the phone's grouping.
        val vm = File(repoRoot, COMPOSE_VM).readText()
        assertTrue(
            "parseCustomAmountZatoshi(amountStr, java.util.Locale.ROOT)" in vm,
            "the custom amount is parsed with the phone's grouping"
        )
    }

    @Test
    fun the_scan_flags_the_old_field_shape() {
        // The Send Payment field as it was before B8, verbatim. The scan must reject it, or the pin proves nothing.
        val oldField =
            """
            OutlinedTextField(
                value = amountText,
                onValueChange = { newValue ->
                    // Only allow valid decimal input
                    if (newValue.isEmpty() || newValue.matches(Regex("^\\d*\\.?\\d*${'$'}"))) {
                        amountText = newValue
                    }
                },
                label = { Text("Amount (ZEC)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            )
            """.trimIndent()
        assertEquals(2, violations(oldField).size, "the old field must be flagged twice (no rule call, old regex)")
    }

    @Test
    fun the_scan_fails_on_the_source_before_b8() {
        val old = gitShow("$B8_FIX~1:$CHAT_DIALOGS")
        assumeTrue("git history with $B8_FIX is not available here", old != null)
        val problems = violations(old!!)
        println("Scan of $B8_FIX~1:$CHAT_DIALOGS (${problems.size} problem(s)):\n" + problems.joinToString("\n"))
        assertTrue(problems.size >= 3, "the scan must fail on the pre-B8 source, found only: $problems")
        for (function in AMOUNT_DIALOGS) {
            val body = assertNotNull(functionBody(old, function), "pre-B8 ChatDialogs.kt has no fun $function")
            assertTrue(
                outlinedTextFields(body).filter { it.isAmountField() }.none { it.callsTheRule() },
                "pre-B8 $function must not pass the scan",
            )
        }
    }

    private fun gitShow(spec: String): String? =
        runCatching {
            val process =
                ProcessBuilder("git", "show", spec)
                    .directory(repoRoot)
                    .redirectErrorStream(false)
                    .start()
            val text = process.inputStream.bufferedReader().readText()
            if (process.waitFor(30, TimeUnit.SECONDS) && process.exitValue() == 0) text else null
        }.getOrNull()

    private companion object {
        const val CHAT_DIALOGS = "ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/chat/view/ChatDialogs.kt"
        const val COMPOSE_VIEW = "ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/chat/view/ZchatComposeView.kt"
        const val COMPOSE_VM = "ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/chat/viewmodel/ZchatComposeVM.kt"
        const val RULE = "acceptAmountInput"

        /** The B8 fix commit; its parent still has the old regex in all three fields. */
        const val B8_FIX = "5d40b8994"

        val AMOUNT_DIALOGS = listOf("PaymentDialog", "PaymentLockSettings", "PaymentRequestComposerDialog")

        /** The old rule as it appears in Kotlin source (escaped string) and as a raw string. */
        val OLD_REGEX_FORMS = listOf("""\\d*\\.?\\d*""", """\d*\.?\d*""")

        fun String.isAmountField() = "KeyboardType.Decimal" in this

        // QA2 m8 (W-137 m8): the holder's edit lambda calls the rule's result variant on the state it is handed.
        fun String.callsTheRule(): Boolean = Regex("""\b$RULE(Result)?\s*\(""").containsMatchIn(onValueChangeArgument(this))

        /** The `onValueChange = ...` argument of a field's argument list, up to the next top-level comma. */
        fun onValueChangeArgument(arguments: String): String {
            val start = Regex("""\bonValueChange\s*=""").find(arguments)?.range?.last ?: return ""
            var depth = 0
            var i = start + 1
            while (i < arguments.length) {
                when (arguments[i]) {
                    '"' -> i = endOfString(arguments, i)
                    '(', '{', '[' -> depth++
                    ')', '}', ']' -> if (--depth < 0) break
                    ',' -> if (depth == 0) break
                }
                i++
            }
            return arguments.substring(start + 1, i)
        }

        /** Every problem the scan finds in [source]; empty when every amount field follows the rule. */
        fun violations(source: String): List<String> {
            val problems = mutableListOf<String>()
            val fields = outlinedTextFields(source)
            val amountFields = fields.filter { it.isAmountField() }
            if (amountFields.isEmpty()) problems += "no decimal amount OutlinedTextField found"
            amountFields.forEachIndexed { i, field ->
                if (!field.callsTheRule()) problems += "amount field #${i + 1} does not call $RULE in onValueChange"
                if (OLD_REGEX_FORMS.any { it in field }) problems += "amount field #${i + 1} uses the old regex"
            }
            return problems
        }

        /** The argument list of every `OutlinedTextField(...)` call in [source], parentheses balanced. */
        fun outlinedTextFields(source: String): List<String> {
            val result = mutableListOf<String>()
            var from = 0
            while (true) {
                val start = source.indexOf("OutlinedTextField(", from)
                if (start < 0) break
                val open = start + "OutlinedTextField".length
                val close = matchingParen(source, open)
                result += source.substring(open, close + 1)
                from = close + 1
            }
            return result
        }

        /** Index of the ')' matching the '(' at [open]; string literals are skipped. */
        fun matchingParen(source: String, open: Int): Int {
            var depth = 0
            var i = open
            while (i < source.length) {
                when (source[i]) {
                    '"' -> i = endOfString(source, i)
                    '(' -> depth++
                    ')' -> if (--depth == 0) return i
                }
                i++
            }
            error("unbalanced parentheses after index $open")
        }

        fun endOfString(source: String, quote: Int): Int {
            if (source.startsWith("\"\"\"", quote)) {
                val end = source.indexOf("\"\"\"", quote + 3)
                return if (end < 0) source.length else end + 2
            }
            var i = quote + 1
            while (i < source.length && source[i] != '"') {
                if (source[i] == '\\') i++
                i++
            }
            return i
        }

        /** From `fun <name>(` to the next top-level declaration or annotation at the start of a line. */
        fun functionBody(source: String, name: String): String? {
            val start = Regex("""(?m)^(?:internal |private )?fun $name\(""").find(source)?.range?.first ?: return null
            val next = Regex("""(?m)^(?:@|fun |internal |private )""").find(source, start + 1)?.range?.first
            return source.substring(start, next ?: source.length)
        }
    }
}
