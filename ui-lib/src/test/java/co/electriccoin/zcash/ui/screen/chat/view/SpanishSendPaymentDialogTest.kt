package co.electriccoin.zcash.ui.screen.chat.view

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Round 8 P9 (PHONE_TRIAGE ES-1) — the Send Payment dialog (ChatDialogs.kt PaymentDialog) is English on a Spanish
 * phone: seeker/logs/es/11 shows English labels next to Spanish figures, the amount field at "0.5" and the button
 * reading "Send 0,5 ZEC". The dialog moves real ZEC.
 *
 * Wanted:
 * - every word the dialog shows (Text, text, label, placeholder, contentDescription) is a string resource in
 *   ui/common/values{,-es}/send_payment_dialog_strings.xml; the English text is the old literal byte for byte, the
 *   Spanish text is a translation (it differs) and keeps the same placeholders;
 * - the amount field shows the phone's decimal separator through a VisualTransformation (the stored text keeps its
 *   '.', so parsing and acceptAmountKeystroke do not change), and the placeholder is built from the same symbol.
 *
 * KI-3 (train T4): the Split Payment card left this dialog (it showed a share while Send paid the total), so its six
 * keys are no longer checked here; the counter's two content descriptions are checked in RequestComposerOffersSplitTest
 * and the removal of the others in SplitStringsHaveSpanishTest.
 */
class SpanishSendPaymentDialogTest {
    private val en by lazy { ChatResourceText.strings("values") }
    private val es by lazy { ChatResourceText.strings("values-es") }

    private val dialogsPath = "ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/chat/view/ChatDialogs.kt"

    /** The source of PaymentDialog, from its declaration to the time-lock dialog's banner. */
    private val dialog: String by lazy {
        val src = ChatResourceText.source(dialogsPath)
        val start = src.indexOf("internal fun PaymentDialog(")
        val end = src.indexOf("// TIME-LOCK COMPOSER DIALOG")
        assertTrue(start >= 0 && end > start, "fixture: PaymentDialog and the time-lock banner in ChatDialogs.kt")
        src.substring(start, end)
    }

    /** The English text each key must carry: the dialog's literals before the move. */
    private val expectedEnglish =
        mapOf(
            "send_payment_dialog_title" to "Send Payment",
            "send_payment_dialog_close" to "Close",
            "send_payment_dialog_quick_pay" to "Quick Pay: %1\$s",
            "send_payment_dialog_to" to "To: %1\$s",
            "send_payment_dialog_amount_label" to "Amount (ZEC)",
            "send_payment_dialog_available" to "Available:",
            "send_payment_dialog_can_send_to_others" to "Can send to others:",
            // D-6 (fix-all 2.13.0, owner W-10): the exact fees replace "+ about %1$s ZEC in fees (platform and network)".
            "send_payment_dialog_fees_exact" to "+ %1\$s ZEC in fees (platform %2\$s, network %3\$s)",
            "send_payment_dialog_memo_label" to "Memo (optional)",
            "send_payment_dialog_memo_placeholder" to "Add a note...",
            "send_payment_dialog_not_enough" to "Not enough that can go to others for this amount plus fees",
            "send_payment_dialog_send" to "Send",
            "send_payment_dialog_send_amount" to "Send %1\$s ZEC",
            "send_payment_dialog_cancel" to "Cancel",
        )

    @Test
    fun the_dialog_shows_no_english_literal() {
        val found = englishLiterals(dialog)
        assertTrue(found.isEmpty(), "the Send Payment dialog still shows English literals:\n" + found.joinToString("\n"))
    }

    @Test
    fun every_key_is_the_old_english_and_a_spanish_translation() {
        expectedEnglish.forEach { (key, english) ->
            assertEquals(english, en[key], "English $key")
            val spanish = assertNotNull(es[key], "no Spanish $key")
            assertNotEquals(english, spanish, "$key is not translated")
            assertEquals(placeholders(english), placeholders(spanish), "$key: the Spanish placeholders differ")
            assertTrue("R.string.$key" in dialog, "PaymentDialog does not show $key")
        }
    }

    @Test
    fun the_amount_field_shows_the_phones_decimal_separator() {
        assertFalse("placeholder = { Text(\"0.00\") }" in dialog, "the amount placeholder is still the English \"0.00\"")
        assertTrue("visualTransformation" in dialog, "the amount field shows the stored '.' on a Spanish phone")
        // The stored text and its keystroke rule are unchanged.
        // F1: the rule is acceptAmountInput (the keystroke rule plus the paste question); the amount is its settled text.
        // T1 fixup (X-F1-1): the field passes its unit, which also gives the precision.
        assertTrue(
            "acceptAmountInputResult(state, newValue, formatLocale, AmountUnit.ZEC, selectionBefore)" in dialog,
            "the amount input rule changed",
        )
        assertTrue("amountText.toDoubleOrNull()" in dialog, "the amount parse changed")
    }

    internal companion object {
        /** %1$s, %2$d, …: the format placeholders of [text], in order. */
        fun placeholders(text: String): List<String> = Regex("""%\d+\$[sd]""").findAll(text).map { it.value }.toList()

        private val stringLiteral = Regex(""""((?:[^"\\\n]|\\.)*)"""")

        /** Words a figure may carry without being translated. */
        private val untranslatable = setOf("ZEC", "USD")

        /**
         * Every string literal in [code] (comments removed, `${…}` / `$name` templates removed) that holds an English
         * word, i.e. two or more ASCII letters other than ZEC or USD, with its line.
         */
        fun englishLiterals(code: String): List<String> {
            val lines =
                code
                    .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL)) { m -> "\n".repeat(m.value.count { it == '\n' }) }
                    .lines()
                    .map { line -> line.replace(Regex("""(^|\s)//.*$"""), "") }
            return lines.flatMapIndexed { i, line ->
                stringLiteral.findAll(line).mapNotNull { m ->
                    val text =
                        m.groupValues[1]
                            .replace(Regex("""\$\{[^}]*}"""), " ")
                            .replace(Regex("""\$[A-Za-z_][A-Za-z0-9_]*"""), " ")
                    val words = Regex("""[A-Za-z]{2,}""").findAll(text).map { it.value }.filter { it !in untranslatable }
                    if (words.any()) "line ${i + 1}: \"${m.groupValues[1]}\"" else null
                }
            }
        }
    }
}
