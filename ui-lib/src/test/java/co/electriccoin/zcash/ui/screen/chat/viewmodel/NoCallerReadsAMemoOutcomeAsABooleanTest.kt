package co.electriccoin.zcash.ui.screen.chat.viewmodel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * F8 (ZC-112; SPEC §3.7) — source scan: PENDING can never pass as "sent" or as "not sent" by default.
 *
 * The Boolean form of the automatic sender is gone, its callers read the outcome, and every `when` over a memo
 * outcome, a key-reply outcome or an owed-KEX result names each case (no `else` branch that a new outcome falls into).
 */
class NoCallerReadsAMemoOutcomeAsABooleanTest {
    private val source = LedgerSources.chatViewModel

    @Test
    fun `the Boolean automatic sender is gone and its four callers read the outcome`() {
        assertTrue(
            "the Boolean sendAutomaticHandshakeMemo must not exist",
            !Regex("""fun sendAutomaticHandshakeMemo\(""").containsMatchIn(source),
        )
        assertTrue("no caller of the Boolean form is left", !Regex("""[^\[]\bsendAutomaticHandshakeMemo\(""").containsMatchIn(source))
        // Every `sendAutomaticHandshakeMemoOutcome(` but the definition itself.
        val calls = Regex("""\bsendAutomaticHandshakeMemoOutcome\(""").findAll(source).count() - 1
        assertEquals("the KEX, the key reply, the ZBOOT and the group control memo call the outcome form", 4, calls)
    }

    @Test
    fun `every when over an outcome names PENDING and has no else`() {
        val outcomes =
            listOf(
                "MemoSendOutcome" to "MemoSendOutcome.PENDING",
                "KexAckOutcome" to "KexAckOutcome.PENDING",
                "OwedKex" to "OwedKex.Pending",
            )
        for ((type, pending) in outcomes) {
            val blocks = whenBlocksNaming(type)
            assertTrue("sanity: ChatViewModel has when blocks over $type", blocks.isNotEmpty())
            for (block in blocks) {
                assertTrue("a when over $type must name $pending:\n$block", block.contains(pending))
                assertTrue(
                    "a when over $type must not have an else branch:\n$block",
                    !Regex("""\n\s*else\s*->""").containsMatchIn(topLevelOf(block)),
                )
            }
        }
    }

    /** Every `when (...) { ... }` block whose branches name a constant of [type] at the block's own level. */
    private fun whenBlocksNaming(type: String): List<String> {
        val blocks = mutableListOf<String>()
        for (match in Regex("""\bwhen\s*(\([^\n]*\))?\s*\{""").findAll(source)) {
            var depth = 0
            var end = -1
            for (i in match.range.last until source.length) {
                when (source[i]) {
                    '{' -> depth++
                    '}' -> if (--depth == 0) end = i
                }
                if (end >= 0) break
            }
            if (end < 0) continue
            val block = source.substring(match.range.first, end + 1)
            val branches = Regex("""\n\s*(is )?$type\.\w+[^\n]*->""").findAll(topLevelOf(block)).count()
            if (branches >= 2) blocks += block
        }
        return blocks
    }

    /** [block] with the content of its nested braces removed, so only its own branches are read. */
    private fun topLevelOf(block: String): String {
        val out = StringBuilder()
        var depth = 0
        for (c in block) {
            if (c == '{') depth++
            if (depth <= 1 || (depth == 2 && c == '{')) out.append(c)
            if (c == '}') {
                depth--
                if (depth == 1) out.append(c)
            }
        }
        return out.toString()
    }
}
