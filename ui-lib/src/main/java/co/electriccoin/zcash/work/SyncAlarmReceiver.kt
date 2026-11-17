package co.electriccoin.zcash.work

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import co.electriccoin.zcash.ui.screen.chat.util.DestroyWipe
import co.electriccoin.zcash.ui.screen.chat.util.ResetWipe
import co.electriccoin.zcash.ui.service.SyncForegroundService

/**
 * BroadcastReceiver that triggers a foreground sync via [SyncForegroundService]
 * and reschedules the next alarm. Used as an AlarmManager-based fallback to ensure
 * timely message delivery even when WorkManager is throttled by Doze or OEM battery
 * optimizations.
 */
class SyncAlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        // ZC-692-S: this process finished a Destroy All at its start. It neither starts the sync service nor sets the
        // next alarm until the app itself turns background sync on for a wallet.
        if (DestroyWipe.backgroundSyncHeld) return
        // ZC-090-R: a Reset of this process is ending it; the next process runs the pass.
        if (ResetWipe.resetRunning) return
        Log.d(TAG, "Alarm fired — starting FGS sync")

        try {
            SyncForegroundService.start(context)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start FGS from alarm", e)
        }

        // Reschedule the next alarm
        SyncAlarmScheduler.schedule(context)
    }

    companion object {
        private const val TAG = "SyncAlarmReceiver"
    }
}
