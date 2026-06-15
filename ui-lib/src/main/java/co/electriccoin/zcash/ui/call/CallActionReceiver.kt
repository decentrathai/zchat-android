package co.electriccoin.zcash.ui.call

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Handles Accept / Decline taps from the incoming-call notification. Runs in the app
 * process (kept alive by the foreground service), so it can reach the process-wide
 * [CallController] and drive the active [VoiceCallManager] even while the screen is
 * locked — without requiring the user to unlock first.
 *
 * Registered in the manifest (not exported). Actions are namespaced to this package so
 * no other app can spoof them.
 */
class CallActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val mgr = CallController.current.value ?: run {
            Log.w(TAG, "call action ${intent.action} but no active VoiceCallManager")
            return
        }
        when (intent.action) {
            ACTION_ACCEPT -> {
                mgr.acceptIncoming()
                // Bring the in-call UI forward so the user sees mute/hangup.
                runCatching {
                    val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
                        ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    if (launch != null) context.startActivity(launch)
                }
            }
            ACTION_DECLINE -> mgr.hangUp(CallEndReason.Declined)
            else -> Log.w(TAG, "unknown call action: ${intent.action}")
        }
    }

    companion object {
        private const val TAG = "CallActionReceiver"
        const val ACTION_ACCEPT = "co.electriccoin.zcash.call.ACCEPT"
        const val ACTION_DECLINE = "co.electriccoin.zcash.call.DECLINE"
    }
}
