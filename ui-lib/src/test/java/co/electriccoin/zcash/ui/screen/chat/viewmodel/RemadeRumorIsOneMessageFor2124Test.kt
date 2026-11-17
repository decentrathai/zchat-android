package co.electriccoin.zcash.ui.screen.chat.viewmodel

import co.electriccoin.zcash.ui.nostr.NOSTRIdentity
import co.electriccoin.zcash.ui.nostr.Nip17
import co.electriccoin.zcash.ui.screen.chat.model.ZMSGProtocol
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.SecureRandom

/**
 * ZC-421 (fix-all 2.13.0, train T6b; ZC-420-SPEC §14.8 item 1, §14.10 compat-2124): CONTROL, passes before and after. A
 * ZChat 2.12.4 receiver files every wrap of one rumor under one id and shows it once, accepts a re-made rumor up to 30
 * days old, and reads a pending row that holds the two keys ZC-421 adds.
 *
 * The 2.12.4 code below is copied verbatim from the tag v2.12.4 (only the surrounding function is cut away; the
 * parser's field reads are kept as they are and collected in a map instead of the data class):
 * - the id rule, ui-lib/.../screen/chat/viewmodel/ChatViewModel.kt line 755;
 * - the skip of a known id, the same file lines 827-835;
 * - the window, ui-lib/.../nostr/NostrChatBridge.kt lines 326-333, with CHAT_MSG_MAX_AGE_SEC from line 463;
 * - the row parser, ui-lib/.../screen/chat/datasource/ZchatPreferences.kt lines 2539-2556 (the fields at 2541-2556).
 * Two wraps of one rumor are made with today's Nip17.send and the same second (a new seal and a new wrap each time), which
 * is what a re-made rumor is on the wire. Every key, text and address is synthetic.
 */
class RemadeRumorIsOneMessageFor2124Test {
    private val sender = identity()
    private val recipient = identity()

    private fun identity(): NOSTRIdentity = NOSTRIdentity.fromSeed(ByteArray(64).also { SecureRandom().nextBytes(it) })

    // ── frozen 2.12.4 ─────────────────────────────────────────────────────────────────────────────────────────

    private class Chat(
        val rumorId: String,
        val eventId: String,
        val createdAtSec: Long,
    )

    private class Msg(
        val id: String,
    )

    /** v2.12.4 NostrChatBridge.kt:326-333 (true: the DM passes; the original returns early instead). */
    private fun passesWindow(dm: Chat): Boolean {
        val chatAgeSec = (System.currentTimeMillis() / 1000) - dm.createdAtSec
        if (chatAgeSec > CHAT_MSG_MAX_AGE_SEC || chatAgeSec < -CHAT_MSG_MAX_AGE_SEC) {
            return false
        }
        return true
    }

    /** v2.12.4 ChatViewModel.kt:755 and :827-835 over [pendingMessages]; true when the row was added. */
    private fun file(
        chat: Chat,
        pendingMessages: MutableStateFlow<List<Msg>>,
    ): Boolean {
        val baseId = if (chat.rumorId.isNotEmpty()) "nmsg-${chat.rumorId}" else "nostr-${chat.eventId}"
        val msg = Msg(baseId)
        var addedNew = false
        pendingMessages.update { current ->
            if (current.any { it.id == msg.id }) {
                current
            } else {
                addedNew = true
                current + msg
            }
        }
        return addedNew
    }

    /** v2.12.4 ZchatPreferences.kt:2539-2556: the fields 2.12.4 reads from a stored row, as (name, value). */
    private fun parse2124(value: String): Map<String, Any?> {
        val json = org.json.JSONObject(value)
        return mapOf(
            "id" to json.getString("id"),
            "text" to json.getString("text"),
            "timestampMillis" to json.getLong("timestampMillis"),
            "peerAddress" to json.getString("peerAddress"),
            "isOutgoing" to json.optBoolean("isOutgoing", true),
            "isPending" to json.optBoolean("isPending", true),
            "status" to if (json.isNull("status")) null else json.getString("status"),
            "replyToId" to if (json.isNull("replyToId")) null else json.getString("replyToId"),
            "replyToPreview" to
                if (json.has("replyToPreview") && !json.isNull("replyToPreview")) json.getString("replyToPreview") else null,
            "fileZfileContent" to if (json.isNull("fileZfileContent")) null else json.getString("fileZfileContent"),
            "paymentRequestAmountZatoshi" to
                if (json.has("paymentRequestAmountZatoshi") && !json.isNull("paymentRequestAmountZatoshi")) {
                    json.getLong("paymentRequestAmountZatoshi")
                } else {
                    null
                },
            "paymentRequestReason" to
                if (json.has("paymentRequestReason") && !json.isNull("paymentRequestReason")) {
                    json.getString("paymentRequestReason")
                } else {
                    null
                },
        )
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────────────────────────

    private fun received(
        content: String,
        rumorSec: Long,
    ): Chat {
        val wrap = Nip17.send(sender.privateKey, sender.publicKey, recipient.publicKey, content, rumorSec).giftWrapJson
        val m = Nip17.receive(wrap, recipient.privateKey)
        return Chat(m.rumorId, m.eventId, m.createdAtSec)
    }

    private companion object {
        // v2.12.4 NostrChatBridge.kt:463
        private const val CHAT_MSG_MAX_AGE_SEC = 30L * 24 * 60 * 60
    }

    // ── the control ──────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `two wraps of one rumor are one row at 2_12_4`() {
        val t0 = System.currentTimeMillis() / 1000 - 600
        val first = received("synthetic zc421 for 2124", t0)
        val again = received("synthetic zc421 for 2124", t0)
        assertNotEquals("fixture: two wraps", first.eventId, again.eventId)
        assertEquals("fixture: one rumor", first.rumorId, again.rumorId)

        val rows = MutableStateFlow<List<Msg>>(emptyList())
        assertTrue(passesWindow(first) && file(first, rows))
        assertTrue(passesWindow(again))
        assertEquals("2.12.4 filed the second copy as a new row", false, file(again, rows))
        assertEquals(1, rows.value.size)
    }

    @Test
    fun `a re-made rumor 23 hours old passes the 2_12_4 window`() {
        val old = received("synthetic zc421 a day old", System.currentTimeMillis() / 1000 - 23 * 60 * 60)
        assertTrue(passesWindow(old))
    }

    @Test
    fun `a re-made payment request 28 days old passes the 2_12_4 window and is one row`() {
        val request = ZMSGProtocol.createPaymentRequest(9_000_000L, "usyntheticzc421frozen00001", "synthetic old rent")
        val t0 = System.currentTimeMillis() / 1000 - 28L * 24 * 60 * 60
        val first = received(request, t0)
        val again = received(request, t0)
        val rows = MutableStateFlow<List<Msg>>(emptyList())

        assertTrue(passesWindow(first) && passesWindow(again))
        file(first, rows)
        file(again, rows)
        assertEquals(1, rows.value.size)
    }

    @Test
    fun `2_12_4 reads a stored row that holds the two new keys`() {
        val withKeys =
            """{"id":"nmsg-rid421frozen","text":"synthetic frozen row","timestampMillis":1790000000000,""" +
                """"peerAddress":"usyntheticzc421frozen00002","isOutgoing":true,"isPending":false,"status":"FAILED",""" +
                """"relayRumorAtSec":1790000000,"relayRumorId":"rid421frozen"}"""
        val without =
            """{"id":"nmsg-rid421frozen","text":"synthetic frozen row","timestampMillis":1790000000000,""" +
                """"peerAddress":"usyntheticzc421frozen00002","isOutgoing":true,"isPending":false,"status":"FAILED"}"""
        assertEquals(parse2124(without), parse2124(withKeys))
    }
}
