package co.electriccoin.zcash.ui.call

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Handles Accept / Decline taps from the incoming-call notification (ZC-1912: Accept for a voice call on a locked phone)
 * and Hang up on the ongoing-call notification (ZC-1964). Runs in the app
 * process (kept alive by the foreground service), so it can reach the process-wide
 * [CallController] and drive the active [VoiceCallManager] even while the screen is
 * locked — without requiring the user to unlock first.
 *
 * Registered in the manifest (not exported). Actions are namespaced to this package so
 * no other app can spoof them.
 */
class CallActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val manager = CallController.current.value
        val cardCallId = intent.getStringExtra(CallNotificationController.EXTRA_CALL_ID)
        when (intent.action) {
            // ZC-1964: the ongoing-call notification's Hang up (works on the lock screen, no unlock). ZC-1982: it ends
            // only the card's own call, and is decided before the manager check below, so a stale card (its call over,
            // or no call manager after a process death) is cancelled.
            ACTION_HANG_UP -> hangUp(context, cardCallId, OngoingCallNotice.hangUpTap(cardCallId, manager?.state?.value))
            else -> {
                if (manager == null) {
                    Log.w(TAG, "call action ${intent.action} but no active VoiceCallManager")
                    return
                }
                onIncomingCardAction(context, intent, cardCallId, manager)
            }
        }
    }

    /** ZC-1982: the ongoing card's Hang up, as [OngoingCallNotice.hangUpTap] decided. */
    private fun hangUp(
        context: Context,
        cardCallId: String?,
        tap: OngoingCallNotice.HangUpTap,
    ) {
        when (tap) {
            OngoingCallNotice.HangUpTap.HANG_UP ->
                cardCallId?.let { CallController.current.value?.hangUpIfCurrent(it, CallEndReason.UserEnded) }
            OngoingCallNotice.HangUpTap.CANCEL_CARD -> CallNotificationController(context).cancelOngoing()
            OngoingCallNotice.HangUpTap.IGNORE -> Log.w(TAG, "Hang up on another call's card: ignored")
        }
    }

    private fun onIncomingCardAction(
        context: Context,
        intent: Intent,
        cardCallId: String?,
        mgr: VoiceCallManager,
    ) {
        when (intent.action) {
            ACTION_ACCEPT -> {
                // ZC-1922: never answer without the microphone (the call would go silent). The card offers this
                // broadcast only with the microphone allowed; if it was revoked since, the call screen asks.
                val micGranted =
                    context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) ==
                        android.content.pm.PackageManager.PERMISSION_GRANTED
                // ZC-1951: only the call this card was posted for, while it still rings; no id never answers.
                val answer = CallLockScreen.answerFromCard(cardCallId, mgr.state.value)
                if (micGranted && answer) mgr.acceptIncoming(cardCallId)
                // ZC-1912: bring the call-only screen forward (mute, hang up), never the wallet. Android may refuse a
                // screen opened from a notification's receiver; a call screen already over the lock screen stays.
                val screen = IncomingCallActivity.intent(context, accept = answer && !micGranted, callId = cardCallId)
                runCatching { context.startActivity(screen) }
                    .onFailure { Log.w(TAG, "call screen could not open: ${it.javaClass.simpleName}") }
            }
            ACTION_DECLINE -> mgr.hangUp(CallEndReason.Declined)
            else -> Log.w(TAG, "unknown call action: ${intent.action}")
        }
    }

    companion object {
        private const val TAG = "CallActionReceiver"
        const val ACTION_ACCEPT = "co.electriccoin.zcash.call.ACCEPT"
        const val ACTION_DECLINE = "co.electriccoin.zcash.call.DECLINE"
        const val ACTION_HANG_UP = "co.electriccoin.zcash.call.HANG_UP"
    }
}
