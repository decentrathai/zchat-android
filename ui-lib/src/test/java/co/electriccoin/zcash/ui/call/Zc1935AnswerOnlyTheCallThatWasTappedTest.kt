package co.electriccoin.zcash.ui.call

import co.electriccoin.zcash.ui.screen.chat.view.ChatResourceText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * ZC-1935 (GPT cross-family review of 3fc413a7c, Fable finding 5): IncomingCallActivity's permission callback reads
 * CallController.current.value and calls acceptIncoming() / hangUp() on whatever call is there. If call A ends while
 * its permission dialog is open and call B rings, granting answers B (microphone on without an Answer tap) and denying
 * hangs up B. CallOverlay's RingingScreen has the same callback shape.
 *
 * Wanted:
 * - a pure rule `CallLockScreen.answerAfterPermissions(requestedCallId, state, micGranted)` returning
 *   AnswerAfterPermissions ACCEPT / END_MIC_DENIED / IGNORE: it acts only when [requestedCallId] (the call whose Answer
 *   was tapped) is still the ringing call; anything else is IGNORE;
 * - the activity keeps the tapped call id across a recreation (saved state) and acts through that rule;
 * - VoiceCallManager answers or ends only that call: `acceptIncoming(expectedCallId)` and `hangUpIfRinging(callId, …)`.
 *
 * Seams are reached by name, so this class compiles before the fix.
 */
class Zc1935AnswerOnlyTheCallThatWasTappedTest {
    private val peer = "c".repeat(64)
    private val callA = "1a".repeat(16)
    private val callB = "2b".repeat(16)

    private fun decide(
        requested: String?,
        state: VoiceCallManager.CallState,
        mic: Boolean,
    ): String = CallSourceBlocks.objectCall(POLICY, "answerAfterPermissions", requested, state, mic).toString()

    @Test
    fun `the tapped call still ringing is answered, or ended without the microphone`() {
        val ringingA = VoiceCallManager.CallState.Ringing(peer, callA)
        assertEquals("ACCEPT", decide(callA, ringingA, true), "ZC-1935: the tapped call, mic allowed")
        assertEquals("END_MIC_DENIED", decide(callA, ringingA, false), "ZC-1922: the tapped call, mic refused")
        assertEquals("ACCEPT", decide(callA, VoiceCallManager.CallState.Ringing(peer, callA, isVideo = true), true))
    }

    @Test
    fun `a different call that rings meanwhile is neither answered nor ended`() {
        val ringingB = VoiceCallManager.CallState.Ringing(peer, callB)
        assertEquals("IGNORE", decide(callA, ringingB, true), "ZC-1935: granting must not answer call B")
        assertEquals("IGNORE", decide(callA, ringingB, false), "ZC-1935: denying must not hang up call B")
    }

    @Test
    fun `no tapped call or a call that is no longer ringing does nothing`() {
        assertEquals("IGNORE", decide(null, VoiceCallManager.CallState.Ringing(peer, callA), true))
        listOf(
            VoiceCallManager.CallState.Idle,
            VoiceCallManager.CallState.Ended(CallEndReason.UserEnded),
            VoiceCallManager.CallState.Connecting(peer, callA),
            VoiceCallManager.CallState.InCall(peer, callA),
        ).forEach {
            assertEquals("IGNORE", decide(callA, it, true), "ZC-1935: $it")
            assertEquals("IGNORE", decide(callA, it, false), "ZC-1935: $it")
        }
    }

    @Test
    fun `the call screens act through the rule on the tapped call only`() {
        val activity = ChatResourceText.source(CALL_ACTIVITY)
        assertTrue("CallLockScreen.answerAfterPermissions(" in activity, "ZC-1935: the activity asks the rule")
        assertTrue("onSaveInstanceState" in activity, "ZC-1935: the tapped call id survives a recreation")
        assertTrue("acceptIncoming()" !in activity, "ZC-1935: the activity never answers whichever call rings")
        assertTrue("hangUpIfRinging(" in activity, "ZC-1935: a refusal ends only the tapped call")
        assertTrue("mgr.hangUp(" !in activity, "ZC-1935: a refusal never hangs up whichever call is there")

        val overlay = CallSourceBlocks.body(ChatResourceText.source(CallSourceBlocks.OVERLAY), "fun RingingScreen(")
        assertTrue("CallLockScreen.answerAfterPermissions(" in overlay, "ZC-1935: the in-app call screen asks the rule")
        assertTrue("acceptIncoming()" !in overlay, "ZC-1935: the in-app call screen answers the tapped call only")

        val manager = ChatResourceText.source(MANAGER)
        val accept = CallSourceBlocks.body(manager, "fun acceptIncoming(")
        assertTrue("expectedCallId" in accept, "ZC-1935: acceptIncoming checks the call id on its call thread")
        assertTrue("fun hangUpIfRinging(" in manager, "ZC-1935: VoiceCallManager ends only the named ringing call")
    }

    private companion object {
        const val POLICY = "co.electriccoin.zcash.ui.call.CallLockScreen"
        const val CALL_ACTIVITY = "ui-lib/src/main/java/co/electriccoin/zcash/ui/call/IncomingCallActivity.kt"
        const val MANAGER = "ui-lib/src/main/java/co/electriccoin/zcash/ui/call/VoiceCallManager.kt"
    }
}
