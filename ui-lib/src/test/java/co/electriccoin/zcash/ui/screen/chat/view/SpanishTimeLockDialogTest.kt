package co.electriccoin.zcash.ui.screen.chat.view

import co.electriccoin.zcash.ui.screen.chat.view.SpanishSendPaymentDialogTest.Companion.englishLiterals
import co.electriccoin.zcash.ui.screen.chat.view.SpanishSendPaymentDialogTest.Companion.placeholders
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Round 8 P9 (PHONE_TRIAGE ES-6) — the time-lock dialog (ChatDialogs.kt TimeLockComposerDialog and its four lock
 * settings) is English on a Spanish phone: seeker/logs/es/09 shows "Note: the text is stored unencrypted…". Those
 * four notes are the one place that tells the user a time-locked message is not encrypted, so a Spanish reader must
 * get that disclosure in Spanish.
 *
 * Wanted: every word of the dialog moves to ui/common/values{,-es}/timelock_dialog_strings.xml. The four notes keep
 * their English byte for byte and each Spanish note says the text "no está cifrad…". The Payment presets are written
 * with the phone's decimal separator (zecDisplay(…, locale)) and scroll sideways instead of overflowing.
 */
class SpanishTimeLockDialogTest {
    private val en by lazy { ChatResourceText.strings("values") }
    private val es by lazy { ChatResourceText.strings("values-es") }

    private val dialogsPath = "ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/chat/view/ChatDialogs.kt"

    /** The time-lock dialog and its four lock settings, up to the template picker's banner. */
    private val dialog: String by lazy {
        val src = ChatResourceText.source(dialogsPath)
        val start = src.indexOf("internal fun TimeLockComposerDialog(")
        val end = src.indexOf("// TEMPLATE PICKER")
        assertTrue(start >= 0 && end > start, "fixture: TimeLockComposerDialog and the template picker banner")
        src.substring(start, end)
    }

    private fun section(name: String): String {
        val start = dialog.indexOf("private fun $name(")
        assertTrue(start >= 0, "fixture: $name in ChatDialogs.kt")
        val next = dialog.indexOf("@Composable", start)
        return if (next > start) dialog.substring(start, next) else dialog.substring(start)
    }

    /** The four "not encrypted" notes, as the dialog showed them in English. */
    private val notes =
        mapOf(
            "timelock_schedule_note" to
                "Message will automatically unlock after the specified time. Note: the message is hidden by the app, " +
                "not encrypted — anyone reading the raw transaction can see it before then.",
            "timelock_block_note" to
                "Message is hidden until a specific Zcash block height. The timing is chain-verifiable, but the text " +
                "itself is stored unencrypted in the on-chain memo.",
            "timelock_payment_note" to
                "Shows the recipient a request to pay you before revealing. Note: the text is stored unencrypted in " +
                "the on-chain memo, so this is a reminder — not enforced.",
            "timelock_secret_note" to
                "Asks the recipient a question before revealing. Note: the text is stored unencrypted in the on-chain " +
                "memo, so this is a reminder — not enforced.",
        )

    private val noteSections =
        mapOf(
            "timelock_schedule_note" to "ScheduledLockSettings",
            "timelock_block_note" to "BlockHeightLockSettings",
            "timelock_payment_note" to "PaymentLockSettings",
            "timelock_secret_note" to "ConditionalLockSettings",
        )

    @Test
    fun the_four_not_encrypted_notes_are_translated() {
        notes.forEach { (key, english) ->
            assertEquals(english, en[key], "English $key")
            val spanish = assertNotNull(es[key], "no Spanish $key")
            assertNotEquals(english, spanish, "$key is not translated")
            assertTrue("no está cifrad" in spanish, "the Spanish $key must say the text is not encrypted: $spanish")
            assertTrue("R.string.$key" in section(noteSections.getValue(key)), "${noteSections[key]} does not show $key")
        }
    }

    @Test
    fun the_dialog_shows_no_english_literal() {
        val found = englishLiterals(dialog)
        assertTrue(found.isEmpty(), "the time-lock dialog still shows English literals:\n" + found.joinToString("\n"))
    }

    @Test
    fun every_timelock_key_exists_in_spanish_with_the_same_placeholders() {
        val keys = en.keys.filter { it.startsWith("timelock_") }
        assertTrue(keys.size >= notes.size, "fixture: timelock_ strings exist: $keys")
        keys.forEach { key ->
            val spanish = assertNotNull(es[key], "no Spanish $key")
            assertEquals(placeholders(en.getValue(key)), placeholders(spanish), "$key: the Spanish placeholders differ")
        }
    }

    @Test
    fun the_payment_presets_follow_the_language_and_scroll() {
        val payment = section("PaymentLockSettings")
        assertFalse("text = \"\$value ZEC\"" in payment, "a Payment preset is still written with an English point")
        assertTrue("zecDisplay(" in payment, "the Payment presets are not written with zecDisplay(…, locale)")
        assertTrue("horizontalScroll" in payment, "the Payment presets do not scroll sideways")
    }
}
