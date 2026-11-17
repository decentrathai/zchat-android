package co.electriccoin.zcash.ui.call

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * ZC-1950 (review of the ZC-1936 fix): the in-app call screen (CallOverlay RingingScreen) still decides on
 * result[RECORD_AUDIO] == true. The request asks only for what is missing, and an interrupted or cancelled request
 * returns an empty map, so with the microphone allowed and the camera missing a video Answer is hung up as
 * PermissionDenied ("Microphone permission needed").
 *
 * Wanted: RingingScreen asks the same rule as IncomingCallActivity, `CallLockScreen.micGrantedAfterRequest(result,
 * micGrantedNow)`, with micGrantedNow from checkSelfPermission(RECORD_AUDIO).
 */
class Zc1950InAppAnswerReadsTheMicFromThePhoneTest {
    private fun micGranted(
        result: Map<String, Boolean>,
        now: Boolean,
    ): Boolean = CallSourceBlocks.objectCall(POLICY, "micGrantedAfterRequest", result, now) as Boolean

    @Test
    fun `an empty or camera-only result keeps a microphone the phone allows`() {
        assertEquals(true, micGranted(emptyMap(), true), "ZC-1950: interrupted request, mic allowed")
        assertEquals(true, micGranted(mapOf(CAMERA to false), true), "ZC-1950: camera refused, mic allowed")
        assertEquals(false, micGranted(emptyMap(), false), "ZC-1922: dismissed with no mic still ends the call")
    }

    @Test
    fun `the in-app call screen reads the microphone from the phone after the request`() {
        val ringing = CallSourceBlocks.block(CallSourceBlocks.source(CallSourceBlocks.OVERLAY), "RingingScreen")
        val callback = ringing.substringAfter("RequestMultiplePermissions()", "").substringBefore("val onAccept")
        assertTrue("CallLockScreen.micGrantedAfterRequest(" in callback, "ZC-1950: RingingScreen asks the ZC-1936 rule")
        assertTrue("checkSelfPermission" in callback, "ZC-1950: the phone's microphone state is read in the callback")
        assertTrue(
            "result[android.Manifest.permission.RECORD_AUDIO] == true" !in callback,
            "ZC-1950: the result alone must not decide (empty when interrupted, no RECORD_AUDIO when only the camera was asked)",
        )
    }

    private companion object {
        const val POLICY = "co.electriccoin.zcash.ui.call.CallLockScreen"
        const val CAMERA = "android.permission.CAMERA"
    }
}
