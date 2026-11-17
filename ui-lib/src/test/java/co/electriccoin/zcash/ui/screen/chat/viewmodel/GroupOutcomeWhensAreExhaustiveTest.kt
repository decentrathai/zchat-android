package co.electriccoin.zcash.ui.screen.chat.viewmodel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * F8-G (ZC-112 part 2; SPEC §3.7) — source scans of GroupViewModel: every place that reads what a group memo send did
 * has its own PENDING branch, and the ledger is wired in.
 *
 * PENDING means "an earlier attempt of this memo is unresolved, or waits for the user's Resend: this call built and
 * paid nothing, and the caller changes nothing". A `when` with an `else` would send a new outcome down some existing
 * branch (a failed badge, an owed mark, a delivered count) without anyone deciding it.
 */
class GroupOutcomeWhensAreExhaustiveTest {
    private val source = LedgerSources.main("ui/screen/chat/viewmodel/GroupViewModel.kt")

    /** The body of each `when (outcome) {` (and of the one `when (payAnnouncePair(...)) {`), to its closing brace. */
    private fun whenBlocks(): List<String> {
        val starts = Regex("""when \((outcome|payAnnouncePair\(.*\))\) \{""").findAll(source).map { it.range.last + 1 }.toList()
        return starts.map { start ->
            var depth = 1
            var i = start
            while (depth > 0) {
                when (source[i]) {
                    '{' -> depth++
                    '}' -> depth--
                }
                i++
            }
            source.substring(start, i - 1)
        }
    }

    private fun indentOf(line: String): Int = line.takeWhile { it == ' ' }.length

    @Test
    fun `each of the six outcome whens has a PENDING branch and no else`() {
        val blocks = whenBlocks()
        assertEquals("the five `when (outcome)` blocks and the introduce tally", 6, blocks.size)
        for (block in blocks) {
            assertTrue("a `when` over a group memo outcome has no PENDING branch:\n$block", block.contains("GroupMemoOutcome.PENDING ->"))
            val lines = block.lines()
            val branchIndent = indentOf(lines.first { it.contains("GroupMemoOutcome.") })
            val topLevelElse = lines.any { it.trim().startsWith("else ->") && indentOf(it) == branchIndent }
            assertTrue("a `when` over a group memo outcome has an else branch:\n$block", !topLevelElse)
        }
    }

    @Test
    fun `the outcome has the four values and PENDING writes no invite badge`() {
        val enum = source.substringAfter("private enum class GroupMemoOutcome {").substringBefore("\n    }\n")
        for (value in listOf("SENT,", "NOTHING_SENT,", "MAYBE_SENT,", "PENDING,")) assertTrue(value, enum.contains(value))
        assertTrue("PENDING has an invite badge", enum.contains("PENDING -> null"))
    }

    @Test
    fun `the ledger is a constructor parameter Koin hands in, and the funnel records an attempt before the SDK is asked`() {
        assertTrue(source.contains("private val sendAttempts: SendAttemptEnvironment? = null,"))
        assertTrue(LedgerSources.main("di/ViewModelModule.kt").contains("viewModelOf(::GroupViewModel)"))
        val binding = "single<co.electriccoin.zcash.ui.common.sendattempt.SendAttemptEnvironment>"
        assertTrue(LedgerSources.main("di/DataSourceModule.kt").contains(binding))
        val funnel = source.substringAfter("private suspend fun sendGroupMemoWithRetry(").substringBefore("private fun networkTip()")
        val relay = funnel.indexOf("trySendGroupMemoOverNostr(memberAddress, inviteMemo)")
        val consent = funnel.indexOf("if (!mayPayOnChain())")
        val gate = funnel.indexOf("capture.gate()")
        val proposal = funnel.indexOf("createChunkedMessageProposal(")
        val begin = funnel.indexOf("capture?.begin()")
        val stamp = funnel.indexOf("beforeSubmit?.invoke()")
        assertTrue(
            "the gate sits after the free relay route and the consent, before the proposal",
            relay in 0 until consent && consent < gate && gate < proposal,
        )
        assertTrue("the attempt is recorded before the caller's own stamp", proposal < begin && begin < stamp)
        assertEquals(
            "the membership is read again before the relay copy and before every on-chain attempt",
            2,
            Regex("""if \(!stillWanted\(\)\)""").findAll(funnel).count(),
        )
        assertTrue(funnel.indexOf("if (!stillWanted())") < relay)
        assertTrue("every exit of an attempt closes it", funnel.contains("} finally {\n                capture?.close()"))
    }

    @Test
    fun `the relay send of GroupViewModel refuses on a Keystone account, and the debug hold is the release-gated seam`() {
        val relaySend =
            source
                .substringAfter("private suspend fun trySendGroupMemoOverNostr(")
                .substringBefore("private fun findPeerNostrPubkeyAnyRep(")
        val check = relaySend.indexOf("if (keystoneAccountSelected()) return false")
        assertTrue(check in 0 until relaySend.indexOf("NostrChatBridge.publish("))
        assertEquals("one debug seam read in the funnel, through FaultSeams", 1, Regex("""FaultSeams\.\w+\(""").findAll(source).count())
        assertTrue(source.contains("FaultSeams.delayIfArmed(FaultSeam.GROUP_SEND_DELAY_MS)"))
        val seams = LedgerSources.main("ui/common/util/FaultSeams.kt")
        assertTrue(
            "the hold is unreachable in a release build",
            seams.substringAfter("suspend fun delayIfArmed(").substringBefore("}").contains("if (!BuildConfig.DEBUG) return"),
        )
    }
}
