package co.electriccoin.zcash.ui.call

import co.electriccoin.zcash.ui.screen.chat.view.ChatResourceText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * ZC-1912 (owner test 16 Nov, W-148 "Call screen over lock"): at 65f4f1323 an incoming call with the screen off only
 * vibrates. Nothing sets showWhenLocked or turnScreenOn (none in app/src or ui-lib/src/main); the full-screen intent
 * targets the plain launcher intent (CallNotificationController.activityPendingIntent); the card's Answer opens
 * MainActivity, which needs an unlock; on Android 14+ canUseFullScreenIntent() is only logged
 * (AndroidCallRingPlayer.logIncomingCallAlerts).
 *
 * Wanted:
 * - a dedicated call-only activity, `co.electriccoin.zcash.ui.call.IncomingCallActivity` (not exported), that shows the
 *   call screen (CallOverlay) over the lock screen and turns the screen on only while a call rings or runs
 *   (`CallLockScreen.showOverLock(state)`), then clears both and finishes. The wallet (MainActivity) never shows over
 *   the lock screen;
 * - the full-screen intent opens IncomingCallActivity;
 * - the card's Answer for a voice call, on a locked phone with the microphone allowed, is the CallActionReceiver
 *   ACTION_ACCEPT broadcast (no unlock): `CallLockScreen.answerByBroadcast(isVideo, micGranted, keyguardLocked, fullScreenIntentAllowed)`;
 *   otherwise it opens IncomingCallActivity, which answers. The receiver never opens the wallet. Decline is unchanged;
 * - on Android 14+ with the full-screen intent not allowed (`CallLockScreen.needsFullScreenIntentGuide(sdk, canUse)`),
 *   the app guides the user to Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, with texts in English and Spanish.
 *
 * - HONOR device evidence (owner test 16 Nov, owner-test-20261116/honor/appops_fsi.txt, notification_call_fsi.txt): the
 *   USE_FULL_SCREEN_INTENT app-op is denied by default although the permission is granted; every call card was posted
 *   FSI_REQUESTED_BUT_DENIED and the screen stayed off. So the guide asks once, and while it is denied a ringing call
 *   still wakes the screen (a timed ACQUIRE_CAUSES_WAKEUP wake lock in AndroidCallRingPlayer) and the service tries to
 *   open the call screen itself (allowed where the phone lets ZChat open windows from the background);
 * - ZC-1922 (same device): RECORD_AUDIO was never granted. Answering from the card or the lock screen asks for the
 *   microphone in IncomingCallActivity; refused, the call ends with the "Microphone permission needed" notice
 *   (CallEndReason.PermissionDenied) instead of going silent, and the receiver never accepts without the microphone.
 *
 * Seams are reached by name, so this class compiles before the fix. A locked phone is the device check.
 */
class Zc1912IncomingCallOverLockScreenTest {
    private val peer = "c".repeat(64)
    private val callId = "1f".repeat(16)

    private fun policy(
        method: String,
        vararg args: Any?,
    ): Boolean = CallSourceBlocks.objectCall(POLICY, method, *args) as Boolean

    @Test
    fun `the call screen shows over the lock screen only while a call rings or runs`() {
        val live =
            listOf(
                VoiceCallManager.CallState.Ringing(peer, callId),
                VoiceCallManager.CallState.Dialling(peer, callId),
                VoiceCallManager.CallState.Connecting(peer, callId),
                VoiceCallManager.CallState.InCall(peer, callId),
            )
        live.forEach { assertEquals(true, policy("showOverLock", it), "ZC-1912: over the lock screen in $it") }
        listOf(VoiceCallManager.CallState.Idle, VoiceCallManager.CallState.Ended(CallEndReason.UserEnded)).forEach {
            assertEquals(false, policy("showOverLock", it), "ZC-1912: never over the lock screen in $it")
        }
    }

    @Test
    fun `Answer for a voice call on a locked phone is the accept broadcast`() {
        assertEquals(true, policy("answerByBroadcast", false, true, true, true), "ZC-1912: voice, mic allowed, locked: no unlock")
        assertEquals(false, policy("answerByBroadcast", true, true, true, true), "video opens the call screen")
        assertEquals(false, policy("answerByBroadcast", false, false, true, true), "no microphone permission: the screen asks")
        assertEquals(false, policy("answerByBroadcast", false, true, false, true), "unlocked: the call screen comes forward")
    }

    @Test
    fun `the full-screen permission guide shows on Android 14+ only when the permission is off`() {
        assertEquals(true, policy("needsFullScreenIntentGuide", 34, false), "ZC-1912: API 34, not allowed")
        assertEquals(false, policy("needsFullScreenIntentGuide", 34, true), "allowed")
        assertEquals(false, policy("needsFullScreenIntentGuide", 35, null), "unknown: no nag")
        assertEquals(false, policy("needsFullScreenIntentGuide", 33, false), "before Android 14 it is always allowed")
    }

    @Test
    fun `a dedicated call activity, not the wallet, shows over the lock screen`() {
        val manifest = ChatResourceText.source(MANIFEST)
        val entry =
            Regex("""<activity\b[^>]*android:name="\.call\.IncomingCallActivity"[^>]*>""", RegexOption.DOT_MATCHES_ALL)
                .find(manifest)
                ?.value ?: fail("ZC-1912: IncomingCallActivity is not in the ui-lib manifest")
        assertTrue("""android:exported="false"""" in entry, "ZC-1912: the call activity is not exported")
        val main =
            Regex("""<activity\b[^>]*android:name="\.MainActivity"[^>]*>""", RegexOption.DOT_MATCHES_ALL)
                .find(manifest)
                ?.value ?: fail("MainActivity entry")
        assertTrue("showWhenLocked" !in main && "turnScreenOn" !in main, "ZC-1912: the wallet never shows over the lock screen")
        val mainKt = ChatResourceText.source(MAIN_ACTIVITY)
        assertTrue("setShowWhenLocked" !in mainKt && "setTurnScreenOn" !in mainKt, "ZC-1912: MainActivity stays behind the lock")

        val activity =
            runCatching { ChatResourceText.source(CALL_ACTIVITY) }.getOrNull()
                ?: fail("ZC-1912: $CALL_ACTIVITY does not exist")
        listOf("setShowWhenLocked(true)", "setTurnScreenOn(true)", "setShowWhenLocked(false)", "setTurnScreenOn(false)")
            .forEach { assertTrue(it in activity, "ZC-1912: IncomingCallActivity must call $it") }
        assertTrue("CallLockScreen.showOverLock(" in activity, "ZC-1912: only while a call rings or runs")
        assertTrue("CallOverlay(" in activity, "ZC-1912: it shows the call screen")
        assertTrue("finish()" in activity, "ZC-1912: it closes when the call is over")
        assertTrue("MainActivity" !in activity && "MainContent" !in activity, "ZC-1912: no wallet over the lock screen")
    }

    @Test
    fun `the full-screen intent opens the call activity and Answer accepts without an unlock`() {
        val src = ChatResourceText.source(CallSourceBlocks.NOTIFICATIONS)
        val fullScreen = CallSourceBlocks.block(src, "activityPendingIntent", "    ")
        assertTrue("getLaunchIntentForPackage" !in fullScreen, "ZC-1912: the full-screen intent must not be the launcher intent")
        assertTrue("IncomingCallActivity" in fullScreen, "ZC-1912: the full-screen intent opens IncomingCallActivity")
        val show = CallSourceBlocks.block(src, "showIncoming", "    ")
        assertTrue("CallActionReceiver.ACTION_ACCEPT" in show, "ZC-1912: voice Answer is the accept broadcast")
        assertTrue("CallLockScreen.answerByBroadcast(" in show, "ZC-1912: the rule decides the Answer target")
        assertTrue("receiverPendingIntent(CallActionReceiver.ACTION_DECLINE" in show, "Decline stays the receiver")

        val receiver = ChatResourceText.source(RECEIVER)
        assertTrue("getLaunchIntentForPackage" !in receiver, "ZC-1912: Answer never opens the wallet")
    }

    @Test
    fun `Android 14+ guides the user to the full-screen permission`() {
        val main = ChatResourceText.source(MAIN_ACTIVITY)
        assertTrue("FullScreenIntentPrompt(" in main, "ZC-1912: the app hosts the full-screen permission guide")
        val guide =
            runCatching { ChatResourceText.source(GUIDE) }.getOrNull() ?: fail("ZC-1912: $GUIDE does not exist")
        assertTrue("ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT" in guide, "ZC-1912: open the system setting")
        assertTrue("CallLockScreen.needsFullScreenIntentGuide(" in guide, "ZC-1912: only when it is off on Android 14+")

        val en = ChatResourceText.strings("values")
        val es = ChatResourceText.strings("values-es")
        listOf("call_fsi_prompt_title", "call_fsi_prompt_body", "call_fsi_prompt_open", "call_fsi_prompt_later").forEach { key ->
            val e = en[key] ?: fail("ZC-1912: no English $key")
            val s = es[key] ?: fail("ZC-1912: no Spanish $key")
            assertTrue(e != s, "ZC-1912: $key is not translated")
            assertTrue("R.string.$key" in guide, "ZC-1912: the guide uses $key")
        }
    }

    @Test
    fun `with the full-screen intent denied a ringing call still wakes the screen and tries the call screen`() {
        val ring = ChatResourceText.source(RING_PLAYER)
        assertTrue("ACQUIRE_CAUSES_WAKEUP" in ring, "ZC-1912: a denied full-screen intent must still wake the screen")
        assertTrue("CallLockScreen.needsFullScreenIntentGuide(" in ring, "ZC-1912: wake only when the full-screen intent is denied")
        val show = CallSourceBlocks.block(ChatResourceText.source(CallSourceBlocks.NOTIFICATIONS), "showIncoming", "    ")
        assertTrue("CallLockScreen.needsFullScreenIntentGuide(" in show, "ZC-1912: denied: open the call screen directly")
        assertTrue("startActivity(" in show, "ZC-1912: denied: open the call screen directly")
    }

    @Test
    fun `answering from the card or the lock screen asks for the microphone`() {
        val activity =
            runCatching { ChatResourceText.source(CALL_ACTIVITY) }.getOrNull()
                ?: fail("ZC-1912: $CALL_ACTIVITY does not exist")
        assertTrue("RECORD_AUDIO" in activity, "ZC-1922: the call screen asks for the microphone before answering")
        assertTrue("RequestMultiplePermissions" in activity, "ZC-1922: a real permission request")
        assertTrue("CallEndReason.PermissionDenied" in activity, "ZC-1922: refused: the call ends with a notice")
        val receiver = ChatResourceText.source(RECEIVER)
        assertTrue("RECORD_AUDIO" in receiver, "ZC-1922: the receiver never accepts without the microphone")
    }

    private companion object {
        const val RING_PLAYER = "ui-lib/src/main/java/co/electriccoin/zcash/ui/call/AndroidCallRingPlayer.kt"
        const val POLICY = "co.electriccoin.zcash.ui.call.CallLockScreen"
        const val MANIFEST = "ui-lib/src/main/AndroidManifest.xml"
        const val MAIN_ACTIVITY = "ui-lib/src/main/java/co/electriccoin/zcash/ui/MainActivity.kt"
        const val CALL_ACTIVITY = "ui-lib/src/main/java/co/electriccoin/zcash/ui/call/IncomingCallActivity.kt"
        const val RECEIVER = "ui-lib/src/main/java/co/electriccoin/zcash/ui/call/CallActionReceiver.kt"
        const val GUIDE = "ui-lib/src/main/java/co/electriccoin/zcash/ui/call/FullScreenIntentPrompt.kt"
    }
}
