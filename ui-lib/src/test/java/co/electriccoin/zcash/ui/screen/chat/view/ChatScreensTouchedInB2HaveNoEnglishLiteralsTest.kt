package co.electriccoin.zcash.ui.screen.chat.view

import org.junit.Test
import kotlin.test.assertTrue

/**
 * ZC-1482, the B2 part (FIX_PLAN REVISION 1, B2; L5, L6; app-texts rule 1): user-visible English literals with no
 * Spanish on the chat screens B2 changes. A secondary source scan (the behaviour REDs of ZC-1457 and ZC-1475 are pure
 * model tests); the My-code screen (ZchatReceiveView) is B4's.
 *
 * At 669cf19e1:
 * - ConversationModeIntroDialog: "Got it", "Choose how each chat travels", the row titles and the footer;
 * - the safety-number dialog: "Safety Number", "Compare this number…", "If the numbers don't match…",
 *   "✓ You marked this contact as verified.", "Done";
 * - PrivacyStatusCard: "Pool:", "Anonymity Set:", contentDescription "Warning".
 * Every text there must be a string resource with English and Spanish. Mode names stay product names, but come from a
 * resource or ConversationMode.label() too, not a literal.
 */
class ChatScreensTouchedInB2HaveNoEnglishLiteralsTest {
    private val cdv by lazy { SourceScan.stripComments(ChatResourceText.source(CDV)) }
    private val cmu by lazy { SourceScan.stripComments(ChatResourceText.source(CMU)) }

    private fun literals(block: String): List<String> = WORDY_LITERAL.findAll(block).map { it.value }.toList()

    private fun functionBody(
        src: String,
        name: String,
    ): String {
        val at = src.indexOf("fun $name(")
        assertTrue(at >= 0, "no fun $name")
        val end = src.indexOf("\n}\n", at)
        assertTrue(end >= 0, "fun $name has no end")
        return src.substring(at, end + 2)
    }

    @Test
    fun the_mode_intro_dialog_has_no_english_literal() {
        // The row composable is part of the dialog while it exists (the fix may rename or inline it).
        val row = if ("fun ModeIntroRow(" in cmu) functionBody(cmu, "ModeIntroRow") else ""
        val found = literals(functionBody(cmu, "ConversationModeIntroDialog")) + literals(row)
        assertTrue(found.isEmpty(), "the mode intro dialog still shows English literals: $found")
    }

    @Test
    fun the_safety_number_dialog_has_no_english_literal() {
        val start = cdv.indexOf("if (showSafetyNumberDialog && safetyNumber != null)")
        assertTrue(start >= 0, "the safety-number dialog block was not found")
        val found = literals(SourceScan.braceBlock(cdv.substring(start), "if (showSafetyNumberDialog"))
        assertTrue(found.isEmpty(), "the safety-number dialog still shows English literals: $found")
    }

    @Test
    fun the_privacy_status_card_has_no_english_literal() {
        val found = literals(functionBody(cdv, "PrivacyStatusCard"))
        assertTrue(found.isEmpty(), "PrivacyStatusCard still shows English literals: $found")
    }

    private companion object {
        const val CDV = "ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/chat/view/ChatDetailView.kt"
        const val CMU = "ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/chat/view/ConversationModeUi.kt"

        /** A Kotlin string literal holding a word (two letters in a row): a user-visible text, not a separator. */
        val WORDY_LITERAL = Regex(""""(?:[^"\\\n]|\\.)*\p{L}{2}(?:[^"\\\n]|\\.)*"""")
    }
}
