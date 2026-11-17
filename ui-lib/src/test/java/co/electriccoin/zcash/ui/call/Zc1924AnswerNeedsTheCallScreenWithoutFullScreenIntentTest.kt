package co.electriccoin.zcash.ui.call

import co.electriccoin.zcash.ui.screen.chat.view.ChatResourceText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * ZC-1924 (Fable review of 3fc413a7c, now high): with the full-screen intent denied (HONOR's default, target 35) the
 * card's broadcast Answer (CallActionReceiver ACTION_ACCEPT) connects a voice call with no call screen and no Hang up:
 * Android 12+ refuses a screen opened from a notification-action receiver, and without the full-screen intent no call
 * screen is over the lock screen either.
 *
 * Superseded in part by ZC-1964: without the full-screen intent the voice Answer is the broadcast as well (no unlock),
 * with an ongoing-call notification for Hang up. The rest below stands.
 *
 * Wanted: `CallLockScreen.answerByBroadcast(isVideo, micGranted, keyguardLocked, fullScreenIntentAllowed)` is true only
 * when the full-screen intent is allowed (true; on API < 34 it always is) and the phone is locked and the microphone is
 * allowed and the call is voice; unknown (null) counts as not allowed. The Answer target is fixed when the card is
 * posted, so showIncoming decides it then from fullScreenIntentAllowed(). Otherwise Answer opens IncomingCallActivity.
 */
class Zc1924AnswerNeedsTheCallScreenWithoutFullScreenIntentTest {
    private fun broadcast(
        isVideo: Boolean,
        mic: Boolean,
        locked: Boolean,
        fsi: Boolean?,
    ): Boolean = CallSourceBlocks.objectCall(POLICY, "answerByBroadcast", isVideo, mic, locked, fsi) as Boolean

    // ZC-1964 (owner W-148, device round 1): an activity Answer asks for the PIN on a locked phone, so the call never
    // connected. Without the full-screen intent the voice Answer is the broadcast too, and the ongoing-call notification
    // gives the Hang up this test asked for; both pinned in Zc1964VoiceAnswerOverTheLockNeedsNoUnlockTest.
    @Test
    fun `with the full-screen intent the voice Answer is the broadcast`() {
        assertEquals(true, broadcast(false, true, true, true), "allowed, locked, voice, mic: the broadcast answers")
    }

    @Test
    fun `the other rules stay`() {
        assertEquals(false, broadcast(true, true, true, true), "video opens the call screen")
        assertEquals(false, broadcast(false, false, true, true), "no microphone: the screen asks")
        assertEquals(false, broadcast(false, true, false, true), "unlocked: the call screen comes forward")
    }

    @Test
    fun `the card decides the Answer target from the full-screen permission`() {
        val show = CallSourceBlocks.block(ChatResourceText.source(CallSourceBlocks.NOTIFICATIONS), "showIncoming", "    ")
        val call = show.substringAfter("CallLockScreen.answerByBroadcast(", missingDelimiterValue = "").substringBefore("\n")
        assertTrue("fullScreenIntentAllowed()" in call, "ZC-1924: showIncoming passes fullScreenIntentAllowed() ($call)")
    }

    private companion object {
        const val POLICY = "co.electriccoin.zcash.ui.call.CallLockScreen"
    }
}
