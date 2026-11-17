package co.electriccoin.zcash.ui.screen.chat.view

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Round 8 D10 (DECISIONS_NIGHT §5, p4-2b8456cab054) — the disappearing-messages dialog says that expiry is for good, in
 * English and Spanish.
 *
 * Since 58279b394 an expired message is tombstoned: its text and photos are deleted from this phone for good, and
 * turning the timer off does not bring them back. The dialog in ChatDetailView ("B17 — disappearing-messages TTL
 * picker") never says so, and every word of it is a hard-coded English literal: the title, the "Off" preset, "Custom: ",
 * the "min" hint, both body sentences, Apply and Cancel.
 *
 * Wanted:
 * - the dialog's words move to string resources; the English is today's text byte for byte, and the Spanish uses the
 *   glossary of the other timer texts (temporizador, mensajes temporales); the timed presets use the chat_ttl_* plurals
 *   the menu already shows ("1 minute" … "1 week");
 * - a new sentence, disappearing_dialog_permanent, follows "New messages disappear…" and comes before the Shielded
 *   sentence;
 * - English and Spanish carry the same placeholders (the body names the contact).
 *
 * Resources are read from the XML files, so this file compiles before they exist.
 */
class DisappearingDialogSpeaksTheTruthTest {
    private val en by lazy { ChatResourceText.strings("values") }
    private val es by lazy { ChatResourceText.strings("values-es") }

    @Test
    fun `the dialog strings exist in English, byte for byte today's text, and in Spanish`() {
        val problems = mutableListOf<String>()
        for ((key, english) in ENGLISH) {
            val enText = en[key]
            val esText = es[key]
            when {
                enText == null -> problems += "values has no $key"
                key !in REWORDED && enText != english -> problems += "English $key is \"$enText\", not \"$english\""
            }
            when {
                esText == null -> problems += "values-es has no $key"
                esText.isBlank() -> problems += "Spanish $key is blank"
                key !in SAME_IN_BOTH && esText == enText -> problems += "Spanish $key is the English text"
            }
        }
        assertTrue(problems.isEmpty(), problems.joinToString("\n"))
    }

    @Test
    fun `English and Spanish use the same placeholders`() {
        for (key in ENGLISH.keys) {
            val enText = en[key] ?: fail("values has no $key")
            val esText = es[key] ?: fail("values-es has no $key")
            assertEquals(placeholders(enText), placeholders(esText), "placeholders of $key")
        }
        assertEquals(listOf("%1\$s"), placeholders(en[BODY] ?: fail("values has no $BODY")), "the body names the contact once")
    }

    // ZC-1160: the permanent sentence's wording is pinned by DisappearingDialogDoesNotPromiseForGoodTest (no "for good" /
    // "para siempre"; a restore, a notification or a preview can still show such messages), not byte for byte here.

    @Test
    fun `the dialog shows the resources, the permanent sentence after the first one, and no hard-coded English`() {
        val dialog = dialogSource()
        for (key in ENGLISH.keys) {
            assertTrue("R.string.$key" in dialog, "the disappearing dialog must show R.string.$key")
        }
        val body = dialog.indexOf("R.string.$BODY")
        val permanent = dialog.indexOf("R.string.$PERMANENT")
        val shielded = dialog.indexOf("R.string.$BODY_SHIELDED")
        assertTrue(body in 0 until permanent, "the permanent sentence must follow \"New messages disappear…\"")
        assertTrue(permanent < shielded, "the permanent sentence must come before the Shielded sentence")
        for (literal in HARD_CODED) {
            assertFalse(literal in dialog, "the dialog still hard-codes $literal")
        }
        assertTrue("ttlLabel(" in dialog, "the timed presets must use the chat_ttl_* plurals (ttlLabel)")
    }

    /** The dialog's block in ChatDetailView: from its marker comment to the next dialog's comment. */
    private fun dialogSource(): String {
        val view = ChatResourceText.source(VIEW_PATH)
        val start = view.indexOf(DIALOG_START)
        if (start < 0) fail("the disappearing dialog's marker \"$DIALOG_START\" is gone from ChatDetailView")
        val end = view.indexOf(DIALOG_END, start)
        if (end < 0) fail("the marker \"$DIALOG_END\" after the disappearing dialog is gone from ChatDetailView")
        return view.substring(start, end)
    }

    private fun placeholders(text: String): List<String> =
        Regex("""%(\d+\$)?[sd]""")
            .findAll(text)
            .map { it.value }
            .toList()
            .sorted()

    private companion object {
        const val VIEW_PATH = "ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/chat/view/ChatDetailView.kt"
        const val DIALOG_START = "// B17 — disappearing-messages TTL picker."
        const val DIALOG_END = "// Confirm before fulfilling an incoming payment request"

        const val BODY = "disappearing_dialog_body"
        const val PERMANENT = "disappearing_dialog_permanent"
        const val BODY_SHIELDED = "disappearing_dialog_body_shielded"

        const val PERMANENT_EN =
            "Messages that disappear are deleted from this phone for good; turning the timer off doesn't bring them back."

        /** Today's English, byte for byte (the body's contact name becomes %1$s; "Custom: " keeps its space in code). */
        val ENGLISH =
            linkedMapOf(
                "disappearing_dialog_title" to "Disappearing messages",
                "disappearing_dialog_off" to "Off",
                "disappearing_dialog_custom" to "Custom:",
                "disappearing_dialog_custom_hint" to "min",
                BODY to
                    "New messages disappear from this chat on BOTH your device and %1\$s's after the timer. Older " +
                    "messages are not affected.",
                PERMANENT to PERMANENT_EN,
                BODY_SHIELDED to
                    "Shielded messages are Zcash transactions: this hides them in the app on both sides, but the " +
                    "encrypted transaction stays on the blockchain forever. Syncing sends one on-chain memo (standard " +
                    "message cost).",
                "disappearing_dialog_apply" to "Apply",
                "disappearing_dialog_cancel" to "Cancel",
            )

        /** ZC-1160: reworded after this test; DisappearingDialogDoesNotPromiseForGoodTest pins what it may say. */
        val REWORDED = setOf(PERMANENT)

        /** "min" is the same abbreviation in Spanish. */
        val SAME_IN_BOTH = setOf("disappearing_dialog_custom_hint")

        val HARD_CODED =
            listOf(
                "Text(\"Disappearing messages\")",
                "\"Off\"",
                "\"1 minute\"",
                "\"1 week\"",
                "Text(\"Custom: \")",
                "Text(\"min\")",
                "\"New messages disappear",
                "\" Shielded messages are Zcash transactions",
                "Text(\"Apply\"",
                "Text(\"Cancel\")",
            )
    }
}
