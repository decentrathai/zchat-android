package co.electriccoin.zcash.ui.screen.chat.view

import org.junit.Test
import java.lang.reflect.InvocationTargetException
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * G3 fixup (train T4b cross review, finding G-G3-2): on the group screen, Join's "You joined" result sticks for the
 * whole visit.
 *
 * At 22a2263f0 AndroidGroupDetail keeps Join's result in `joinOutcome` (remember(groupId)) and never clears it while the
 * screen is open. When the Join reply goes back to waiting for Join (the relays failed and the wallet cannot pay it
 * yet: returnGroupAcceptToJoin writes the needs-join record again), the screen still says "You joined «G»" and keeps
 * Join disabled, so the retry the design promises ("the user can tap Join again") is blocked on that visit.
 *
 * Wanted: a pure rule, `joinOutcomeAfterRecordRead(outcome, awaitingJoin)` in GroupDetailView.kt, clears a true outcome
 * once the needs-join record is back and keeps every other value; AndroidGroupDetail applies it whenever the record or
 * the outcome changes. The rule is reached by name, so this file compiles before the fix; the screen is read as source,
 * as the other copy tests of this package do.
 */
class GroupJoinOutcomeClearsWhenJoinWaitsAgainTest {
    private fun rule(
        outcome: Boolean?,
        awaitingJoin: Boolean,
    ): Boolean? {
        val method =
            runCatching {
                Class
                    .forName("co.electriccoin.zcash.ui.screen.chat.view.GroupDetailViewKt")
                    .getDeclaredMethod("joinOutcomeAfterRecordRead", Boolean::class.javaObjectType, Boolean::class.javaPrimitiveType)
                    .apply { isAccessible = true }
            }.getOrNull()
        assertNotNull(method, "GroupDetailView.kt has no joinOutcomeAfterRecordRead(Boolean?, Boolean)")
        return try {
            method.invoke(null, outcome, awaitingJoin) as Boolean?
        } catch (e: InvocationTargetException) {
            throw e.targetException
        }
    }

    @Test
    fun a_joined_outcome_is_cleared_when_the_invite_waits_for_join_again() {
        assertEquals(null, rule(outcome = true, awaitingJoin = true), "\"You joined\" stays while the invite waits for Join again")
    }

    @Test
    fun every_other_outcome_is_kept() {
        assertEquals(true, rule(outcome = true, awaitingJoin = false), "a Join whose reply is on its way lost its result")
        assertEquals(false, rule(outcome = false, awaitingJoin = true), "a failed Join lost its notice")
        assertEquals(false, rule(outcome = false, awaitingJoin = false))
        assertEquals(null, rule(outcome = null, awaitingJoin = true))
        assertEquals(null, rule(outcome = null, awaitingJoin = false))
    }

    @Test
    fun the_group_screen_applies_the_rule_when_the_record_or_the_outcome_changes() {
        val src = ChatResourceText.source("ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/chat/AndroidChat.kt")
        val start = src.indexOf("fun AndroidGroupDetail(")
        val end = src.indexOf("private fun GroupInviteJoinQuestions(")
        assertTrue(start >= 0 && end > start, "fixture: AndroidGroupDetail and GroupInviteJoinQuestions in AndroidChat.kt")
        val screen = src.substring(start, end).replace(Regex("""\s+"""), "")
        assertTrue(
            "LaunchedEffect(joinRecord,joinOutcome){joinOutcome=joinOutcomeAfterRecordRead(joinOutcome,joinRecord!=null)}" in screen,
            "AndroidGroupDetail does not clear Join's result when the needs-join record comes back",
        )
    }
}
