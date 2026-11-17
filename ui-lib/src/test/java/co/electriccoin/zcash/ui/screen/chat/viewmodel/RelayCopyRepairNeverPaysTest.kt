package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.screen.chat.view.ChatResourceText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ZC-420 (fix-all 2.13.0, train T6b; ZC-420-SPEC §3.2 R4, §3.4 items 4 and 5, §4.2, §6): the relay-identity repair can
 * never reach a paying function.
 *
 * The repair re-delivers our relay identity over the relays only. Its functions in ChatViewModel
 * (`repairUnconfirmedRelayIdentity`, the tick's `repairUnconfirmedRelayIdentities`, every helper whose name starts the
 * same way, and the memo builder `ownZbootMemo`) name none of the paying functions: createChunkedMessageProposal,
 * sendGroupMemoWithRetry, sendMemoWithBlockRetry, sendAutomaticHandshakeMemo*, sendNostrBootHandshake, payOwedKex,
 * sendKEXMessage. The identity repair may run the free rotation announce, `announceRotationOverNostr`, which is not one
 * of them. Its triggers: AndroidChat's chat-open effect calls the repair right after retryOwedKeyExchange, outside the
 * mode check; onChainTip calls the tick. PaidSendCallSiteLockTest's allowlist names none of them. A source scan of code:
 * comments are left out, so a KDoc that names a function does not count.
 */
class RelayCopyRepairNeverPaysTest {
    private companion object {
        const val CVM = "ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/chat/viewmodel/ChatViewModel.kt"
        const val AC = "ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/chat/AndroidChat.kt"
        const val LOCK = "ui-lib/src/test/java/co/electriccoin/zcash/ui/screen/chat/viewmodel/PaidSendCallSiteLockTest.kt"
        const val REPAIR_PREFIX = "repairUnconfirmedRelayIdentit"
        val PAYING =
            listOf(
                "createChunkedMessageProposal",
                "sendGroupMemoWithRetry",
                "sendMemoWithBlockRetry",
                "sendAutomaticHandshakeMemo",
                "sendNostrBootHandshake",
                "payOwedKex",
                "sendKEXMessage",
            )
        val MEMBER_FUN =
            Regex(
                """\n    (?:(?:private|internal|public|override|suspend|inline)\s+)*fun\s+(?:<[^>]*>\s*)?([A-Za-z0-9_]+)\s*\(""",
            )
    }

    private fun functions(source: String): List<Pair<String, String>> {
        val starts = MEMBER_FUN.findAll(source).toList()
        return starts.mapIndexed { i, m ->
            val end = if (i + 1 < starts.size) starts[i + 1].range.first else source.length
            m.groupValues[1] to source.substring(m.range.first, end)
        }
    }

    /** [text] without comment lines and trailing line comments. */
    private fun code(text: String): String =
        text
            .lines()
            .map { it.trim() }
            .filterNot { it.startsWith("*") || it.startsWith("/*") || it.startsWith("//") }
            .joinToString("\n") { it.substringBefore(" // ") }

    @Test
    fun `the identity repair and its memo builder name no paying function`() {
        val all = functions(ChatResourceText.source(CVM))
        val repair = all.filter { it.first.startsWith(REPAIR_PREFIX) || it.first == "ownZbootMemo" }
        val names = repair.map { it.first }.toSet()
        assertTrue(
            "the repair does not exist: $names (repairUnconfirmedRelayIdentity, repairUnconfirmedRelayIdentities, ownZbootMemo)",
            names.containsAll(setOf("repairUnconfirmedRelayIdentity", "repairUnconfirmedRelayIdentities", "ownZbootMemo")),
        )
        val problems =
            repair.flatMap { (name, body) ->
                val text = code(body)
                PAYING.filter { Regex("""\b$it\w*\s*\(""").containsMatchIn(text) }.map { "$name calls $it" }
            }
        assertEquals("the repair can reach a paying function", emptyList<String>(), problems)
    }

    @Test
    fun `the chat-open effect calls the repair after the owed key exchange and outside the mode check`() {
        val source = ChatResourceText.source(AC)
        val owed = source.indexOf("viewModel.retryOwedKeyExchange(peerAddress)")
        assertTrue("fixture: the chat-open effect's owed key exchange", owed >= 0)
        val modeCheck = source.indexOf("if (zchatPreferences.getConversationMode(peerAddress) !=", owed)
        assertTrue("fixture: the chat-open effect's mode check", modeCheck > owed)
        val repair = source.indexOf("viewModel.repairUnconfirmedRelayIdentity(peerAddress)")
        assertTrue("the chat-open effect does not call the relay-identity repair", repair >= 0)
        assertTrue("the repair is not right after the owed key exchange, before (outside) the mode check", repair in owed until modeCheck)
    }

    @Test
    fun `each block runs the tick repair`() {
        val onChainTip = functions(ChatResourceText.source(CVM)).single { it.first == "onChainTip" }.second
        assertTrue("onChainTip does not run the tick repair", "repairUnconfirmedRelayIdentities(height)" in code(onChainTip))
    }

    @Test
    fun `the paid call-site allowlist names none of the repair functions`() {
        val lock = ChatResourceText.source(LOCK)
        assertTrue("PaidSendCallSiteLockTest lists a repair function", REPAIR_PREFIX !in lock && "ownZbootMemo" !in lock)
    }
}
