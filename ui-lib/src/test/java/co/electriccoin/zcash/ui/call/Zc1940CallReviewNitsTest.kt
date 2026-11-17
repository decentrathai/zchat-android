package co.electriccoin.zcash.ui.call

import co.electriccoin.zcash.ui.screen.chat.view.ChatResourceText
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * ZC-1940 (Fable review of 3fc413a7c, two nits taken):
 * - IncomingCallActivity.onCreate turns on showWhenLocked/turnScreenOn before it knows a call exists: with no call
 *   manager it shows a blank screen over the lock screen for up to MANAGER_WAIT_MS. Wanted: over the lock screen
 *   from the first frame only when a call rings or runs (CallLockScreen.showOverLock); watchCall turns it on later.
 * - VoiceCallManager.holdVideoUpgrade's prompt timeout calls declineHeldUpgrade, whose endVideoQuestion cancels
 *   promptTimeoutJob, the very coroutine running it, before the suspending emit: the timed-out VIDEO_DECLINE is never
 *   sent and the caller waits out its own 30 s. Wanted: the timeout path clears promptTimeoutJob before declining.
 *
 * Source checks: neither an Activity nor VoiceCallManager (WebRTC) runs in a ui-lib unit test.
 */
class Zc1940CallReviewNitsTest {
    @Test
    fun `the call screen is over the lock screen at start only with a live call`() {
        val activity = ChatResourceText.source(CALL_ACTIVITY)
        val onCreate = CallSourceBlocks.body(activity, "override fun onCreate(")
        assertTrue("setOverLock(true)" !in onCreate, "ZC-1940: no blank screen over the lock screen without a call")
        assertTrue("CallLockScreen.showOverLock" in onCreate, "ZC-1940: only a ringing or running call shows over it")
    }

    @Test
    fun `the prompt timeout sends its decline before its own job is cancelled`() {
        val hold = CallSourceBlocks.body(ChatResourceText.source(MANAGER), "fun holdVideoUpgrade(")
        val timeout = hold.substringAfter("delay(CallRenegotiation.PROMPT_TIMEOUT_MS)", missingDelimiterValue = "")
        val cleared = timeout.indexOf("promptTimeoutJob = null")
        val declined = timeout.indexOf("declineHeldUpgrade(")
        assertTrue(declined >= 0, "the timeout declines the held OFFER")
        assertTrue(
            cleared in 0 until declined,
            "ZC-1940: the timeout must clear promptTimeoutJob before declineHeldUpgrade cancels it mid-emit",
        )
    }

    private companion object {
        const val CALL_ACTIVITY = "ui-lib/src/main/java/co/electriccoin/zcash/ui/call/IncomingCallActivity.kt"
        const val MANAGER = "ui-lib/src/main/java/co/electriccoin/zcash/ui/call/VoiceCallManager.kt"
    }
}
