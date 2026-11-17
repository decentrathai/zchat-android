package co.electriccoin.zcash.ui.call

import co.electriccoin.zcash.ui.call.CallSourceBlocks.NOTIFICATIONS
import co.electriccoin.zcash.ui.call.CallSourceBlocks.SERVICE
import co.electriccoin.zcash.ui.call.CallSourceBlocks.block
import co.electriccoin.zcash.ui.call.CallSourceBlocks.objectCall
import co.electriccoin.zcash.ui.call.CallSourceBlocks.source
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * QA2 Q2-74 (ZC-1657, owner B-6 under W-137), Q2-72 (ZC-1655), Q2-79 (ZC-1658).
 *
 * At 33bb92cdf SyncForegroundService posts the system incoming-call card (CallStyle, PRIORITY_MAX, full-screen
 * intent) for every Ringing state, also while ZChat is in front: the phone shows our full-screen Decline/Accept and
 * the system card's Decline/Answer together (P3E seeker_02, honor_08). The card's title is overridden by CallStyle's
 * Person, and setContentText(callerDisplay) repeats the name, so no line says "incoming call" (seeker_12/13); the
 * Person has no icon, so the avatar is a lone letter.
 *
 * Wanted (FIX_PLAN_W137 B-6): `object IncomingCallNotice { fun wanted(state: VoiceCallManager.CallState,
 * appInForeground: Boolean): Boolean }` in this package: true only for Ringing with the app not in front. The
 * service posts the card when it turns true (also when the app goes to the background mid-ring) and cancels it when
 * it turns false (the app comes back, or the ringing ends). The ring controller is not changed. The card's text
 * says "Incoming voice call"/"Incoming video call" from the existing resources, and the Person carries an icon.
 */
class IncomingCallNoticeOnlyWhenOurScreenIsNotInFrontTest {
    private fun wanted(
        state: VoiceCallManager.CallState,
        appInForeground: Boolean,
    ): Boolean = objectCall("co.electriccoin.zcash.ui.call.IncomingCallNotice", "wanted", state, appInForeground) as Boolean

    @Test
    fun `a ringing call with the app in the background posts the system card`() {
        assertTrue(wanted(VoiceCallManager.CallState.Ringing(PEER_KEY, CALL_ID, isVideo = false), appInForeground = false))
        assertTrue(wanted(VoiceCallManager.CallState.Ringing(PEER_KEY, CALL_ID, isVideo = true), appInForeground = false))
    }

    @Test
    fun `a ringing call with our screen in front posts no system card`() {
        assertFalse(wanted(VoiceCallManager.CallState.Ringing(PEER_KEY, CALL_ID, isVideo = false), appInForeground = true))
        assertFalse(wanted(VoiceCallManager.CallState.Ringing(PEER_KEY, CALL_ID, isVideo = true), appInForeground = true))
    }

    @Test
    fun `no other call state posts the incoming card`() {
        val states =
            listOf(
                VoiceCallManager.CallState.Idle,
                VoiceCallManager.CallState.Dialling(PEER_KEY, CALL_ID),
                VoiceCallManager.CallState.Connecting(PEER_KEY, CALL_ID),
                VoiceCallManager.CallState.InCall(PEER_KEY, CALL_ID),
                VoiceCallManager.CallState.Ended(CallEndReason.Timeout),
            )
        for (state in states) {
            for (fg in listOf(true, false)) assertFalse(wanted(state, fg), "$state (foreground=$fg) must not post the card")
        }
    }

    @Test
    fun `the service decides the card through IncomingCallNotice and follows the foreground state`() {
        val service = source(SERVICE)
        assertTrue("IncomingCallNotice.wanted(" in service, "SyncForegroundService still posts the card for every Ringing state")
    }

    @Test
    fun `the card's text says incoming call, not the caller's name again`() {
        val show = block(source(NOTIFICATIONS), "showIncoming", indent = "    ")
        assertFalse(".setContentText(callerDisplay)" in show, "the card's subtitle repeats the caller's name")
        assertTrue(
            Regex("""setContentText\([^\n]*R\.string\.call_incoming_""").containsMatchIn(show),
            "the card's text must be call_incoming_voice / call_incoming_video",
        )
    }

    @Test
    fun `the caller Person on the card has an icon`() {
        val show = block(source(NOTIFICATIONS), "showIncoming", indent = "    ")
        assertTrue(".setIcon(" in show, "the heads-up avatar is a lone letter: the caller Person needs an icon")
    }

    private companion object {
        const val PEER_KEY = "93c9d47100f8aa11bb22cc33dd44ee55ff6677889900aabbccddeeff00112233"
        const val CALL_ID = "0123456789abcdef0123456789abcdef"
    }
}
