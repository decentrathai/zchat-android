package co.electriccoin.zcash.ui.call

import co.electriccoin.zcash.ui.screen.chat.view.ChatResourceText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * ZC-1936 (GPT cross-family review of 3fc413a7c, Fable finding 2): IncomingCallActivity.neededPermissions asks only
 * for what is missing, but the callback requires result[RECORD_AUDIO] == true. With the microphone already allowed and
 * the camera missing, a video Answer asks only for CAMERA, the result has no RECORD_AUDIO, and the call is hung up as
 * PermissionDenied ("Microphone permission needed") although the microphone is allowed.
 *
 * Wanted: a pure rule `CallLockScreen.micGrantedAfterRequest(result, micGrantedNow)`: the microphone counts as allowed
 * when the phone says so now (ContextCompat.checkSelfPermission) or the request granted it. A refused camera never
 * ends the call: the call is answered with this phone's camera off (as ZC-1904: the camera stays off until a tap).
 */
class Zc1936MicAlreadyAllowedVideoAnswerTest {
    private fun micGranted(
        result: Map<String, Boolean>,
        now: Boolean,
    ): Boolean = CallSourceBlocks.objectCall(POLICY, "micGrantedAfterRequest", result, now) as Boolean

    @Test
    fun `a video answer that asked only for the camera keeps the allowed microphone`() {
        assertEquals(true, micGranted(mapOf(CAMERA to false), true), "ZC-1936: camera refused, mic was allowed")
        assertEquals(true, micGranted(mapOf(CAMERA to true), true), "ZC-1936: camera allowed, mic was allowed")
        assertEquals(true, micGranted(emptyMap(), true), "ZC-1936: nothing asked, mic allowed")
    }

    @Test
    fun `a refused microphone still counts as refused`() {
        assertEquals(false, micGranted(mapOf(MIC to false), false), "ZC-1922: mic refused")
        assertEquals(false, micGranted(mapOf(MIC to false, CAMERA to true), false), "ZC-1922: mic refused, camera ok")
        assertEquals(false, micGranted(emptyMap(), false), "ZC-1922: dialog dismissed")
        assertEquals(true, micGranted(mapOf(MIC to true), false), "granted by the request")
    }

    @Test
    fun `the call screen reads the microphone state from the phone after the request`() {
        val activity = ChatResourceText.source(CALL_ACTIVITY)
        assertTrue("CallLockScreen.micGrantedAfterRequest(" in activity, "ZC-1936: the activity asks the rule")
        assertTrue("checkSelfPermission" in activity, "ZC-1936: the real permission state is read")
        assertTrue(
            "result[Manifest.permission.RECORD_AUDIO] == true" !in activity,
            "ZC-1936: the result alone must not decide (it lacks RECORD_AUDIO when only the camera was asked)",
        )
    }

    private companion object {
        const val POLICY = "co.electriccoin.zcash.ui.call.CallLockScreen"
        const val CALL_ACTIVITY = "ui-lib/src/main/java/co/electriccoin/zcash/ui/call/IncomingCallActivity.kt"
        const val MIC = "android.permission.RECORD_AUDIO"
        const val CAMERA = "android.permission.CAMERA"
    }
}
