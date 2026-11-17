package co.electriccoin.zcash.ui.screen.chat.view

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * ES-L (fix-all 2.13.0, train T4c; Spanish fold spec review item 10, §3.6; ZC-103): the chat list's Leave Group
 * dialog (swipe DELETE on a group row) shows the toast "Left group" as soon as Leave is tapped, before
 * GroupViewModel.leaveGroup asks the paid GROUP_LEAVE question (askForQuotedPlan) and before the leave runs. A user
 * who then declines, or whose leave fails, was told they left. The dialog's texts are English literals.
 *
 * What it must be: the toast is shown from leaveGroup's completion lambda, which GroupViewModel calls only after the
 * leave succeeded (its catch logs and skips it), and the dialog reads the group screen's own translated texts.
 */
class LeaveGroupToastWaitsForTheLeaveTest {
    private val path = "ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/chat/AndroidChat.kt"

    private fun leaveBlock(): Pair<KotlinUiLiterals.Lexed, IntRange> {
        val lexed = KotlinUiLiterals.lex(ChatResourceText.source(path))
        val start = lexed.source.indexOf("// Leave Group Confirmation Dialog")
        val end = lexed.source.indexOf("// Add Contact Dialog", start)
        assertTrue(start >= 0 && end > start, "the Leave Group dialog's comment anchors moved")
        return lexed to (start until end)
    }

    @Test
    fun the_left_group_toast_is_shown_only_after_the_leave() {
        val (lexed, block) = leaveBlock()
        val code = lexed.blanked.substring(block.first, block.last + 1)
        val call = Regex("""leaveGroup\(\s*selectedGroupId\s*\)\s*\{""").find(code)
        assertTrue(call != null, "the dialog must call groupViewModel.leaveGroup(selectedGroupId) { … } with a completion lambda")
        // The completion lambda, brace-matched.
        var depth = 0
        var close = -1
        for (i in call.range.last until code.length) {
            if (code[i] == '{') depth++
            if (code[i] == '}') {
                depth--
                if (depth == 0) {
                    close = i
                    break
                }
            }
        }
        val completion = code.substring(call.range.last, close + 1)
        assertTrue("Toast.makeText" in completion, "the \"Left group\" toast is not in leaveGroup's completion lambda")
        assertTrue(
            "R.string.chat_list_leave_group_left" in code,
            "the toast must read chat_list_leave_group_left",
        )
        assertEquals(
            1,
            Regex("""Toast\.makeText""").findAll(code).count(),
            "a toast outside the completion lambda would still show before the paid question and the leave",
        )
    }

    @Test
    fun the_dialog_reuses_the_group_screens_leave_texts() {
        val (lexed, block) = leaveBlock()
        val code = lexed.blanked.substring(block.first, block.last + 1)
        for (key in listOf("group_leave_confirm_title", "group_leave_confirm_body", "group_leave_confirm_button")) {
            assertTrue("R.string.$key" in code, "the chat-list Leave Group dialog does not read $key")
        }
    }
}
