package co.electriccoin.zcash.ui.call

import co.electriccoin.zcash.ui.call.CallSourceBlocks.NOTIFICATIONS
import co.electriccoin.zcash.ui.call.CallSourceBlocks.SERVICE
import co.electriccoin.zcash.ui.call.CallSourceBlocks.block
import co.electriccoin.zcash.ui.call.CallSourceBlocks.objectCall
import co.electriccoin.zcash.ui.call.CallSourceBlocks.source
import co.electriccoin.zcash.ui.screen.chat.datasource.NotificationPrivacy
import co.electriccoin.zcash.ui.screen.chat.model.CallLogType
import co.electriccoin.zcash.ui.screen.chat.view.ChatResourceText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * QA2 Q2-80 (ZC-1659, owner B-7 under W-137) and ZC-1680.
 *
 * At 33bb92cdf a call nobody answers while ZChat is in the background leaves only a "Missed call" pill inside the
 * chat (P3E c4 timeline): no notification. The incoming channel's name and description are English literals
 * ("Incoming Calls", "Incoming ZCHAT voice calls"), so Android's notification settings show English on a Spanish phone.
 *
 * Wanted (FIX_PLAN_W137 B-7), all in this package:
 *  - `object CallLogClassifier { fun classify(reason: CallEndReason, outgoing: Boolean, connected: Boolean):
 *    CallLogType? }`: the `when` of SyncForegroundService.recordCallLog extracted verbatim (Glare is not logged);
 *  - `object MissedCallNotice`: `wanted(type: CallLogType, appInForeground: Boolean, privacy: NotificationPrivacy)`
 *    true only for MISSED in the background and not SILENT; `title(privacy, callerLabel, isVideo): CallScreenLine?`
 *    names the caller for FULL_PREVIEW and SENDER_ONLY, says only "Missed (video) call" for NEW_MESSAGE, null for
 *    SILENT; `notificationId(peerAddress): Int` one stable id per peer, never the incoming card's 1003;
 *  - `CallNotificationController.showMissed(…)` on a new channel `missed_calls_v1` at IMPORTANCE_DEFAULT (never the
 *    ringtone channel, no full-screen intent), tap opens the chat with EXTRA_NAVIGATE_TO_CONVERSATION as the chat
 *    notification does; the service calls it after recordCallLog when MissedCallNotice.wanted;
 *  - every channel name and description a resource in EN and ES (ES lines are drafts).
 */
class MissedCallIsNotifiedTest {
    private fun classify(
        reason: CallEndReason,
        outgoing: Boolean,
        connected: Boolean,
    ): CallLogType? =
        objectCall("co.electriccoin.zcash.ui.call.CallLogClassifier", "classify", reason, outgoing, connected) as CallLogType?

    private fun wanted(
        type: CallLogType,
        appInForeground: Boolean,
        privacy: NotificationPrivacy,
    ): Boolean = objectCall(NOTICE, "wanted", type, appInForeground, privacy) as Boolean

    private fun title(
        privacy: NotificationPrivacy,
        label: String,
        isVideo: Boolean,
    ): CallScreenLine? = objectCall(NOTICE, "title", privacy, label, isVideo) as CallScreenLine?

    private fun id(name: String): Int = ChatResourceText.stringId(name) ?: fail("no string resource $name")

    @Test
    fun `the call-log classification is the service's, extracted verbatim`() {
        val reasons =
            listOf(
                CallEndReason.UserEnded,
                CallEndReason.Declined,
                CallEndReason.Busy,
                CallEndReason.Timeout,
                CallEndReason.IceFailed,
                CallEndReason.RemoteHangup("user_ended"),
                CallEndReason.Shutdown,
            )
        for (reason in reasons) {
            for (outgoing in listOf(true, false)) {
                val connected = if (outgoing) CallLogType.OUTGOING else CallLogType.INCOMING
                assertEquals(connected, classify(reason, outgoing, connected = true), "$reason outgoing=$outgoing connected")
                val expected =
                    when {
                        reason == CallEndReason.Declined || reason == CallEndReason.Busy -> CallLogType.DECLINED
                        outgoing -> CallLogType.OUTGOING
                        else -> CallLogType.MISSED
                    }
                assertEquals(expected, classify(reason, outgoing, connected = false), "$reason outgoing=$outgoing not connected")
            }
        }
        assertNull(classify(CallEndReason.Glare, outgoing = false, connected = false), "glare is not logged")
        assertNull(classify(CallEndReason.Glare, outgoing = true, connected = true), "glare is not logged")
    }

    @Test
    fun `only a missed call in the background is notified, and never when notifications are silent`() {
        assertTrue(wanted(CallLogType.MISSED, appInForeground = false, privacy = NotificationPrivacy.FULL_PREVIEW))
        assertTrue(wanted(CallLogType.MISSED, appInForeground = false, privacy = NotificationPrivacy.SENDER_ONLY))
        assertTrue(wanted(CallLogType.MISSED, appInForeground = false, privacy = NotificationPrivacy.NEW_MESSAGE))
        assertFalse(wanted(CallLogType.MISSED, appInForeground = false, privacy = NotificationPrivacy.SILENT))
        assertFalse(wanted(CallLogType.MISSED, appInForeground = true, privacy = NotificationPrivacy.FULL_PREVIEW))
        for (type in listOf(CallLogType.INCOMING, CallLogType.OUTGOING, CallLogType.DECLINED)) {
            for (fg in listOf(true, false)) {
                assertFalse(wanted(type, fg, NotificationPrivacy.FULL_PREVIEW), "$type (foreground=$fg) is not a missed call")
            }
        }
    }

    @Test
    fun `the missed-call title follows the notification privacy setting`() {
        for (privacy in listOf(NotificationPrivacy.FULL_PREVIEW, NotificationPrivacy.SENDER_ONLY)) {
            val voice = title(privacy, NAME, isVideo = false) ?: fail("$privacy must have a title")
            assertEquals(id("call_missed_from"), voice.resId, "$privacy voice")
            assertEquals(listOf(NAME), voice.args, "$privacy voice names the caller")
            val video = title(privacy, NAME, isVideo = true) ?: fail("$privacy must have a title")
            assertEquals(id("call_missed_video_from"), video.resId, "$privacy video")
            assertEquals(listOf(NAME), video.args, "$privacy video names the caller")
        }
        val generic = title(NotificationPrivacy.NEW_MESSAGE, NAME, isVideo = false) ?: fail("NEW_MESSAGE must have a title")
        assertEquals(id("call_log_missed"), generic.resId, "NEW_MESSAGE says only Missed call")
        assertTrue(generic.args.isEmpty() && generic.literal == null, "NEW_MESSAGE must not name the caller")
        val genericVideo = title(NotificationPrivacy.NEW_MESSAGE, NAME, isVideo = true) ?: fail("NEW_MESSAGE must have a title")
        assertEquals(id("call_log_missed_video"), genericVideo.resId, "NEW_MESSAGE video says only Missed video call")
        assertTrue(genericVideo.args.isEmpty() && genericVideo.literal == null, "NEW_MESSAGE must not name the caller")
        assertNull(title(NotificationPrivacy.SILENT, NAME, isVideo = false), "SILENT has no notification")
    }

    @Test
    fun `each peer gets its own stable missed-call id, never the incoming card's`() {
        val a = objectCall(NOTICE, "notificationId", PEER_A) as Int
        val b = objectCall(NOTICE, "notificationId", PEER_B) as Int
        assertEquals(a, objectCall(NOTICE, "notificationId", PEER_A) as Int, "the id is stable for one peer")
        assertNotEquals(a, b, "two peers must not replace each other's missed-call notification")
        assertTrue(a != INCOMING_ID && b != INCOMING_ID, "a missed-call id must never be the incoming card's $INCOMING_ID")
    }

    @Test
    fun `showMissed posts on its own default-importance channel and opens the chat`() {
        val src = source(NOTIFICATIONS)
        assertTrue("\"missed_calls_v1\"" in src, "no missed_calls_v1 channel")
        assertTrue(
            Regex("""NotificationChannel\(\s*\w*MISSED\w*.{0,240}?IMPORTANCE_DEFAULT""", RegexOption.DOT_MATCHES_ALL)
                .containsMatchIn(src),
            "the missed-call channel must be IMPORTANCE_DEFAULT",
        )
        val show = block(src, "showMissed", indent = "    ")
        assertFalse(Regex("""\bCHANNEL_ID\b""").containsMatchIn(show), "showMissed must never post on the ringtone channel")
        assertFalse("setFullScreenIntent" in show, "a missed call is not a full-screen ring")
        assertFalse("CallStyle" in show, "a missed call has no Answer/Decline")
        assertTrue("EXTRA_NAVIGATE_TO_CONVERSATION" in show, "tapping the missed call must open the chat")
    }

    @Test
    fun `the service classifies through CallLogClassifier and notifies a missed call`() {
        val service = source(SERVICE)
        assertTrue("CallLogClassifier.classify(" in service, "recordCallLog still classifies inline")
        assertFalse("CallLogType.DECLINED" in service, "the inline classification is still in SyncForegroundService")
        assertTrue("MissedCallNotice.wanted(" in service, "the service never decides a missed-call notification")
        assertTrue("showMissed(" in service, "the service never posts a missed-call notification")
    }

    @Test
    fun `channel names and descriptions are English and Spanish resources`() {
        val src = source(NOTIFICATIONS)
        for (literal in listOf("\"Incoming Calls\"", "\"Incoming ZCHAT voice calls\"")) {
            assertFalse(literal in src, "CallNotificationController still names a channel in English: $literal")
        }
        val en = ChatResourceText.strings("values")
        val es = ChatResourceText.strings("values-es")
        CHANNEL_EN.forEach { (key, text) ->
            assertEquals(text, en[key], "values $key")
            assertTrue("R.string.$key" in src, "CallNotificationController must use $key")
        }
        CHANNEL_ES.forEach { (key, text) -> assertEquals(text, es[key], "values-es $key") }
    }

    @Test
    fun `the missed-call titles are English and Spanish resources with the caller placeholder`() {
        val en = ChatResourceText.strings("values")
        val es = ChatResourceText.strings("values-es")
        assertEquals("Missed call from %1\$s", en["call_missed_from"])
        assertEquals("Missed video call from %1\$s", en["call_missed_video_from"])
        assertEquals("Llamada perdida de %1\$s", es["call_missed_from"])
        assertEquals("Videollamada perdida de %1\$s", es["call_missed_video_from"])
    }

    private companion object {
        const val NOTICE = "co.electriccoin.zcash.ui.call.MissedCallNotice"
        const val NAME = "Synthetic Alice"
        const val PEER_A = "usyntheticqa2missedpeer0000000a"
        const val PEER_B = "usyntheticqa2missedpeer0000000b"
        const val INCOMING_ID = 1003

        val CHANNEL_EN =
            linkedMapOf(
                "call_channel_incoming_name" to "Incoming calls",
                "call_channel_incoming_desc" to "Incoming ZChat voice and video calls",
                "call_channel_missed_name" to "Missed calls",
                "call_channel_missed_desc" to "Calls you did not answer",
            )
        val CHANNEL_ES =
            linkedMapOf(
                "call_channel_incoming_name" to "Llamadas entrantes",
                "call_channel_incoming_desc" to "Llamadas de voz y video entrantes de ZChat",
                "call_channel_missed_name" to "Llamadas perdidas",
                "call_channel_missed_desc" to "Llamadas que no contestaste",
            )
    }
}
