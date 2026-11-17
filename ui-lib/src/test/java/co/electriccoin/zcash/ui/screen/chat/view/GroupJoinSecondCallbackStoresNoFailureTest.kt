package co.electriccoin.zcash.ui.screen.chat.view

import org.junit.Test
import java.lang.reflect.InvocationTargetException
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * ZC-776 (review of 12 Oct 2026, finding 4) — a second Join callback shows "Couldn't join … Nothing was sent" although
 * the reply went out.
 *
 * Since ZC-517 a released relay-only Join keeps no result (`joinOutcome` stays null: the reply has not gone out yet).
 * The live `joinOutcome != true` guard therefore lets a second callback through before the screen has read the record
 * again. That call is still bound to the record the screen showed, finds it gone (the first call released it), returns
 * false, and the false is kept and shown as "Couldn't join «G». Nothing was sent". No payment and no lost reply: a
 * false failure text.
 *
 * Wanted: Join is taken once per read of the needs-join record. AndroidGroupDetail builds one `JoinTapGate`
 * (GroupDetailView.kt) each time it reads the record, on the same keys; the first Join callback on that read passes
 * (`firstTap()`), every later one on the same read neither calls Join nor stores a result. When the screen reads the
 * record again (after Join, or when the reply went back to waiting) it builds a new gate, so Join can be tapped again.
 *
 * The callback itself lives in a composable, so, as the other tests of this package do, the screen is read as source
 * and the pure parts are run: [Composition] runs the Join callback as AndroidGroupDetail's `onJoinInvite` is written
 * (its inner block is the one GroupJoinGivesFeedbackTest pins), with the product's gate when the build has one and
 * without it when it has none, against a store that behaves as the record-bound Join does (ChatViewModel
 * .joinGroupInvite(groupId, expectedRecord): no record, or another record than the one shown, releases nothing).
 */
class GroupJoinSecondCallbackStoresNoFailureTest {
    @Test
    fun two_join_callbacks_on_one_read_of_a_relay_only_record_release_once_and_show_no_failure() {
        val store = Store(RECORD)
        val state = ScreenState()
        val shown = Composition(store, state, replyIsRelayOnly = true)

        shown.onJoinInvite()
        shown.onJoinInvite()

        assertEquals(1, store.releases, "the reply was not released exactly once")
        assertEquals(
            null,
            outcomeShown(state.joinOutcome, awaitingJoin = store.record != null),
            "the screen says \"Couldn't join … Nothing was sent\" although this screen released the reply",
        )
        assertEquals(1, store.calls, "the second callback asked Join again for a record this screen had already released")
        assertEquals(2, state.joinTick, "each callback still makes the screen read the record again")
    }

    @Test
    fun two_join_callbacks_on_one_read_of_a_fitting_record_release_once_and_keep_you_joined() {
        val store = Store(RECORD)
        val state = ScreenState()
        val shown = Composition(store, state, replyIsRelayOnly = false)

        shown.onJoinInvite()
        shown.onJoinInvite()

        assertEquals(1, store.releases, "the reply was not released exactly once")
        assertEquals(true, outcomeShown(state.joinOutcome, awaitingJoin = store.record != null), "\"You joined\" was lost")
        assertEquals(1, store.calls, "the second callback asked Join again")
    }

    @Test
    fun control_join_can_be_tapped_again_once_the_screen_has_read_the_returned_record() {
        val store = Store(RECORD)
        val state = ScreenState()
        Composition(store, state, replyIsRelayOnly = true).onJoinInvite()
        assertEquals(1, store.releases, "fixture: the first tap releases the reply")

        // The relay try failed: the reply went back to waiting, and the screen read the record again.
        store.record = RECORD
        val again = Composition(store, state, replyIsRelayOnly = true)
        again.onJoinInvite()

        assertEquals(2, store.releases, "Join stays blocked after its record came back and was read again")
        assertEquals(null, outcomeShown(state.joinOutcome, awaitingJoin = store.record != null))
    }

    @Test
    fun control_a_join_that_released_nothing_still_says_so() {
        // The record this screen shows was taken elsewhere (another screen joined or ignored it) before the tap.
        val store = Store(RECORD)
        val state = ScreenState()
        val shown = Composition(store, state, replyIsRelayOnly = true)
        store.record = null

        shown.onJoinInvite()

        assertEquals(0, store.releases)
        assertEquals(
            false,
            outcomeShown(state.joinOutcome, awaitingJoin = false),
            "a Join that released nothing must still show \"Couldn't join … Nothing was sent\"",
        )
    }

    @Test
    fun the_gate_passes_the_first_tap_only_and_a_new_gate_passes_again() {
        val gate = newGate()
        assertNotNull(gate, "GroupDetailView.kt has no JoinTapGate")
        assertTrue(firstTap(gate), "the first Join callback on a read was refused")
        assertTrue(!firstTap(gate), "a second Join callback on the same read passed")
        assertTrue(!firstTap(gate), "a third Join callback on the same read passed")
        assertTrue(firstTap(newGate()!!), "a new read of the record did not open Join again")
    }

    @Test
    fun the_group_screen_builds_one_gate_per_read_of_the_record_and_join_passes_through_it() {
        val src = compact(screen)
        val recordKeys = Regex("""valjoinRecord=remember\(([^)]*)\)""").find(src)?.groupValues?.get(1)
        assertNotNull(recordKeys, "fixture: the remembered Join record")
        val gateKeys = Regex("""valjoinTapGate=remember\(([^)]*)\)\{JoinTapGate\(\)}""").find(src)?.groupValues?.get(1)
        assertNotNull(gateKeys, "AndroidGroupDetail builds no JoinTapGate")
        assertEquals(
            recordKeys,
            gateKeys,
            "the gate is not rebuilt exactly when the record is read again: Join could stay blocked, or open too early",
        )
        val start = screen.indexOf("onJoinInvite = {")
        assertTrue(start >= 0, "fixture: onJoinInvite in AndroidGroupDetail")
        val handler = compact(screen.substring(start, screen.indexOf("onIgnoreInvite", start)))
        assertTrue(
            (
                "if(joinTapGate.firstTap()){if(joinOutcome!=true){" +
                    "valreleased=inviteChatViewModel?.joinGroupInvite(groupId,joinRecord)==true" +
                    "joinOutcome=if(released&&joinReplyIsRelayOnly)nullelsereleased}}"
            ) in handler,
            "Join does not pass through the gate of the record it was shown for: $handler",
        )
        // What the screen shows for a kept false is the failure text (the model above stands on this).
        val view = ChatResourceText.source("ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/chat/view/GroupDetailView.kt")
        assertTrue(
            "if(joinOutcome)R.string.group_join_sentelseR.string.group_join_failed" in compact(view),
            "fixture: GroupDetailView shows group_join_failed for a false Join outcome",
        )
    }

    // ------------------------------------------------------------------ harness

    /** AndroidGroupDetail, from its declaration to the next composable. */
    private val screen: String by lazy {
        val src = ChatResourceText.source("ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/chat/AndroidChat.kt")
        val start = src.indexOf("fun AndroidGroupDetail(")
        val end = src.indexOf("private fun GroupInviteJoinQuestions(")
        assertTrue(start >= 0 && end > start, "fixture: AndroidGroupDetail and GroupInviteJoinQuestions in AndroidChat.kt")
        src.substring(start, end)
    }

    private fun compact(text: String): String = text.replace(Regex("""\s+"""), "")

    /** The needs-join record as the record-bound Join meets it. */
    private class Store(
        var record: String?,
    ) {
        var calls = 0
        var releases = 0

        /** ChatViewModel.joinGroupInvite(groupId, expectedRecord): only the stored record the screen showed is released. */
        fun join(expectedRecord: String?): Boolean {
            calls++
            if (expectedRecord == null || record == null || record != expectedRecord) return false
            record = null
            releases++
            return true
        }
    }

    /** What AndroidGroupDetail remembers for the whole visit (`remember(groupId)`), read live by its callbacks. */
    private class ScreenState {
        var joinOutcome: Boolean? = null
        var joinTick = 0
    }

    /**
     * One read of the record by AndroidGroupDetail and the Join callback built on it. The callback captures the record
     * and the gate of this read and reads [ScreenState] live, as the composable's lambda does.
     */
    private inner class Composition(
        private val store: Store,
        private val state: ScreenState,
        private val replyIsRelayOnly: Boolean,
    ) {
        private val joinRecord: String? = store.record
        private val joinTapGate: Any? = newGate()

        fun onJoinInvite() {
            if (joinTapGate == null || firstTap(joinTapGate)) {
                if (state.joinOutcome != true) {
                    val released = store.join(joinRecord)
                    state.joinOutcome = if (released && replyIsRelayOnly) null else released
                }
            }
            state.joinTick++
        }
    }

    /** A new JoinTapGate of this build, or null when the build has none. */
    private fun newGate(): Any? =
        runCatching { Class.forName("co.electriccoin.zcash.ui.screen.chat.view.JoinTapGate") }
            .getOrNull()
            ?.getDeclaredConstructor()
            ?.apply { isAccessible = true }
            ?.newInstance()

    private fun firstTap(gate: Any): Boolean =
        try {
            gate.javaClass
                .getDeclaredMethod("firstTap")
                .apply { isAccessible = true }
                .invoke(gate) as Boolean
        } catch (e: InvocationTargetException) {
            throw e.targetException
        }

    /** The outcome the view is given: the screen's own rule over the kept outcome and whether a record waits. */
    private fun outcomeShown(
        outcome: Boolean?,
        awaitingJoin: Boolean,
    ): Boolean? =
        try {
            Class
                .forName("co.electriccoin.zcash.ui.screen.chat.view.GroupDetailViewKt")
                .getDeclaredMethod("joinOutcomeAfterRecordRead", Boolean::class.javaObjectType, Boolean::class.javaPrimitiveType)
                .apply { isAccessible = true }
                .invoke(null, outcome, awaitingJoin) as Boolean?
        } catch (e: InvocationTargetException) {
            throw e.targetException
        }

    private companion object {
        const val RECORD = "{\"synthetic\":\"needs-join record of ZC-776\"}"
    }
}
