package co.electriccoin.zcash.ui.screen.chat.model

import co.electriccoin.zcash.ui.screen.chat.view.ChatResourceText
import java.lang.reflect.InvocationTargetException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.fail

/**
 * QA2 Q2-76 (ZC-1577) — the call-log pill and the chat-list preview of a call (FIX_LIST B5).
 *
 * At 33bb92cdf CallLogInfo.label builds "Outgoing call", "Incoming video call", "Missed call", "Call declined" from
 * English literals at display time, so a Spanish phone shows English (P3E honor_15, seeker_15), and an outgoing call
 * nobody answered reads "Outgoing call", the same as one that connected (P3F D1).
 *
 * Wanted: `CallLogInfo.labelRes` (a @StringRes Int property; the constructor stays source-compatible for
 * ChatRequestBannerTest and ChatOpensFromTheLastPassTest) naming one resource per type × video, with "No answer" for an
 * outgoing call that never connected (durationSec == null); EN and ES texts (ES drafts); the English literals gone
 * from ChatMessage.kt. The pill's time of day and size are layout in ChatDetailView: no test here.
 */
class CallLogLabelsAreResourcesTest {
    private fun labelRes(info: CallLogInfo): Int =
        try {
            info.javaClass.getMethod("getLabelRes").invoke(info) as Int
        } catch (e: NoSuchMethodException) {
            fail("CallLogInfo has no labelRes: the call-log pill still builds English at display time")
        } catch (e: InvocationTargetException) {
            throw e.targetException
        }

    private fun id(name: String): Int = ChatResourceText.stringId(name) ?: fail("no string resource $name")

    @Test
    fun `each call-log type names its own resource`() {
        val cases =
            listOf(
                CallLogInfo(CallLogType.OUTGOING, isVideo = false, durationSec = 12) to "call_log_outgoing",
                CallLogInfo(CallLogType.OUTGOING, isVideo = true, durationSec = 12) to "call_log_outgoing_video",
                CallLogInfo(CallLogType.INCOMING, isVideo = false, durationSec = 12) to "call_log_incoming",
                CallLogInfo(CallLogType.INCOMING, isVideo = true, durationSec = 12) to "call_log_incoming_video",
                CallLogInfo(CallLogType.MISSED, isVideo = false) to "call_log_missed",
                CallLogInfo(CallLogType.MISSED, isVideo = true) to "call_log_missed_video",
                CallLogInfo(CallLogType.DECLINED, isVideo = false) to "call_log_declined",
                CallLogInfo(CallLogType.DECLINED, isVideo = true) to "call_log_declined_video",
                CallLogInfo(CallLogType.OUTGOING, isVideo = false, durationSec = null) to "call_log_no_answer",
                CallLogInfo(CallLogType.OUTGOING, isVideo = true, durationSec = null) to "call_log_no_answer",
            )
        for ((info, key) in cases) assertEquals(id(key), labelRes(info), "$info must show $key")
    }

    @Test
    fun `the call-log labels are English and Spanish resources`() {
        val en = ChatResourceText.strings("values")
        val es = ChatResourceText.strings("values-es")
        EN.forEach { (key, text) -> assertEquals(text, en[key], "values $key") }
        ES.forEach { (key, text) -> assertEquals(text, es[key], "values-es $key") }
    }

    @Test
    fun `the English call-log literals left ChatMessage kt`() {
        val src = ChatResourceText.source("ui-lib/src/main/java/co/electriccoin/zcash/ui/screen/chat/model/ChatMessage.kt")
        val left = listOf("\"Outgoing ", "\"Incoming ", "\"Missed ", "all declined\"", "\"Video c\"").filter { it in src }
        assertEquals(emptyList(), left, "English call-log literals still in ChatMessage.kt")
    }

    private companion object {
        val EN =
            linkedMapOf(
                "call_log_outgoing" to "Outgoing call",
                "call_log_outgoing_video" to "Outgoing video call",
                "call_log_incoming" to "Incoming call",
                "call_log_incoming_video" to "Incoming video call",
                "call_log_missed" to "Missed call",
                "call_log_missed_video" to "Missed video call",
                "call_log_declined" to "Call declined",
                "call_log_declined_video" to "Video call declined",
                "call_log_no_answer" to "No answer",
            )
        val ES =
            linkedMapOf(
                "call_log_outgoing" to "Llamada saliente",
                "call_log_outgoing_video" to "Videollamada saliente",
                "call_log_incoming" to "Llamada entrante",
                "call_log_incoming_video" to "Videollamada entrante",
                "call_log_missed" to "Llamada perdida",
                "call_log_missed_video" to "Videollamada perdida",
                "call_log_declined" to "Llamada rechazada",
                "call_log_declined_video" to "Videollamada rechazada",
                "call_log_no_answer" to "Sin respuesta",
            )
    }
}
