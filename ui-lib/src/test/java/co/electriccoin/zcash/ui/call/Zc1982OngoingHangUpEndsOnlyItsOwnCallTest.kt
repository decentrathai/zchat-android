package co.electriccoin.zcash.ui.call

import co.electriccoin.zcash.ui.screen.chat.view.ChatResourceText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * ZC-1982 (Fable + GPT review of the ZC-1964 fix, medium): the ongoing-call card's Hang up is
 * `receiverPendingIntent(CallActionReceiver.ACTION_HANG_UP, REQ_HANG_UP)` with no call id, and the receiver's
 * ACTION_HANG_UP calls `mgr.hangUp(UserEnded)` on whatever call is current. A late tap on call A's card ends call B.
 * After a process death the stale card's Hang up reaches a receiver with no VoiceCallManager, which only logs and
 * returns: the card stays up for a call that no longer exists.
 *
 * Wanted:
 *  - showOngoing takes the call id and its Hang up carries it, as an extra and as the intent's data (receiverPendingIntent
 *    with the call id, like the Answer since ZC-1951/ZC-1956), so two calls' Hang up PendingIntents differ; the service
 *    passes the live call's id;
 *  - a pure rule `OngoingCallNotice.hangUpTap(cardCallId: String?, state: VoiceCallManager.CallState?)` (null state: no
 *    call manager) returns an enum: HANG_UP only for the card's own Connecting or InCall call; IGNORE while another call
 *    is connecting or live (its own card stays); CANCEL_CARD when no live call has the card's id (ended, idle, no manager);
 *  - the receiver asks that rule before it gives up on a missing manager, hangs up only on HANG_UP and cancels the
 *    ongoing card on CANCEL_CARD.
 */
class Zc1982OngoingHangUpEndsOnlyItsOwnCallTest {
    private fun tap(
        cardCallId: String?,
        state: VoiceCallManager.CallState?,
    ): String = (CallSourceBlocks.objectCall(ONGOING, "hangUpTap", cardCallId, state) as Enum<*>).name

    @Test
    fun `a Hang up tap ends only the call its card was posted for`() {
        assertEquals("HANG_UP", tap(CALL_A, VoiceCallManager.CallState.InCall(PEER, CALL_A)), "ZC-1982: A's card, A live")
        assertEquals(
            "HANG_UP",
            tap(CALL_A, VoiceCallManager.CallState.Connecting(PEER, CALL_A)),
            "ZC-1982: A's card, A connecting",
        )
        assertEquals(
            "IGNORE",
            tap(CALL_A, VoiceCallManager.CallState.InCall(PEER, CALL_B)),
            "ZC-1982: a late tap on A's card never ends call B (and leaves B's card)",
        )
        assertEquals(
            "IGNORE",
            tap(CALL_A, VoiceCallManager.CallState.Connecting(PEER, CALL_B)),
            "ZC-1982: a late tap on A's card never ends connecting call B",
        )
        assertEquals(
            "IGNORE",
            tap(null, VoiceCallManager.CallState.InCall(PEER, CALL_B)),
            "ZC-1982: a card with no call id never ends a live call",
        )
    }

    @Test
    fun `a stale card with no live call is cancelled`() {
        assertEquals("CANCEL_CARD", tap(CALL_A, null), "ZC-1982: after a process death (no call manager) the card goes")
        listOf(
            VoiceCallManager.CallState.Idle,
            VoiceCallManager.CallState.Ended(CallEndReason.UserEnded),
            VoiceCallManager.CallState.Ringing(PEER, CALL_B),
            VoiceCallManager.CallState.Dialling(PEER, CALL_B),
        ).forEach { assertEquals("CANCEL_CARD", tap(CALL_A, it), "ZC-1982: A no longer live ($it): A's card goes") }
        assertEquals("CANCEL_CARD", tap(null, null), "ZC-1982: no id and no manager: the card goes")
    }

    @Test
    fun `the Hang up carries its call id to a receiver that asks the rule`() {
        val notifications = CallSourceBlocks.source(CallSourceBlocks.NOTIFICATIONS)
        val show = CallSourceBlocks.block(notifications, "showOngoing", "    ")
        assertTrue("callId: String" in show, "ZC-1982: showOngoing takes the call id")
        assertTrue(
            Regex("""receiverPendingIntent\(\s*CallActionReceiver\.ACTION_HANG_UP[^)]*callId""").containsMatchIn(show),
            "ZC-1982: the Hang up broadcast carries the call id (extra and data, so each call has its own PendingIntent)",
        )
        val service = CallSourceBlocks.source(CallSourceBlocks.SERVICE)
        assertTrue(
            Regex("""showOngoing\([^)]*callId""").containsMatchIn(service),
            "ZC-1982: the service passes the live call's id to the ongoing card",
        )

        val receiver = ChatResourceText.source(RECEIVER)
        val rule = receiver.indexOf("OngoingCallNotice.hangUpTap(")
        assertTrue(rule >= 0, "ZC-1982: the receiver asks OngoingCallNotice.hangUpTap for ACTION_HANG_UP")
        val giveUp = receiver.indexOf("no active VoiceCallManager")
        assertTrue(
            giveUp < 0 || rule < giveUp,
            "ZC-1982: a Hang up reaching a process with no call manager still cancels the stale card (rule before the " +
                "early return)",
        )
        assertTrue("cancelOngoing(" in receiver, "ZC-1982: the receiver cancels a stale ongoing card")
        assertTrue(
            !Regex("""ACTION_HANG_UP\s*->\s*mgr\.hangUp\(""").containsMatchIn(receiver),
            "ZC-1982: ACTION_HANG_UP no longer ends whatever call is current",
        )
    }

    private companion object {
        const val ONGOING = "co.electriccoin.zcash.ui.call.OngoingCallNotice"
        const val RECEIVER = "ui-lib/src/main/java/co/electriccoin/zcash/ui/call/CallActionReceiver.kt"
        const val PEER = "ab"
        const val CALL_A = "a1"
        const val CALL_B = "b2"
    }
}
