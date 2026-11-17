package co.electriccoin.zcash.ui.screen.chat.view

import org.junit.Test
import java.lang.reflect.Modifier
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * ZC-1475 (owner findings 2 Nov, point 15; FIX_PLAN REVISION 1, B2; L5): the safety-number dialog shows "Done" and
 * "Mark as verified" side by side as two text buttons that read the same; the owner wants one action and a close cross.
 * Its title and body lines are English literals with no Spanish (app-texts rule 1).
 *
 * Wanted:
 * - `enum class SafetyNumberButton { MARK_VERIFIED, CLOSE }` and `object SafetyNumberButtons { fun forState(isVerified:
 *   Boolean): List<SafetyNumberButton> }` in `screen.chat.model`: not verified → [MARK_VERIFIED, CLOSE] (one primary
 *   button and the X), verified → [CLOSE] only. There is no "Done" button.
 * - the texts are resources in English and Spanish: chat_safety_number_title, chat_safety_number_compare,
 *   chat_safety_number_mismatch, chat_safety_number_you_verified, chat_safety_number_close_cd (the X's content
 *   description); chat_safety_number_mark_verified exists already.
 * markE2EVerified is unchanged (a local flag; no wire change, 2.12.4 unaffected).
 *
 * The new API is reached by name, so this file compiles before it exists.
 */
class SafetyNumberDialogHasOneActionTest {
    private fun buttons(isVerified: Boolean): List<String> {
        val cls =
            runCatching { Class.forName("co.electriccoin.zcash.ui.screen.chat.model.SafetyNumberButtons") }.getOrElse {
                throw AssertionError(
                    "no co.electriccoin.zcash.ui.screen.chat.model.SafetyNumberButtons: the dialog still shows Done and Mark as verified",
                )
            }
        val method =
            assertNotNull(
                cls.methods.firstOrNull { it.name == "forState" && it.parameterCount == 1 },
                "SafetyNumberButtons has no forState(isVerified: Boolean)",
            )
        val receiver = if (Modifier.isStatic(method.modifiers)) null else cls.getField("INSTANCE").get(null)
        return (method.invoke(receiver, isVerified) as List<*>).map { (it as Enum<*>).name }
    }

    @Test
    fun an_unverified_contact_gets_one_primary_action_and_the_close_cross() {
        assertEquals(listOf("MARK_VERIFIED", "CLOSE"), buttons(isVerified = false))
    }

    @Test
    fun a_verified_contact_gets_only_the_close_cross() {
        assertEquals(listOf("CLOSE"), buttons(isVerified = true))
    }

    @Test
    fun there_is_no_done_button() {
        val cls = runCatching { Class.forName("co.electriccoin.zcash.ui.screen.chat.model.SafetyNumberButton") }.getOrNull()
        val names = assertNotNull(cls, "no enum SafetyNumberButton").enumConstants.orEmpty().map { (it as Enum<*>).name }
        assertEquals(listOf("MARK_VERIFIED", "CLOSE"), names)
    }

    @Test
    fun the_dialog_texts_are_resources_in_english_and_spanish() {
        val en = ChatResourceText.strings("values")
        val es = ChatResourceText.strings("values-es")
        for (key in KEYS) {
            val english = assertNotNull(en[key], "values has no $key")
            val spanish = assertNotNull(es[key], "values-es has no $key")
            assertTrue(english.isNotBlank() && spanish.isNotBlank(), key)
            assertNotEquals(english, spanish, "$key is not translated")
        }
        assertEquals(
            "Compare this number with your contact. If they match, your conversation is secure.",
            en["chat_safety_number_compare"],
        )
        assertEquals("If the numbers don't match, someone may be intercepting your messages.", en["chat_safety_number_mismatch"])
    }

    @Test
    fun the_dialog_asks_the_rule_and_has_no_done_button() {
        val view = SourceScan.stripComments(ChatResourceText.source(CDV))
        val start = view.indexOf("if (showSafetyNumberDialog && safetyNumber != null)")
        assertTrue(start >= 0, "the safety-number dialog block was not found")
        val dialog = SourceScan.braceBlock(view.substring(start), "if (showSafetyNumberDialog")
        assertTrue("SafetyNumberButtons.forState(" in dialog, "the dialog does not pick its buttons with SafetyNumberButtons.forState")
        assertFalse("Text(\"Done\"" in dialog, "the dialog still has the Done button")
    }

    private companion object {
        const val CDV = "ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/chat/view/ChatDetailView.kt"
        val KEYS =
            listOf(
                "chat_safety_number_title",
                "chat_safety_number_compare",
                "chat_safety_number_mismatch",
                "chat_safety_number_you_verified",
                "chat_safety_number_close_cd",
                "chat_safety_number_mark_verified",
            )
    }
}
