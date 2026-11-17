package co.electriccoin.zcash.ui.call

import co.electriccoin.zcash.ui.screen.chat.view.ChatResourceText
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * ZC-1938 (Fable review of 3fc413a7c, pre-existing): MainActivity still reads CallNotificationController
 * EXTRA_ACCEPT_CALL and, for 45 s (ACCEPT_CALL_WAIT_TIMEOUT), answers any call that rings. MainActivity is reachable from
 * other apps and the web through the exported launcher alias (app/src/main/AndroidManifest.xml), so any app could
 * open ZChat with that extra and have the next incoming call answered with the microphone on. Since ZC-1912 the card's
 * Answer opens IncomingCallActivity (not exported) or the CallActionReceiver broadcast, never MainActivity.
 *
 * Wanted: MainActivity no longer honours the extra and never answers a call. A source check: ui-lib has no
 * Robolectric, so the activity's intent handling cannot run in a unit test.
 */
class Zc1938WalletNeverAnswersCallsTest {
    @Test
    fun `the wallet activity does not answer calls from its launch intent`() {
        val main = ChatResourceText.source(MAIN_ACTIVITY)
        assertTrue("EXTRA_ACCEPT_CALL" !in main, "ZC-1938: MainActivity must not read the accept-call extra")
        assertTrue("acceptIncoming(" !in main, "ZC-1938: MainActivity must never answer a call")
        assertTrue("ACCEPT_CALL_WAIT_TIMEOUT" !in main, "ZC-1938: no 45 s auto-answer window")
    }

    @Test
    fun `the accept-call extra goes only to the call screen`() {
        val missed = CallSourceBlocks.body(ChatResourceText.source(CallSourceBlocks.NOTIFICATIONS), "fun showMissed(")
        assertTrue("EXTRA_ACCEPT_CALL" !in missed, "the missed-call card never carries the accept extra")
        val activity = ChatResourceText.source(CALL_ACTIVITY)
        assertTrue("EXTRA_ACCEPT_CALL" in activity, "IncomingCallActivity is the one Answer screen")
    }

    private companion object {
        const val MAIN_ACTIVITY = "ui-lib/src/main/java/co/electriccoin/zcash/ui/MainActivity.kt"
        const val CALL_ACTIVITY = "ui-lib/src/main/java/co/electriccoin/zcash/ui/call/IncomingCallActivity.kt"
    }
}
