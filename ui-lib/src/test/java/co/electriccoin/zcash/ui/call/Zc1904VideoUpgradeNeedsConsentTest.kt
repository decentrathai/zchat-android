package co.electriccoin.zcash.ui.call

import co.electriccoin.zcash.ui.screen.chat.view.ChatResourceText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * ZC-1904 (owner test 16 Nov, W-148 "Ask to accept video"): at 65f4f1323 a second OFFER under the same callId while
 * InCall is answered at once (VoiceCallManager.onOffer -> CallRenegotiation.onRemoteOffer returns ANSWER ->
 * answerVideoUpgrade -> markInCallVideo(true)), so the caller's video appears on the other phone without asking.
 *
 * Wanted:
 * - CallRenegotiation.onRemoteOffer: in an audio call (InCall, isVideo = false) with no upgrade of our own pending the
 *   OFFER is held and the user asked: a new OfferDecision.ASK. While the question is open a further OFFER is ignored and
 *   this phone starts no upgrade of its own; `finishAsk()` is true once (Accept, Decline or the prompt timeout, first
 *   wins). Once the call carries video an OFFER is answered as before (the other phone adding its camera), and glare
 *   (both tapped video) still answers.
 * - `PROMPT_TIMEOUT_MS` (the callee's question) is shorter than `UPGRADE_TIMEOUT_MS` (about 30 s, the caller's wait),
 *   so a timeout's decline reaches the caller before its own rollback.
 * - `onRemoteDecline(state)`: true once, only for our own pending upgrade in a connected call; the caller then rolls
 *   back (camera off, call stays audio).
 * - A new wire type CallSignalType.VIDEO_DECLINE ("VIDEO_DECLINE"), the callee's answer to a declined or timed-out
 *   question. A 2.12.4 phone cannot parse it (frozen parser below) and never receives it (it never sends an in-call
 *   OFFER, see [Frozen2124]).
 * - VoiceCallManager: `videoUpgradeRequest` (StateFlow), `acceptVideoUpgrade()` (answers the held OFFER; the camera
 *   stays off), `declineVideoUpgrade()` (drops it and sends VIDEO_DECLINE); CallOverlay shows the question with Accept
 *   and Decline, and the caller a "declined" notice; the texts in English and Spanish.
 *
 * New members are reached by name, so this class compiles before the fix. Two phones are the device check.
 */
class Zc1904VideoUpgradeNeedsConsentTest {
    private val peer = "c".repeat(64)
    private val callId = "1f".repeat(16)
    private val audioCall = VoiceCallManager.CallState.InCall(peer, callId)
    private val videoCall = audioCall.copy(isVideo = true)
    private val stateType = VoiceCallManager.CallState::class.java

    private val type: Class<*> = CallRenegotiation::class.java

    private fun reneg(isCaller: Boolean): Any = CallRenegotiation(isCaller)

    private fun offer(
        r: Any,
        state: VoiceCallManager.CallState,
    ): String = (type.getMethod("onRemoteOffer", stateType).invoke(r, state) as Enum<*>).name

    private fun start(
        r: Any,
        state: VoiceCallManager.CallState,
        nowMs: Long,
    ): Boolean = type.getMethod("startUpgrade", stateType, Long::class.javaPrimitiveType).invoke(r, state, nowMs) as Boolean

    private fun finishAsk(r: Any): Boolean {
        val m =
            type.methods.firstOrNull { it.name == "finishAsk" && it.parameterCount == 0 }
                ?: fail("ZC-1904: CallRenegotiation has no finishAsk(); a held video request can never be answered once")
        return m.invoke(r) as Boolean
    }

    private fun decline(
        r: Any,
        state: VoiceCallManager.CallState,
    ): Boolean {
        val m =
            type.methods.firstOrNull { it.name == "onRemoteDecline" && it.parameterCount == 1 }
                ?: fail("ZC-1904: CallRenegotiation has no onRemoteDecline(state); the caller cannot roll back on a decline")
        return m.invoke(r, state) as Boolean
    }

    private fun rollBack(
        r: Any,
        nowMs: Long,
    ): Boolean = type.getMethod("shouldRollBack", Long::class.javaPrimitiveType).invoke(r, nowMs) as Boolean

    private fun constant(name: String): Long =
        (runCatching { type.getField(name).get(null) }.getOrNull() as? Number)?.toLong()
            ?: fail("ZC-1904: CallRenegotiation.$name does not exist")

    @Test
    fun `an upgrade OFFER in an audio call asks the user instead of answering`() {
        for (isCaller in listOf(true, false)) {
            assertEquals(
                "ASK",
                offer(reneg(isCaller), audioCall),
                "ZC-1904: the other phone's switch to video must ask first, never show its video unasked (isCaller=$isCaller)",
            )
        }
    }

    @Test
    fun `while the question is open a second OFFER is ignored and no own upgrade starts`() {
        val r = reneg(isCaller = false)
        assertEquals("ASK", offer(r, audioCall))
        assertEquals("IGNORE", offer(r, audioCall), "ZC-1904: one question at a time")
        assertEquals(false, start(r, audioCall, 0L), "ZC-1904: no own upgrade while the question is open")
        assertEquals(true, finishAsk(r), "ZC-1904: Accept, Decline or the timeout ends the question")
        assertEquals(false, finishAsk(r), "ZC-1904: only the first of Accept, Decline and the timeout wins")
        assertEquals(true, start(r, audioCall, 1_000L), "after the question the user may switch to video")
    }

    @Test
    fun `no question is open without an OFFER`() {
        assertEquals(false, finishAsk(reneg(isCaller = true)), "ZC-1904: nothing to accept or decline")
    }

    @Test
    fun `glare and an OFFER in a video call are still answered`() {
        val callee = reneg(isCaller = false)
        assertTrue(start(callee, audioCall, 0L))
        assertEquals("ROLLBACK_LOCAL_THEN_ANSWER", offer(callee, audioCall), "both tapped video: consent on both sides")
        assertEquals("ANSWER", offer(reneg(isCaller = true), videoCall), "the call already carries video")
    }

    @Test
    fun `the callee's question ends before the caller stops waiting`() {
        val prompt = constant("PROMPT_TIMEOUT_MS")
        val wait = constant("UPGRADE_TIMEOUT_MS")
        assertEquals(30_000L, wait, "ZC-1904: the caller waits about 30 s while the other phone asks")
        assertTrue(prompt in 10_000L until wait, "ZC-1904: PROMPT_TIMEOUT_MS ($prompt) must be shorter than $wait")
    }

    @Test
    fun `a decline rolls the caller's upgrade back once`() {
        val r = reneg(isCaller = true)
        assertEquals(false, decline(r, audioCall), "no upgrade pending: a stray decline is ignored")
        assertTrue(start(r, audioCall, 0L))
        assertEquals(false, decline(r, VoiceCallManager.CallState.Idle), "not in a call")
        assertEquals(true, decline(r, audioCall), "ZC-1904: the decline ends our pending upgrade")
        assertEquals(false, decline(r, audioCall), "a repeated decline is dropped")
        assertEquals(false, rollBack(r, 60_000L), "a declined upgrade has no timeout left")
        assertEquals(true, start(r, audioCall, 61_000L), "the user may ask again")
    }

    @Test
    fun `VIDEO_DECLINE is a call signal type and round-trips`() {
        val t = CallSignalType.fromCode("VIDEO_DECLINE") ?: fail("ZC-1904: no CallSignalType VIDEO_DECLINE")
        val env = CallSignalEnvelope(callId, t, "declined")
        assertEquals(env, CallSignalEnvelope.parse(env.serialize()))
        assertEquals("ZCALL|v1|$callId|VIDEO_DECLINE|declined", env.serialize())
    }

    @Test
    fun `a 2 12 4 phone cannot parse VIDEO_DECLINE`() {
        val wire = "ZCALL|v1|$callId|VIDEO_DECLINE|declined"
        assertNull(Frozen2124.parse(wire), "2.12.4 must not read VIDEO_DECLINE as one of its call signals")
        // Positive control: the same frozen parser reads a 2.12.4 HANGUP.
        assertNotNull(Frozen2124.parse("ZCALL|v1|$callId|HANGUP|user_ended"))
    }

    @Test
    fun `the call manager holds the OFFER and answers it only on Accept`() {
        val mgr = VoiceCallManager::class.java
        listOf("acceptVideoUpgrade", "declineVideoUpgrade", "getVideoUpgradeRequest").forEach { name ->
            assertTrue(
                mgr.methods.any { it.name == name && it.parameterCount == 0 },
                "ZC-1904: VoiceCallManager.$name() does not exist",
            )
        }
        val src = ChatResourceText.source(MANAGER)
        val onOffer = functionBody(src, "onOffer")
        val ask = onOffer.substringAfter("OfferDecision.ASK ->", missingDelimiterValue = "")
        assertTrue(ask.isNotEmpty(), "ZC-1904: onOffer must handle OfferDecision.ASK")
        assertTrue(
            "answerVideoUpgrade(" !in ask.substringBefore("OfferDecision."),
            "ZC-1904: the ASK branch must hold the OFFER, not answer it",
        )
        assertTrue(
            "answerVideoUpgrade(" in functionBody(src, "acceptVideoUpgrade"),
            "ZC-1904: Accept answers the held OFFER",
        )
        assertTrue("CallSignalType.VIDEO_DECLINE ->" in src, "ZC-1904: handleSignal must route VIDEO_DECLINE")
        assertTrue(".onRemoteDecline(" in src, "ZC-1904: the caller must roll back on VIDEO_DECLINE")
        assertTrue("PROMPT_TIMEOUT_MS" in src, "ZC-1904: an unanswered question times out and declines")
    }

    @Test
    fun `the call screen asks with Accept and Decline and tells the caller of a decline`() {
        val overlay = ChatResourceText.source(CallSourceBlocks.OVERLAY)
        listOf(
            "videoUpgradeRequest",
            "acceptVideoUpgrade()",
            "declineVideoUpgrade()",
            "R.string.call_video_request_title",
            "R.string.call_video_request_body",
            "R.string.call_video_request_accept",
            "R.string.call_video_request_decline",
            "R.string.call_video_declined",
        ).forEach { assertTrue(it in overlay, "ZC-1904: CallOverlay must use $it") }
    }

    @Test
    fun `the new texts exist in English and Spanish`() {
        val en = ChatResourceText.strings("values")
        val es = ChatResourceText.strings("values-es")
        val keys =
            listOf(
                "call_video_request_title",
                "call_video_request_body",
                "call_video_request_accept",
                "call_video_request_decline",
                "call_video_declined",
            )
        for (key in keys) {
            val e = en[key] ?: fail("ZC-1904: no English $key")
            val s = es[key] ?: fail("ZC-1904: no Spanish $key")
            assertTrue(s != e, "ZC-1904: $key is not translated")
            assertEquals(placeholders(e), placeholders(s), "ZC-1904: $key placeholders")
        }
        assertEquals(listOf("%1\$s"), placeholders(en.getValue("call_video_request_body")), "the body names the contact")
        assertTrue("camera" in en.getValue("call_video_request_body"), "ZC-1904: say that this phone's camera stays off")
        assertTrue("cámara" in es.getValue("call_video_request_body"), "ZC-1904: say that this phone's camera stays off (es)")
    }

    private fun functionBody(
        src: String,
        name: String,
    ): String {
        val at = Regex("""fun\s+$name\s*\(""").find(src) ?: fail("ZC-1904: fun $name not found in VoiceCallManager")
        val rest = src.substring(at.range.first)
        val open = rest.indexOf('{')
        var depth = 0
        for (i in open until rest.length) {
            when (rest[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return rest.substring(open, i + 1)
                }
            }
        }
        fail("unbalanced fun $name")
    }

    private fun placeholders(s: String): List<String> =
        Regex("""%\d+\$[sd]""")
            .findAll(s)
            .map { it.value }
            .sorted()
            .toList()

    /**
     * The 2.12.4 call-signal parser, verbatim from `git show v2.12.4:ui-lib/src/main/java/co/electriccoin/zcash/ui/call/
     * CallSignalEnvelope.kt` lines 12-22 (CallSignalType) and 54-72 (parse). Changes: the enum is renamed
     * `CallSignalType2124` and parse returns `Pair<String, CallSignalType2124>?` instead of building a
     * CallSignalEnvelope; the checks are unchanged.
     *
     * Why 2.12.4 never receives VIDEO_DECLINE: 2.13.0 sends it only to answer a held in-call upgrade OFFER from the
     * active call's peer, and 2.12.4 emits an OFFER only in placeCall (`git show v2.12.4:.../call/VoiceCallManager.kt`
     * line 179, its only `CallSignalType.OFFER` emit), before the call is connected. (Were it ever delivered, 2.12.4's
     * NostrChatBridge lines 291-295 would hand the unparseable body to its chat path; it is not reachable.)
     */
    @Suppress("ktlint")
    private object Frozen2124 {
        enum class CallSignalType2124(val code: String) {
            OFFER("OFFER"),
            ANSWER("ANSWER"),
            ICE("ICE"),
            HANGUP("HANGUP"),
            RING("RING");

            companion object {
                fun fromCode(code: String): CallSignalType2124? = entries.find { it.code == code }
            }
        }

        const val PREFIX = "ZCALL|v1|"
        const val MAX_SDP_PAYLOAD = 64 * 1024
        private const val CALL_ID_LEN = 32
        private val CALL_ID_PATTERN = Regex("^[0-9a-f]{$CALL_ID_LEN}$")

        fun parse(raw: String): Pair<String, CallSignalType2124>? {
            if (!raw.startsWith(PREFIX)) return null
            val body = raw.removePrefix(PREFIX)
            // body = "<callId>|<type>|<payload-with-arbitrary-pipes>"
            val firstPipe = body.indexOf('|')
            if (firstPipe < 0) return null
            val callId = body.substring(0, firstPipe)
            if (!CALL_ID_PATTERN.matches(callId)) return null
            val rest = body.substring(firstPipe + 1)
            val secondPipe = rest.indexOf('|')
            if (secondPipe < 0) return null
            val typeStr = rest.substring(0, secondPipe)
            val type = CallSignalType2124.fromCode(typeStr) ?: return null
            val payload = rest.substring(secondPipe + 1)
            // Reject oversized payloads before constructing anything downstream (SDP DoS guard).
            if (payload.length > MAX_SDP_PAYLOAD) return null
            return callId to type
        }
    }

    private companion object {
        const val MANAGER = "ui-lib/src/main/java/co/electriccoin/zcash/ui/call/VoiceCallManager.kt"
    }
}
