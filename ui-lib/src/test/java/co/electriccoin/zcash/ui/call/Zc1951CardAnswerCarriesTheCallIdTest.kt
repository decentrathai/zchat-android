package co.electriccoin.zcash.ui.call

import co.electriccoin.zcash.ui.screen.chat.view.ChatResourceText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * ZC-1951 (review of the ZC-1935 fix): the card's Answer (the IncomingCallActivity intent and the CallActionReceiver
 * accept broadcast) carries no call id. IncomingCallActivity waits up to 15 s for whichever call rings and the receiver
 * calls acceptIncoming() with no id, so a call B that rings seconds after A ended can be answered by a tap on A's card.
 *
 * Wanted:
 * - the service passes the ringing call's id into `CallNotificationController.showIncoming(display, isVideo, callId)`,
 *   and both Answer intents carry it (`CallNotificationController.EXTRA_CALL_ID`);
 * - a pure rule `CallLockScreen.answerFromCard(cardCallId, state)`: true only while the card's call rings; a missing id
 *   never answers (the call screen only opens);
 * - the receiver and IncomingCallActivity answer with `acceptIncoming(<card id>)`; IncomingCallActivity waits only for a
 *   missing call manager, never for "any call that rings";
 * - `CallLockScreen.answerByBroadcast` has no fail-open default for fullScreenIntentAllowed (ZC-1924: unknown counts as
 *   not allowed), and AndroidCallRingPlayer no longer says the card's Answer works without an unlock.
 */
class Zc1951CardAnswerCarriesTheCallIdTest {
    private val peer = "c".repeat(64)
    private val callA = "a1".repeat(16)
    private val callB = "b2".repeat(16)

    private fun fromCard(
        cardCallId: String?,
        state: VoiceCallManager.CallState,
    ): Boolean = CallSourceBlocks.objectCall(POLICY, "answerFromCard", cardCallId, state) as Boolean

    @Test
    fun `the card answers only its own call while it rings`() {
        assertEquals(true, fromCard(callA, VoiceCallManager.CallState.Ringing(peer, callA)), "ZC-1951: A's card, A rings")
        assertEquals(
            true,
            fromCard(callA, VoiceCallManager.CallState.Ringing(peer, callA, isVideo = true)),
            "ZC-1951: video",
        )
        assertEquals(false, fromCard(callA, VoiceCallManager.CallState.Ringing(peer, callB)), "ZC-1951: A's tap never answers B")
        assertEquals(false, fromCard(null, VoiceCallManager.CallState.Ringing(peer, callA)), "ZC-1951: no id never answers")
        listOf(
            VoiceCallManager.CallState.Idle,
            VoiceCallManager.CallState.InCall(peer, callA),
            VoiceCallManager.CallState.Ended(CallEndReason.UserEnded),
        ).forEach { assertEquals(false, fromCard(callA, it), "ZC-1951: not ringing: $it") }
    }

    @Test
    fun `answerByBroadcast has no fail-open default`() {
        val overloads =
            Class
                .forName(POLICY)
                .methods
                .filter { it.name == "answerByBroadcast" }
                .map { it.parameterCount }
        assertEquals(listOf(4), overloads, "ZC-1951: only the four-argument answerByBroadcast (no default true)")
    }

    @Test
    fun `the card's Answer carries the call id to the code that answers`() {
        val service = CallSourceBlocks.source(CallSourceBlocks.SERVICE)
        assertTrue(
            Regex("""showIncoming\([^)]*ringing\.callId""").containsMatchIn(service),
            "ZC-1951: the service passes the ringing call's id to the card",
        )
        val notifications = CallSourceBlocks.source(CallSourceBlocks.NOTIFICATIONS)
        val show = CallSourceBlocks.block(notifications, "showIncoming", "    ")
        assertTrue("callId: String" in show, "ZC-1951: showIncoming takes the call id")
        assertTrue("const val EXTRA_CALL_ID" in notifications, "ZC-1951: the Answer intents name the call")
        assertTrue(
            Regex("""receiverPendingIntent\([^)]*ACTION_ACCEPT[^)]*callId""").containsMatchIn(show),
            "ZC-1951: the accept broadcast carries the call id",
        )
        assertTrue(
            Regex("""IncomingCallActivity\.intent\(context, accept = true, callId""").containsMatchIn(show),
            "ZC-1951: the call-screen Answer carries the call id",
        )

        val receiver = ChatResourceText.source(RECEIVER)
        assertTrue("EXTRA_CALL_ID" in receiver, "ZC-1951: the receiver reads the card's call id")
        assertTrue("CallLockScreen.answerFromCard(" in receiver, "ZC-1951: the receiver asks the rule")
        assertTrue("acceptIncoming()" !in receiver, "ZC-1951: the receiver never answers whichever call rings")

        val activity = ChatResourceText.source(CALL_ACTIVITY)
        val handle = CallSourceBlocks.body(activity, "private fun handleAccept(")
        assertTrue("EXTRA_CALL_ID" in handle, "ZC-1951: the call screen reads the card's call id")
        assertTrue("CallLockScreen.answerFromCard(" in handle, "ZC-1951: the call screen asks the rule")
        assertTrue(
            "it is VoiceCallManager.CallState.Ringing" !in handle,
            "ZC-1951: the call screen never waits for whichever call rings next",
        )
    }

    @Test
    fun `the ring player no longer promises an Answer without an unlock`() {
        val ring = ChatResourceText.source(RING_PLAYER)
        assertTrue("its Answer works without an unlock" !in ring, "ZC-1951: stale comment (ZC-1924 opens the call screen)")
    }

    private companion object {
        const val POLICY = "co.electriccoin.zcash.ui.call.CallLockScreen"
        const val RECEIVER = "ui-lib/src/main/java/co/electriccoin/zcash/ui/call/CallActionReceiver.kt"
        const val CALL_ACTIVITY = "ui-lib/src/main/java/co/electriccoin/zcash/ui/call/IncomingCallActivity.kt"
        const val RING_PLAYER = "ui-lib/src/main/java/co/electriccoin/zcash/ui/call/AndroidCallRingPlayer.kt"
    }
}
