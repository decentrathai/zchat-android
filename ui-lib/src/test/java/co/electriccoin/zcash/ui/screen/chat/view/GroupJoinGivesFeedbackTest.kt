package co.electriccoin.zcash.ui.screen.chat.view

import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * G3 8a (fix-all 2.13.0, train T4b; groups SPEC §3.1, §3.5 J1 with review G) — Join on a group invitation gives no
 * feedback.
 *
 * At HEAD, AndroidGroupDetail reads the needs-join record in `remember(groupId, groupDetailState)`. Join clears the mark
 * and calls loadGroupDetail, which sets Loading and then a Success equal to the one before with no suspension between
 * them, so the StateFlow collector sees no change, the record is not read again and the banner stays (on the Fold,
 * until a message arrived about 40 s later). Join's Boolean is thrown away, so a tap that failed says nothing either.
 *
 * Wanted: the record is also keyed on a counter Join changes (`joinTick`); Join keeps its result in `joinOutcome`, where
 * a later `false` (a second tap: the mark is already cleared) never replaces a `true`; the group screen gets that outcome
 * and shows `group_join_sent` or `group_join_failed`; and Join is disabled once `joinOutcome == true`.
 *
 * ZC-517: the tap is bound to the record that supplied its price or relay-only banner. A released relay-only reply
 * gets no immediate successful outcome: it has not gone out yet. The rendered outcome also applies the existing
 * waiting-record clear rule synchronously, so a returned reply never displays stale success for one frame. A later
 * false still cannot replace a true while no record waits. The source checks below retain the counter, reload,
 * forwarding, success/failure text and disabled-Join protections.
 *
 * Reads the views' source, as the other copy tests of this package do.
 */
class GroupJoinGivesFeedbackTest {
    /** AndroidGroupDetail, from its declaration to the next composable. */
    private val screen: String by lazy {
        val src = ChatResourceText.source("ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/chat/AndroidChat.kt")
        val start = src.indexOf("fun AndroidGroupDetail(")
        val end = src.indexOf("private fun GroupInviteJoinQuestions(")
        assertTrue(start >= 0 && end > start, "fixture: AndroidGroupDetail and GroupInviteJoinQuestions in AndroidChat.kt")
        src.substring(start, end)
    }

    private val view: String by lazy {
        ChatResourceText.source("ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/chat/view/GroupDetailView.kt")
    }

    /** The banner composable of GroupDetailView, from its declaration to the next top-level doc comment. */
    private val banner: String by lazy {
        val start = view.indexOf("private fun GroupInviteRequestBanner(")
        val end = view.indexOf("\n/**", start)
        assertTrue(start >= 0 && end > start, "fixture: GroupInviteRequestBanner in GroupDetailView.kt")
        view.substring(start, end)
    }

    private fun compact(text: String): String = text.replace(Regex("""\s+"""), "")

    @Test
    fun the_join_record_is_read_again_after_join() {
        assertTrue(
            Regex("""var\s+joinTick\s+by\s+remember\(\s*groupId\s*\)\s*\{\s*mutableIntStateOf\(0\)\s*}""").containsMatchIn(screen),
            "AndroidGroupDetail has no joinTick counter for Join to change",
        )
        val recordKeys =
            Regex("""val\s+joinRecord\s*=\s*remember\(([^)]*)\)""").find(screen)?.groupValues?.get(1)
        assertTrue(recordKeys != null, "fixture: the joinRecord remember")
        assertTrue(
            "joinTick" in recordKeys,
            "the join record is keyed only on ($recordKeys): an equal reload after Join never reads it again",
        )
    }

    @Test
    fun join_keeps_its_result_and_a_later_false_never_replaces_a_true() {
        assertTrue(
            Regex("""var\s+joinOutcome\s+by\s+remember\(\s*groupId\s*\)\s*\{\s*mutableStateOf<Boolean\?>\(null\)\s*}""")
                .containsMatchIn(screen),
            "AndroidGroupDetail does not keep Join's result (joinOutcome)",
        )
        val start = screen.indexOf("onJoinInvite = {")
        assertTrue(start >= 0, "fixture: onJoinInvite in AndroidGroupDetail")
        val handler = compact(screen.substring(start, screen.indexOf("onIgnoreInvite", start)))
        assertTrue(
            "valshownJoinOutcome=joinOutcomeAfterRecordRead(joinOutcome,awaitingJoin)" in compact(screen),
            "Join must clear a rendered success synchronously when its record waits again",
        )
        val expectedHandler =
            "if(joinOutcome!=true){" +
                "valreleased=inviteChatViewModel?.joinGroupInvite(groupId,joinRecord)==true" +
                "joinOutcome=if(released&&joinReplyIsRelayOnly)nullelsereleased}"
        assertTrue(
            expectedHandler in handler,
            "Join must bind its displayed record, retain fitting results and suppress relay-only success: $handler",
        )
        assertTrue("joinTick++" in handler, "Join does not change joinTick: $handler")
        assertTrue("viewModel.loadGroupDetail(groupId)" in handler, "Join no longer reloads the group: $handler")
        assertTrue(
            "joinOutcome=shownJoinOutcome" in compact(screen),
            "the group screen is not given the synchronously normalized Join outcome",
        )
    }

    @Test
    fun the_group_screen_shows_the_outcome_and_disables_join_once_joined() {
        assertTrue(
            Regex("""joinOutcome:\s*Boolean\?\s*=\s*null""").containsMatchIn(view),
            "GroupDetailView takes no joinOutcome",
        )
        assertTrue("R.string.group_join_sent" in view, "GroupDetailView never shows group_join_sent")
        assertTrue("R.string.group_join_failed" in view, "GroupDetailView never shows group_join_failed")
        assertTrue(
            Regex("""TextButton\(\s*onClick\s*=\s*onJoin\s*,\s*enabled\s*=\s*joinEnabled\s*\)""").containsMatchIn(banner),
            "the Join button is not disabled by joinEnabled",
        )
        assertTrue(
            "joinEnabled=joinOutcome!=true" in compact(view),
            "Join must be disabled once joinOutcome is true",
        )
        assertFalse(
            "group_join_sent" in banner || "group_join_failed" in banner,
            "the outcome must show outside the banner (the banner goes away after a successful Join)",
        )
    }
}
