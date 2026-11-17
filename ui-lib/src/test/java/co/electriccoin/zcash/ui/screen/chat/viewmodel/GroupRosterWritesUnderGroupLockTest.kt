package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.screen.chat.view.ChatResourceText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Modifier

/**
 * KI-22 — source pins for the group change locks in GroupViewModel.
 *
 * 1. The change lock (the D5 rotation lock, now also held by add, resend, leave, the owed-key pass and introductions)
 *    is ONE per group for the whole process: at 565fb3c14 `rotationLocks` is a field of each GroupViewModel, and the
 *    group screen and the group settings screen each have their own instance.
 * 2. Every roster write GroupViewModel makes (`saveGroupMembers` / `saveGroupMembersDurably`) is a fresh
 *    read-modify-write inside the process-wide group lock (`withGroupLock` / `synchronized(ZchatPreferences.groupLock)`),
 *    the lock ChatViewModel's inbound GROUP_ACCEPT / LEAVE / KICK / KEY handlers hold. At 565fb3c14 createGroup,
 *    setMemberInviteStatus, addMemberToGroup, recordKeyTxIds, recordKeyOwed, leaveGroup and the rotation commit write
 *    outside it.
 * 3. Lock order: the change Mutex is taken before the group lock, never inside it, and the group lock is never held
 *    across a suspension. No group-lock block in GroupViewModel contains a suspend call.
 *
 * Control: the scan finds the blocks and the writes it should (so an empty scan cannot pass).
 */
class GroupRosterWritesUnderGroupLockTest {
    private val path = "ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/chat/viewmodel/GroupViewModel.kt"
    private val source by lazy { ChatResourceText.source(path) }

    /** Every group-lock block's body range (after its opening brace to its closing brace). */
    private fun lockBlocks(): List<IntRange> =
        Regex("""(withGroupLock\([^()]*\)|synchronized\(ZchatPreferences\.groupLock\([^()]*\)\))\s*\{""")
            .findAll(source)
            .map { m ->
                val open = m.range.last
                var depth = 0
                var i = open
                while (i < source.length) {
                    when (source[i]) {
                        '{' -> depth++
                        '}' -> {
                            depth--
                            if (depth == 0) break
                        }
                    }
                    i++
                }
                (open + 1) until i
            }.toList()

    /** Code only: line comments and KDoc/block comments blanked, so a word in a comment is not a call. */
    private fun code(range: IntRange): String =
        source
            .substring(range.first, range.last + 1)
            .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), " ")
            .replace(Regex("""//[^\n]*"""), " ")

    @Test
    fun `the change lock of a group is one per process, not one per screen`() {
        val field = GroupViewModel::class.java.getDeclaredField("rotationLocks")
        assertTrue(
            "rotationLocks is a field of each GroupViewModel instance: the group screen and the group settings screen " +
                "each lock their own copy, so their changes of the same group don't exclude each other",
            Modifier.isStatic(field.modifiers),
        )
    }

    @Test
    fun `every roster write in GroupViewModel is inside the process-wide group lock`() {
        val blocks = lockBlocks()
        assertTrue("control: the scan finds the group-lock blocks", blocks.size >= 3)
        val writes = Regex("""\.saveGroupMembers(Durably)?\(""").findAll(source).map { it.range.first }.toList()
        assertTrue("control: the scan finds the roster writes", writes.size >= 7)
        val outside =
            writes
                .filter { at -> blocks.none { at in it } }
                .map { at -> source.substring(0, at).count { it == '\n' } + 1 }
        assertEquals(
            "roster writes outside the group lock (GroupViewModel.kt lines): a GROUP_LEAVE / GROUP_ACCEPT stored by " +
                "ChatViewModel meanwhile is overwritten",
            emptyList<Int>(),
            outside,
        )
    }

    @Test
    fun `no group-lock block suspends`() {
        val suspending =
            listOf(
                "sendGroupMemoWithRetry",
                "delay(",
                "withLock",
                "withGroupChange",
                ".await(",
                "rotateAndNotify(",
                "retryPendingGroupKeysInternal(",
                "payIntroductions(",
                "launch",
                "withContext",
                "loadGroupMessagesFromHistory",
                "keystoneAccountSelected",
                "suspendCancellableCoroutine",
                ".lock(",
            )
        val offending =
            lockBlocks().flatMap { range ->
                val body = code(range)
                suspending.filter { body.contains(it) }.map { "line ${source.substring(0, range.first).count { c -> c == '\n' } + 1}: $it" }
            }
        assertEquals(
            "a group-lock block contains a suspend call (the group lock must never be held across a suspension)",
            emptyList<String>(),
            offending
        )
    }
}
