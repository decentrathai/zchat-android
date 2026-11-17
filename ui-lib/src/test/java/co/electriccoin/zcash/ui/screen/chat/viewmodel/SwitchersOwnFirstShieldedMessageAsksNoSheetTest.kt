package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.screen.chat.view.ChatResourceText
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * QA2 B-8 (Q2-56, ZC-1647 wontfix; MONEY_CHECKS_B8_Q240_Q282.md; owner W-137): the phone that SWITCHED the chat to
 * Shielded sent its first Shielded message with no extra "Pay for this?" sheet, while the other phone was asked. That is by
 * design: the first-message sheet is asked only when the PEER moved the chat to the paid mode (ChatViewModel
 * modeMovedToPaidByPeer, set in handleModeControl) and is cleared by the user's own switch. The switcher saw "at least
 * 0.00017 ZEC each" on the mode sheet and sees the cost on Send; 17,000 paid equals the quote.
 *
 * This is a GUARD (green today, protects the rule from a later change): the mark is set only by the handler of the
 * contact's mode message, and cleared by our own switch, so our own first Shielded message never asks the extra sheet.
 * Behaviour tests of the same rule: ModeSwitchAsksBeforeEveryPaymentTest, PeerModeSwitchIsAdoptedOnBothPhonesTest.
 */
class SwitchersOwnFirstShieldedMessageAsksNoSheetTest {
    private val chat = "ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/chat"
    private val vm by lazy { ChatResourceText.source("$chat/viewmodel/ChatViewModel.kt") }

    private fun functionBody(name: String): String {
        val start = Regex("""\bfun\s+$name\s*\(""").find(vm)?.range?.first
        assertTrue(start != null, "ChatViewModel has no fun $name")
        var depth = 0
        var i = vm.indexOf('{', start)
        val open = i
        while (i < vm.length) {
            when (vm[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return vm.substring(open, i + 1)
                }
            }
            i++
        }
        error("unbalanced braces in $name")
    }

    @Test
    fun our_own_switch_clears_the_peer_moved_mark() {
        val own = functionBody("changeConversationMode")
        assertTrue(
            Regex("""setModeMovedToPaidByPeer\(\s*peerAddress\s*,\s*false\s*\)""").containsMatchIn(own),
            "our own switch must clear the mark, or our first Shielded message would be asked as if the peer had moved the chat",
        )
    }

    @Test
    fun only_the_handler_of_the_contacts_mode_message_sets_the_mark() {
        val sets = Regex("""setModeMovedToPaidByPeer\(\s*peerAddress\s*,\s*([^)]+)\)""").findAll(vm).toList()
        val settingTrue = sets.filter { it.groupValues[1].trim() != "false" }
        assertEquals(1, settingTrue.size, "the mark may be set from exactly one place: ${settingTrue.map { it.value }}")
        val handler = functionBody("handleModeControl")
        assertTrue(settingTrue.single().value in handler, "the mark is set outside handleModeControl (the contact's ZMODE handler)")
    }

    @Test
    fun the_first_message_sheet_is_asked_only_under_the_mark() {
        assertTrue(
            Regex("""retryPendingId == null\s*&&\s*!ZBootMessage\.isBootMessage\(message\)\s*&&\s*modeMovedToPaidByPeer\(peerAddress\)""")
                .containsMatchIn(vm),
            "the first-message price question must stay under modeMovedToPaidByPeer (the peer moved the chat)",
        )
    }

    @Test
    fun the_behaviour_tests_of_the_rule_still_exist() {
        for (name in listOf("ModeSwitchAsksBeforeEveryPaymentTest", "PeerModeSwitchIsAdoptedOnBothPhonesTest")) {
            val file =
                File(ChatResourceText.repoRoot, "ui-lib/src/test/java/co/electriccoin/zcash/ui/screen/chat/viewmodel/$name.kt")
            assertTrue(file.isFile, "$name was removed")
        }
    }
}
