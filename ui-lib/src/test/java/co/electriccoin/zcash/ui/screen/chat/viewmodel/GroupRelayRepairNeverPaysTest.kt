package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.screen.chat.view.ChatResourceText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ZC-420-G (fix-all 2.13.0, train T6b; ZC-420-SPEC §3.2 R4, §3.5, §3.6, §4.3, §6) — the group relay-copy repair can never
 * reach a paying function, and the Resend of a not-confirmed invite never reaches a price question.
 *
 * The repair re-delivers group message copies, keys, removals and invites over the relays only, through
 * NostrChatBridge.repairRelayCopy. Its functions in GroupViewModel (`repairUnconfirmedGroupCopies` and every helper whose
 * name starts the same way, `resendUnconfirmedInvite`) and the memo builders it shares with the senders
 * (`groupMessageCopyMemo`, `resyncKeyMemo`, `removalNoticeMemo`) name none of the paying functions
 * (createChunkedMessageProposal, sendGroupMemoWithRetry, sendMemoWithBlockRetry, sendAutomaticHandshakeMemo*,
 * sendNostrBootHandshake, payOwedKex, sendKEXMessage) nor the price questions (askForQuotedPlan, askBeforePaying,
 * askForQuotedGroupMessage). The public resendInvite takes a marked row to resendUnconfirmedInvite before it reaches the
 * price question; loadGroupDetail runs the repair after the owed-key pass's `if` block, on retryOwedKeys alone.
 * PaidSendCallSiteLockTest's allowlist names none of them. A source scan of code: comments are left out.
 */
class GroupRelayRepairNeverPaysTest {
    private companion object {
        const val GVM = "ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/chat/viewmodel/GroupViewModel.kt"
        const val LOCK = "ui-lib/src/test/java/co/electriccoin/zcash/ui/screen/chat/viewmodel/PaidSendCallSiteLockTest.kt"
        const val REPAIR_PREFIX = "repairUnconfirmedGroup"
        val BUILDERS = setOf("resendUnconfirmedInvite", "groupMessageCopyMemo", "resyncKeyMemo", "removalNoticeMemo")
        val PAYING =
            listOf(
                "createChunkedMessageProposal",
                "sendGroupMemoWithRetry",
                "sendMemoWithBlockRetry",
                "sendAutomaticHandshakeMemo",
                "sendNostrBootHandshake",
                "payOwedKex",
                "sendKEXMessage",
                "askForQuotedPlan",
                "askBeforePaying",
                "askForQuotedGroupMessage",
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
    fun `the group repair, the not-confirmed Resend and their memo builders name no paying function and no price question`() {
        val all = functions(ChatResourceText.source(GVM))
        val repair = all.filter { it.first.startsWith(REPAIR_PREFIX) || it.first in BUILDERS }
        val names = repair.map { it.first }.toSet()
        assertTrue(
            "the repair does not exist: $names (repairUnconfirmedGroupCopies, ${BUILDERS.joinToString()})",
            names.containsAll(BUILDERS + "repairUnconfirmedGroupCopies"),
        )
        val problems =
            repair.flatMap { (name, body) ->
                val text = code(body)
                PAYING.filter { Regex("""\b$it\w*\s*\(""").containsMatchIn(text) }.map { "$name calls $it" }
            }
        assertEquals("the repair can reach a paying function or a price question", emptyList<String>(), problems)
    }

    @Test
    fun `a marked row's Resend goes to resendUnconfirmedInvite before the price question`() {
        val resend =
            functions(ChatResourceText.source(GVM))
                .filter { it.first == "resendInvite" }
                .map { code(it.second) }
                .firstOrNull { "askForQuotedPlan(" in it }
        assertTrue("fixture: the public resendInvite with its price question", resend != null)
        val free = resend!!.indexOf("resendUnconfirmedInvite(")
        assertTrue("the public resendInvite never takes a not-confirmed row to the free Resend", free >= 0)
        assertTrue("the free Resend is reached only after the price question", free < resend.indexOf("askForQuotedPlan("))
    }

    @Test
    fun `the group open runs the repair after the owed-key pass, on retryOwedKeys alone`() {
        val load =
            functions(ChatResourceText.source(GVM))
                .filter { it.first == "loadGroupDetail" }
                .map { code(it.second) }
                .firstOrNull { "retryPendingGroupKeysInternal(" in it }
        assertTrue("fixture: the private loadGroupDetail with the owed-key pass", load != null)
        val owed = load!!.indexOf("retryPendingGroupKeysInternal(")
        val repair = load.indexOf("repairUnconfirmedGroupCopies(")
        assertTrue("the group open does not run the repair", repair >= 0)
        assertTrue("the repair runs before the owed-key pass", repair > owed)
        assertTrue(
            "the repair sits inside the owed-key block, which needs an owed member",
            load.substring(owed, repair).lines().any { it == "}" },
        )
        val call = load.lines().first { "repairUnconfirmedGroupCopies(" in it }
        assertTrue("the repair is not on retryOwedKeys alone: $call", "retryOwedKeys" in call && "pendingKeyEpoch" !in call)
    }

    @Test
    fun `the paid call-site allowlist names none of the repair functions`() {
        val lock = ChatResourceText.source(LOCK)
        val named = (BUILDERS + REPAIR_PREFIX).filter { it in lock }
        assertEquals("PaidSendCallSiteLockTest lists a repair function", emptyList<String>(), named)
    }
}
