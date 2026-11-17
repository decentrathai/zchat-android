package co.electriccoin.zcash.ui.call

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * ZC-1981 (Fable + GPT review of the ZC-1964 fix, high): SyncForegroundService decides the ongoing-call card only when
 * the call state or its `callScreenInFront` flow changes, and computes `inFront || processIsInFront()` at that moment. A
 * change of the process's foreground state alone re-evaluates nothing:
 *  - first session after a background start (ApplicationStateProvider.isInForeground starts true, so the service's
 *    first value is forced false and the following ON_START emits nothing): the call is answered on the call screen over
 *    the lock (processIsInFront() true, no card), then Home: the flow stays false, no emission, a live call with no Hang
 *    up card;
 *  - the same whenever the foreground flag stays stale (a third-party screen keeps isInForeground true over ON_STOP).
 *
 * Wanted: one rule the service collects, `OngoingCallNotice.cardFor(state: Flow<CallState>, callScreenShown:
 * Flow<Boolean>, appInForeground: Flow<Boolean>): Flow<CallState?>`, which emits the Connecting or InCall state the card
 * is for, or null for no card. The card is up whenever a call is Connecting or InCall and its call screen is not visible
 * (not shown, or shown while the app is not in front), and it is re-evaluated on every change of any of the three inputs:
 * removed when the call screen becomes visible or the call ends.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class Zc1981OngoingCardFollowsTheCallScreenTest {
    private val state = MutableStateFlow<VoiceCallManager.CallState>(VoiceCallManager.CallState.Idle)
    private val screenShown = MutableStateFlow(false)

    // ApplicationStateProviderImpl's first value is "in front", also for a process started in the background.
    private val appInFront = MutableStateFlow(true)

    private fun cards(): Flow<*> = CallSourceBlocks.objectCall(ONGOING, "cardFor", state, screenShown, appInFront) as Flow<*>

    private fun cardCallId(card: Any?): String? =
        when (card) {
            null -> null
            is VoiceCallManager.CallState.Connecting -> card.callId
            is VoiceCallManager.CallState.InCall -> card.callId
            else -> "unexpected card for $card"
        }

    @Test
    fun `Home with the call screen up posts the card although the foreground flag was stale`() =
        runTest {
            val seen = mutableListOf<Any?>()
            val flow = cards()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { flow.collect { seen += it } }
            state.value = VoiceCallManager.CallState.Ringing(PEER, CALL_A)
            assertNull(cardCallId(seen.lastOrNull()), "a ringing call has the incoming card, not the ongoing one")
            screenShown.value = true
            state.value = VoiceCallManager.CallState.Connecting(PEER, CALL_A)
            assertNull(cardCallId(seen.lastOrNull()), "the call screen over the lock is in front: no card")
            state.value = VoiceCallManager.CallState.InCall(PEER, CALL_A)
            assertNull(cardCallId(seen.lastOrNull()), "in call on the visible call screen: no card")
            appInFront.value = false
            assertEquals(
                CALL_A,
                cardCallId(seen.lastOrNull()),
                "ZC-1981: Home during a live call (only the process's foreground changed): the Hang up card must appear",
            )
            appInFront.value = true
            assertNull(cardCallId(seen.lastOrNull()), "ZC-1981: back on the visible call screen: the card is removed")
            screenShown.value = false
            assertEquals(
                CALL_A,
                cardCallId(seen.lastOrNull()),
                "ZC-1981: the call screen closed while the call lives (only the screen changed): the card must appear",
            )
        }

    @Test
    fun `a call that connects with its call screen not shown has the card while the app is in front too`() =
        runTest {
            val seen = mutableListOf<Any?>()
            val flow = cards()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { flow.collect { seen += it } }
            state.value = VoiceCallManager.CallState.Connecting(PEER, CALL_A)
            assertEquals(
                CALL_A,
                cardCallId(seen.lastOrNull()),
                "ZC-1981: connecting while another ZChat screen (not the call screen) is in front: the card is up",
            )
            appInFront.value = false
            assertEquals(CALL_A, cardCallId(seen.lastOrNull()), "ZC-1981: Home: the card stays")
            state.value = VoiceCallManager.CallState.InCall(PEER, CALL_A)
            val live = seen.lastOrNull()
            assertTrue(live is VoiceCallManager.CallState.InCall, "the card follows connecting -> in call ($live)")
            state.value = VoiceCallManager.CallState.Ended(CallEndReason.UserEnded)
            assertNull(cardCallId(seen.lastOrNull()), "ZC-1981: the call ended: the card is removed")
            state.value = VoiceCallManager.CallState.Idle
            assertNull(cardCallId(seen.lastOrNull()), "idle: no card")
        }

    @Test
    fun `the service posts the ongoing card from that rule, not from a one-off process check`() {
        val service = CallSourceBlocks.source(CallSourceBlocks.SERVICE)
        assertTrue(
            "OngoingCallNotice.cardFor(" in service,
            "ZC-1981: the service collects OngoingCallNotice.cardFor (call state, call screen, app foreground)",
        )
        assertTrue(
            !Regex("""OngoingCallNotice\.wanted\([^)]*processIsInFront""").containsMatchIn(service),
            "ZC-1981: the ongoing card is never decided by polling processIsInFront() only when the call state changes",
        )
    }

    private companion object {
        const val ONGOING = "co.electriccoin.zcash.ui.call.OngoingCallNotice"
        const val PEER = "ab"
        const val CALL_A = "a1"
    }
}
