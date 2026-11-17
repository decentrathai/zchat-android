package co.electriccoin.zcash.ui.call

import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * ZC-1567 (owner, 6 Nov 2026: with the Seeker at the ear the call screen "disappeared"). A voice call starts on the
 * loudspeaker when no headset is connected (VoiceCallManager.bestInitialRoute), and the proximity screen-off lock was
 * held only on the earpiece route. So at the ear the screen stayed live and the ear tapped Mute or the call controls
 * (the touches 40 ms before each mic stop in the 6 Nov proof, ZC-1564). The lock is wanted for every live voice call
 * whose sound comes from the phone itself (earpiece or loudspeaker); never for video (the user looks at the screen) and
 * never on a headset (the phone is not at the ear).
 *
 * At RED there is no CallProximity.
 */
class CallScreenGoesDarkAtTheEarOnTheSpeakerTooTest {
    private fun route(name: String): Any =
        Class
            .forName("co.electriccoin.zcash.ui.call.VoiceCallManager\$AudioRoute")
            .enumConstants!!
            .first { (it as Enum<*>).name == name }

    private fun wanted(inCall: Boolean, isVideo: Boolean, route: String): Boolean {
        val k = Class.forName("co.electriccoin.zcash.ui.call.CallProximity")
        val m = k.methods.first { it.name == "screenOffNearFace" }
        return m.invoke(k.getField("INSTANCE").get(null), inCall, isVideo, route(route)) as Boolean
    }

    @Test
    fun `a live voice call on the loudspeaker or the earpiece goes dark at the ear`() {
        assertTrue(wanted(inCall = true, isVideo = false, route = "SPEAKER"))
        assertTrue(wanted(inCall = true, isVideo = false, route = "EARPIECE"))
    }

    @Test
    fun `video calls, headsets and calls not yet live keep the screen`() {
        assertFalse(wanted(inCall = true, isVideo = true, route = "SPEAKER"))
        assertFalse(wanted(inCall = true, isVideo = true, route = "EARPIECE"))
        assertFalse(wanted(inCall = true, isVideo = false, route = "BLUETOOTH"))
        assertFalse(wanted(inCall = true, isVideo = false, route = "WIRED"))
        assertFalse(wanted(inCall = false, isVideo = false, route = "SPEAKER"))
    }
}
