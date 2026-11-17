package co.electriccoin.zcash.ui.call

import co.electriccoin.zcash.ui.call.CallSourceBlocks.OVERLAY
import co.electriccoin.zcash.ui.call.CallSourceBlocks.block
import co.electriccoin.zcash.ui.call.CallSourceBlocks.source
import co.electriccoin.zcash.ui.screen.chat.view.ChatResourceText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * QA2 Q2-75 (ZC-1599), Q2-77 and Q2-78 (ZC-1552), Q2-73 (ZC-1656), Q2-81 (ZC-800) — the call screen (FIX_LIST B5).
 *
 * At 33bb92cdf CallOverlay.kt writes its button labels as English literals ("Decline", "Accept", "Mute", "Unmute",
 * "Hang up", "Switch camera", "Turn video off/on", "Call duration … minutes … seconds", "Audio output: …",
 * "Speaker"/"Earpiece"/"Bluetooth"/"Headset", ", selected"), so TalkBack speaks English on a Spanish phone; the
 * route, camera-switch and video on/off buttons are emoji (the Earpiece glyph is invisible when selected); the
 * caller line under "Incoming … call" is 14.sp grey; the ringing and in-call screens are 90 % / 92 % black, so chat
 * rows and amber banners show through; the Ended notice is 85 % black; the video layout drops the call timer and
 * says nothing when the other camera is off.
 *
 * Wanted: every label a string resource in ui/common/values{,-es}/call_screen_strings.xml (ES lines are drafts,
 * app-texts rule 9; "video" without accent as the existing call_switch_to_video), vector icons instead of emoji,
 * the caller line 16.sp, opaque call screens and notice, the timer kept in the video layout, and the two new
 * remote-video lines. Pure layout (inset, gradient, scrim) has no test here: phone check in the QA repeat.
 */
class CallControlsSpeakThePhonesLanguageTest {
    private val overlay: String by lazy { source(OVERLAY) }

    @Test
    fun `the call controls' English labels left CallOverlay kt`() {
        val literals =
            listOf(
                "\"Decline\"",
                "\"Accept\"",
                "\"Mute\"",
                "\"Unmute\"",
                "\"Hang up\"",
                "\"Switch camera\"",
                "\"Turn video off\"",
                "\"Turn video on\"",
                "\"Call duration",
                "\"Audio output:",
                "\"Speaker\"",
                "\"Earpiece\"",
                "\"Bluetooth\"",
                "\"Headset\"",
                "\", selected\"",
            )
        val left = literals.filter { it in overlay }
        assertEquals(emptyList(), left, "English call-control literals still in CallOverlay.kt")
    }

    @Test
    fun `the call controls are English and Spanish resources`() {
        val en = ChatResourceText.strings("values")
        val es = ChatResourceText.strings("values-es")
        EN.forEach { (key, text) -> assertEquals(text, en[key], "values $key") }
        ES.forEach { (key, text) -> assertEquals(text, es[key], "values-es $key") }
    }

    @Test
    fun `the duration and route labels keep their placeholders in both languages`() {
        val en = ChatResourceText.strings("values")
        val es = ChatResourceText.strings("values-es")
        val placeholder = Regex("""%\d\$[sd]""")
        for ((key, count) in listOf("call_duration_a11y" to 2, "call_audio_output" to 1, "call_route_selected" to 1)) {
            val enText = en[key] ?: fail("$key must be in values")
            val esText = es[key] ?: fail("$key must be in values-es")
            val enSet = placeholder.findAll(enText).map { it.value }.toSet()
            val esSet = placeholder.findAll(esText).map { it.value }.toSet()
            assertEquals(count, enSet.size, "English $key placeholders: $enText")
            assertEquals(enSet, esSet, "Spanish $key must keep the English placeholders: $esText")
        }
        assertTrue("R.string.call_duration_a11y" in overlay, "CallTimer's content description must come from call_duration_a11y")
    }

    @Test
    fun `no emoji glyph is a call button`() {
        val left = listOf("🔄", "📷", "🚫", "🔊", "📞", "🎧").filter { it in overlay }
        assertEquals(emptyList(), left, "CallOverlay.kt still draws call buttons as emoji (use tinted vector icons)")
    }

    @Test
    fun `the caller line on the ringing screen is 16 sp, not 14 sp grey`() {
        val ringing = block(overlay, "RingingScreen")
        assertFalse("fontSize = 14.sp" in ringing, "the ringing screen's caller line is still 14.sp")
        assertFalse("NightwireColors.TextSecondary" in ringing, "the ringing screen's caller line is still grey")
        assertTrue("fontSize = 16.sp" in ringing, "the ringing screen's caller line must be 16.sp")
    }

    @Test
    fun `the ringing and in-call screens are opaque`() {
        for (screen in listOf("RingingScreen", "InCallScreen")) {
            val body = block(overlay, screen)
            for (alpha in listOf("alpha = 0.9f", "alpha = 0.92f")) {
                assertFalse(alpha in body, "$screen still lets the chat show through ($alpha)")
            }
        }
    }

    @Test
    fun `the ended-call notice is opaque`() {
        val toast = block(overlay, "EndedToast")
        assertFalse(Regex("""copy\(\s*alpha\s*=""").containsMatchIn(toast), "EndedToast's background is still see-through")
    }

    @Test
    fun `the video layout keeps the timer and says when the other camera is off`() {
        val video = block(overlay, "VideoCallScreen")
        assertTrue("CallTimer(" in video, "the video layout dropped the call duration")
        assertTrue("R.string.call_remote_camera_off" in video, "the video layout never says the other camera is off")
        assertTrue("R.string.call_remote_video_on_hint" in video, "the receiving phone never says its own camera can join")
    }

    private companion object {
        val EN =
            linkedMapOf(
                "call_action_decline" to "Decline",
                "call_action_answer" to "Answer",
                "call_action_mute" to "Mute",
                "call_action_unmute" to "Unmute",
                "call_action_end" to "End call",
                "call_action_speaker" to "Speaker",
                "call_action_earpiece" to "Earpiece",
                "call_action_bluetooth" to "Bluetooth",
                "call_action_headset" to "Headset",
                "call_action_switch_camera" to "Switch camera",
                "call_action_video_off" to "Turn video off",
                "call_action_video_on" to "Turn video on",
                "call_remote_camera_off" to "No picture from your contact",
                "call_remote_video_on_hint" to "Their video is on — tap to share yours",
            )
        val ES =
            linkedMapOf(
                "call_action_decline" to "Rechazar",
                "call_action_answer" to "Responder",
                "call_action_mute" to "Silenciar",
                "call_action_unmute" to "Activar micrófono",
                "call_action_end" to "Colgar",
                "call_action_speaker" to "Altavoz",
                "call_action_earpiece" to "Auricular",
                "call_action_bluetooth" to "Bluetooth",
                "call_action_headset" to "Auriculares",
                "call_action_switch_camera" to "Cambiar cámara",
                "call_action_video_off" to "Apagar video",
                "call_action_video_on" to "Encender video",
                "call_remote_camera_off" to "No llega la imagen de tu contacto",
                "call_remote_video_on_hint" to "Tu contacto activó su video. Toca para compartir el tuyo.",
            )
    }
}
