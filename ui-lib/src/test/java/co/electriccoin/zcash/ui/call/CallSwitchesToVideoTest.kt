package co.electriccoin.zcash.ui.call

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * ZC-1468 (OT213n B3; owner findings 2 Nov, point 8: "I cannot switch to video directly from the audio call ... We need
 * to have it"). At 669cf19e1 the video mode is fixed at call start: onOffer ignores every OFFER unless the state is
 * Connecting ("OFFER while not Connecting — ignoring"), onAnswer every ANSWER unless Dialling, and
 * onRenegotiationNeeded is empty, so no in-call OFFER/ANSWER can ever add the camera (lanes/L4_calls.md).
 *
 * Wanted (FIX_PLAN REVISION 1, B3), a pure `class CallRenegotiation(isCaller: Boolean)` in `co.electriccoin.zcash.ui.call`:
 * - `companion object { const val UPGRADE_TIMEOUT_MS = 5_000L }` (30_000L since ZC-1904);
 * - `enum class OfferDecision { ANSWER, IGNORE, ROLLBACK_LOCAL_THEN_ANSWER }` (nested);
 * - `fun startUpgrade(state: VoiceCallManager.CallState, nowMs: Long): Boolean`: true (send the upgrade OFFER now) only
 *   in InCall with no upgrade pending;
 * - `fun onRemoteOffer(state: VoiceCallManager.CallState): OfferDecision`: in InCall a renegotiation OFFER is answered;
 *   in Dialling, Idle and Ended it is ignored; glare (both sides offering): the caller keeps its offer (IGNORE), the
 *   callee rolls its own back and answers (ROLLBACK_LOCAL_THEN_ANSWER);
 * - `fun onRemoteAnswer(state: VoiceCallManager.CallState): Boolean`: true only for the answer to our pending upgrade;
 * - `fun shouldRollBack(nowMs: Long): Boolean`: true once, when an upgrade stays unanswered for UPGRADE_TIMEOUT_MS
 *   (a 2.12.4 peer drops it; see Frozen2124), and the audio call goes on;
 * and VoiceCallManager uses it in onOffer and onAnswer while InCall.
 *
 * Reached by name, so this class compiles before the fix. Two phones (and one 2.12.4 install) are the device check.
 */
class CallSwitchesToVideoTest {
    private val peer = "c".repeat(64)
    private val callId = "1f".repeat(16)
    private val inCall = VoiceCallManager.CallState.InCall(peer, callId)
    private val dialling = VoiceCallManager.CallState.Dialling(peer, callId)

    private val type: Class<*> by lazy {
        runCatching { Class.forName("co.electriccoin.zcash.ui.call.CallRenegotiation") }.getOrNull()
            ?: fail("ZC-1468: co.electriccoin.zcash.ui.call.CallRenegotiation does not exist; an audio call can never switch to video")
    }

    private inner class Reneg(
        isCaller: Boolean,
    ) {
        private val obj: Any = type.getConstructor(Boolean::class.javaPrimitiveType).newInstance(isCaller)
        private val stateType = VoiceCallManager.CallState::class.java

        fun startUpgrade(
            state: VoiceCallManager.CallState,
            nowMs: Long,
        ): Boolean {
            val method = type.getMethod("startUpgrade", stateType, Long::class.javaPrimitiveType)
            return method.invoke(obj, state, nowMs) as Boolean
        }

        fun onRemoteOffer(state: VoiceCallManager.CallState): String {
            val decision = type.getMethod("onRemoteOffer", stateType).invoke(obj, state) as Enum<*>
            return decision.name
        }

        fun onRemoteAnswer(state: VoiceCallManager.CallState): Boolean {
            val method = type.getMethod("onRemoteAnswer", stateType)
            return method.invoke(obj, state) as Boolean
        }

        fun shouldRollBack(nowMs: Long): Boolean {
            val method = type.getMethod("shouldRollBack", Long::class.javaPrimitiveType)
            return method.invoke(obj, nowMs) as Boolean
        }
    }

    @Test
    fun `the upgrade waits 30 s for an answer`() {
        // ZC-1904 (owner W-148): the other phone now asks its user first, so the caller waits about 30 s, not 5 s.
        assertEquals(30_000L, (type.getField("UPGRADE_TIMEOUT_MS").get(null) as Number).toLong(), "ZC-1904: upgrade timeout")
    }

    @Test
    fun `an upgrade starts only in a connected call`() {
        val r = Reneg(isCaller = true)
        listOf(
            VoiceCallManager.CallState.Idle,
            VoiceCallManager.CallState.Ringing(peer, callId),
            dialling,
            VoiceCallManager.CallState.Connecting(peer, callId),
            VoiceCallManager.CallState.Ended(CallEndReason.UserEnded),
        ).forEach { assertEquals(false, r.startUpgrade(it, 0L), "ZC-1468: no upgrade OFFER in $it") }
        assertEquals(true, r.startUpgrade(inCall, 0L), "ZC-1468: InCall must allow the switch to video")
        assertEquals(false, r.startUpgrade(inCall, 100L), "ZC-1468: one upgrade at a time")
    }

    @Test
    fun `an OFFER in a connected video call is answered`() {
        // ZC-1904 (owner W-148): an OFFER in an audio call asks the user first (Zc1904VideoUpgradeNeedsConsentTest);
        // once the call carries video, the other phone adding its camera is answered as before.
        for (isCaller in listOf(true, false)) {
            val decision = Reneg(isCaller).onRemoteOffer(inCall.copy(isVideo = true))
            assertEquals("ANSWER", decision, "ZC-1468: a renegotiation OFFER in a video call must be answered (isCaller=$isCaller)")
        }
    }

    @Test
    fun `an OFFER outside a call or while dialling is ignored`() {
        val r = Reneg(isCaller = false)
        assertEquals("IGNORE", r.onRemoteOffer(VoiceCallManager.CallState.Idle), "Idle")
        assertEquals("IGNORE", r.onRemoteOffer(VoiceCallManager.CallState.Ended(CallEndReason.UserEnded)), "Ended")
        assertEquals("IGNORE", r.onRemoteOffer(dialling), "Dialling")
    }

    @Test
    fun `glare - the caller's offer wins`() {
        val caller = Reneg(isCaller = true)
        assertTrue(caller.startUpgrade(inCall, 0L))
        assertEquals("IGNORE", caller.onRemoteOffer(inCall), "ZC-1468: in glare the caller keeps its own offer")
        assertEquals(true, caller.onRemoteAnswer(inCall), "the caller's offer is then answered")

        val callee = Reneg(isCaller = false)
        assertTrue(callee.startUpgrade(inCall, 0L))
        assertEquals("ROLLBACK_LOCAL_THEN_ANSWER", callee.onRemoteOffer(inCall), "ZC-1468: in glare the callee rolls back and answers")
        assertEquals(false, callee.shouldRollBack(10_000L), "the callee's dropped offer has no timeout left")
        assertEquals(false, callee.onRemoteAnswer(inCall), "the callee waits for no answer after rolling back")
    }

    @Test
    fun `an answered upgrade does not roll back`() {
        val r = Reneg(isCaller = false)
        assertTrue(r.startUpgrade(inCall, 1_000L))
        assertEquals(true, r.onRemoteAnswer(inCall), "ZC-1468: the answer to our upgrade is applied")
        assertEquals(false, r.shouldRollBack(60_000L), "an answered upgrade never rolls back")
        assertEquals(false, r.onRemoteAnswer(inCall), "a repeated ANSWER is dropped")
    }

    @Test
    fun `a stray ANSWER is dropped`() {
        assertEquals(false, Reneg(isCaller = true).onRemoteAnswer(inCall), "no upgrade pending")
    }

    @Test
    fun `an unanswered upgrade rolls back after 30 s, once`() {
        val r = Reneg(isCaller = true)
        assertTrue(r.startUpgrade(inCall, 1_000L))
        assertEquals(false, r.shouldRollBack(30_999L), "ZC-1904: not before 30 s")
        assertEquals(true, r.shouldRollBack(31_000L), "ZC-1468: an unanswered upgrade must roll back after 30 s")
        assertEquals(false, r.shouldRollBack(32_000L), "the rollback happens once")
        assertEquals(true, r.startUpgrade(inCall, 33_000L), "the user may try again after a rollback")
    }

    @Test
    fun `a 2 12 4 peer drops the upgrade OFFER, so the 2 13 phone rolls back and the audio call goes on`() {
        // The 2.12.4 callee in a connected audio call (its remote description was set at call setup).
        val old = Frozen2124(state = inCall, remoteDescriptionSet = true, activeCallId = callId)
        old.onOffer(peer, CallSignalEnvelope(callId, CallSignalType.OFFER, "v=0"))
        assertEquals(false, old.applied, "2.12.4 must not apply an OFFER in InCall")
        assertEquals(listOf("OFFER while not Connecting — ignoring"), old.warnings)

        val r = Reneg(isCaller = true)
        assertTrue(r.startUpgrade(inCall, 0L), "ZC-1468: the 2.13 caller sends the upgrade blind")
        // No ANSWER comes back from 2.12.4.
        assertEquals(true, r.shouldRollBack(30_000L), "ZC-1468: the 2.13 phone must roll back when 2.12.4 never answers")
    }

    @Test
    fun `the call manager consults the renegotiation in onOffer and onAnswer`() {
        val manager = callDir.resolve("VoiceCallManager.kt").readText()
        assertTrue("CallRenegotiation(" in manager, "ZC-1468: VoiceCallManager must hold a CallRenegotiation")
        assertTrue(".onRemoteOffer(" in functionBody(manager, "onOffer"), "ZC-1468: onOffer must ask CallRenegotiation in InCall")
        assertTrue(".onRemoteAnswer(" in functionBody(manager, "onAnswer"), "ZC-1468: onAnswer must ask CallRenegotiation in InCall")
        assertTrue(".shouldRollBack(" in manager, "ZC-1468: an unanswered upgrade must be rolled back")
    }

    /**
     * The 2.12.4 callee's OFFER guards, verbatim from `git show v2.12.4:ui-lib/src/main/java/co/electriccoin/zcash/ui/
     * call/VoiceCallManager.kt` lines 317-333 (onOffer up to the duplicate check). Changes: `suspend` dropped (the
     * guards call nothing suspending), and the SDP part after the guards (lines 334-346) is replaced by `applied = true`.
     * `_state`, `bufferedOffer`, `remoteDescriptionSet`, `belongsToActiveCall` and `Log` are stand-ins for the fields.
     */
    @Suppress("PropertyName", "UNUSED_PARAMETER", "unused", "ktlint")
    private class Frozen2124(
        state: VoiceCallManager.CallState,
        var remoteDescriptionSet: Boolean,
        private val activeCallId: String,
    ) {
        private class Holder(var value: VoiceCallManager.CallState)

        private val _state = Holder(state)
        private var bufferedOffer: CallSignalEnvelope? = null
        private val TAG = "VoiceCallManager"
        val warnings = mutableListOf<String>()
        var applied = false

        private val Log = object {
            fun w(tag: String, msg: String) {
                warnings += msg
            }
        }

        private fun belongsToActiveCall(sender: String, envCallId: String): Boolean = envCallId == activeCallId

        fun onOffer(sender: String, env: CallSignalEnvelope) {
            if (!belongsToActiveCall(sender, env.callId)) {
                Log.w(TAG, "OFFER from non-active peer or stale callId — ignoring"); return
            }
            // Buffer (one-shot) if the user hasn't accepted yet.
            if (_state.value is VoiceCallManager.CallState.Ringing) {
                if (bufferedOffer == null) bufferedOffer = env
                return
            }
            if (_state.value !is VoiceCallManager.CallState.Connecting) {
                Log.w(TAG, "OFFER while not Connecting — ignoring"); return
            }
            // Idempotent: a redelivered OFFER after we've already applied one would throw in
            // the native stack (wrong signalling state). Drop it.
            if (remoteDescriptionSet) {
                Log.w(TAG, "duplicate OFFER after remote description set — ignoring"); return
            }
            applied = true
        }
    }

    private val callDir: File by lazy {
        val start = File(System.getProperty("user.dir") ?: ".").absoluteFile
        var dir: File? = start
        repeat(8) {
            val current = dir ?: return@repeat
            listOf(current, current.resolve("ui-lib")).forEach { module ->
                val candidate = module.resolve("src/main/java/co/electriccoin/zcash/ui/call")
                if (candidate.isDirectory) return@lazy candidate
            }
            dir = current.parentFile
        }
        fail("could not locate ui-lib's call package from ${start.path}")
    }

    private fun functionBody(
        src: String,
        name: String,
    ): String {
        val at = Regex("""fun\s+$name\s*\(""").find(src) ?: fail("fun $name not found")
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
}
