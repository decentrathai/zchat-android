package co.electriccoin.zcash.ui.call

import co.electriccoin.zcash.ui.call.CallSourceBlocks.NOTIFICATIONS
import co.electriccoin.zcash.ui.call.CallSourceBlocks.SERVICE
import co.electriccoin.zcash.ui.call.CallSourceBlocks.block
import co.electriccoin.zcash.ui.call.CallSourceBlocks.objectCall
import co.electriccoin.zcash.ui.call.CallSourceBlocks.source
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * ZC-1964 (owner W-148 "Call screen over lock. Answer works for voice without unlocking"; device round 1 of 2.13.0,
 * HONOR with a PIN lock and the full-screen intent not allowed): Answer on the lock-screen card opened "Enter PIN", no
 * ZChat screen started, the call never connected and rang out.
 *
 * Cause: since ZC-1924 `CallLockScreen.answerByBroadcast` is false whenever the full-screen intent is not allowed, so
 * showIncoming makes Answer an activity PendingIntent (IncomingCallActivity), and SystemUI dismisses the keyguard (asks
 * for the PIN) before it sends an activity PendingIntent from a notification action. A broadcast PendingIntent
 * (CallActionReceiver ACTION_ACCEPT) is sent without an unlock.
 *
 * Wanted:
 *  - a voice Answer on a locked phone with the microphone allowed is the accept broadcast whether or not the full-screen
 *    intent is allowed (true, false or unknown); video (camera) and an unlocked phone keep the call screen;
 *  - ZC-1924's worry (a call connected with no Hang up when no call screen is over the lock) is answered by an
 *    ongoing-call notification: `internal object OngoingCallNotice { fun wanted(state: VoiceCallManager.CallState,
 *    appInForeground: Boolean): Boolean }` is true for a Connecting or InCall call while our screen is not in front;
 *    CallNotificationController.showOngoing posts a CallStyle.forOngoingCall card whose Hang up is a CallActionReceiver
 *    broadcast (ACTION_HANG_UP, works on the lock screen), and SyncForegroundService posts/cancels it from that rule.
 */
class Zc1964VoiceAnswerOverTheLockNeedsNoUnlockTest {
    private fun broadcast(
        isVideo: Boolean,
        mic: Boolean,
        locked: Boolean,
        fsi: Boolean?,
    ): Boolean = objectCall(POLICY, "answerByBroadcast", isVideo, mic, locked, fsi) as Boolean

    private fun ongoing(
        state: VoiceCallManager.CallState,
        inFront: Boolean,
    ): Boolean = objectCall(ONGOING, "wanted", state, inFront) as Boolean

    @Test
    fun `a voice Answer on a locked phone is the broadcast without the full-screen intent too`() {
        assertEquals(
            true,
            broadcast(isVideo = false, mic = true, locked = true, fsi = false),
            "ZC-1964: full-screen intent not allowed (HONOR): voice Answer must be the broadcast, not an activity " +
                "PendingIntent that asks for the PIN",
        )
        assertEquals(
            true,
            broadcast(isVideo = false, mic = true, locked = true, fsi = null),
            "ZC-1964: full-screen permission unknown: voice Answer must still be the broadcast",
        )
        assertEquals(true, broadcast(isVideo = false, mic = true, locked = true, fsi = true), "allowed: the broadcast")
    }

    @Test
    fun `video and an unlocked phone keep the call screen`() {
        assertEquals(false, broadcast(isVideo = true, mic = true, locked = true, fsi = false), "video may ask to unlock")
        assertEquals(false, broadcast(isVideo = true, mic = true, locked = true, fsi = true), "video opens the screen")
        assertEquals(false, broadcast(isVideo = false, mic = false, locked = true, fsi = false), "no mic: screen asks")
        assertEquals(false, broadcast(isVideo = false, mic = true, locked = false, fsi = false), "unlocked: screen")
    }

    @Test
    fun `a call answered away from our screen has an ongoing-call notification`() {
        val connecting = VoiceCallManager.CallState.Connecting(PEER_KEY, CALL_ID)
        val inCall = VoiceCallManager.CallState.InCall(PEER_KEY, CALL_ID)
        assertTrue(ongoing(connecting, inFront = false), "ZC-1964: connecting from the lock-screen card: Hang up shown")
        assertTrue(ongoing(inCall, inFront = false), "ZC-1964: in call with no call screen in front: Hang up shown")
        assertEquals(false, ongoing(inCall, inFront = true), "our call screen in front has its own Hang up")
        val others =
            listOf(
                VoiceCallManager.CallState.Idle,
                VoiceCallManager.CallState.Ringing(PEER_KEY, CALL_ID),
                VoiceCallManager.CallState.Ended(CallEndReason.UserEnded),
            )
        others.forEach { assertEquals(false, ongoing(it, inFront = false), "no ongoing card for $it") }
    }

    @Test
    fun `the ongoing-call notification hangs up through the receiver`() {
        val notifications = source(NOTIFICATIONS)
        val show = block(notifications, "showOngoing", "    ")
        assertTrue("CallStyle.forOngoingCall(" in show, "ZC-1964: showOngoing posts a CallStyle.forOngoingCall card")
        assertTrue(
            "receiverPendingIntent(CallActionReceiver.ACTION_HANG_UP" in show,
            "ZC-1964: its Hang up is a CallActionReceiver broadcast (no unlock), never an activity PendingIntent",
        )
        val hangUpAction =
            runCatching { CallActionReceiver::class.java.getField("ACTION_HANG_UP").get(null) as String }
                .getOrElse { fail("ZC-1964: CallActionReceiver has no ACTION_HANG_UP") }
        assertTrue(hangUpAction.startsWith("co.electriccoin.zcash.call."), "the action is namespaced ($hangUpAction)")
        val receiver = source(RECEIVER)
        val branch = receiver.substringAfter("ACTION_HANG_UP ->", missingDelimiterValue = "").substringBefore("\n")
        assertTrue("hangUp(" in branch, "ZC-1964: ACTION_HANG_UP ends the call ($branch)")
        val service = source(SERVICE)
        assertTrue(
            "OngoingCallNotice.wanted(" in service || "OngoingCallNotice.cardFor(" in service,
            "ZC-1964: the service decides the ongoing card by the rule (wanted, or ZC-1981's cardFor built on it)",
        )
        assertTrue("showOngoing(" in service, "ZC-1964: the service posts the ongoing card")
    }

    private companion object {
        const val POLICY = "co.electriccoin.zcash.ui.call.CallLockScreen"
        const val ONGOING = "co.electriccoin.zcash.ui.call.OngoingCallNotice"
        const val RECEIVER = "ui-lib/src/main/java/co/electriccoin/zcash/ui/call/CallActionReceiver.kt"
        const val PEER_KEY = "ab"
        const val CALL_ID = "c1"
    }
}
